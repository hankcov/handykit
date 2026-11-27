package com.handykit.app.feature.note

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

enum class VaultState { LOADING, SETUP, LOCKED, UNLOCKED }

@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewModel(private val repo: NoteRepository) : ViewModel() {

    companion object {
        private const val MIN_PASSWORD_LENGTH = 6
        private const val IDLE_TIMEOUT_MS = 5L * 60 * 1000
        private const val CLIPBOARD_TTL_MS = 30L * 1000
    }

    private val _state = MutableStateFlow(VaultState.LOADING)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    fun setPassword(value: String) { _password.value = value }

    private val _confirmPassword = MutableStateFlow("")
    val confirmPassword: StateFlow<String> = _confirmPassword.asStateFlow()
    fun setConfirmPassword(value: String) { _confirmPassword.value = value }

    private val _unlockPassword = MutableStateFlow("")
    val unlockPassword: StateFlow<String> = _unlockPassword.asStateFlow()
    fun setUnlockPassword(value: String) { _unlockPassword.value = value }

    private val _oldPassword = MutableStateFlow("")
    val oldPassword: StateFlow<String> = _oldPassword.asStateFlow()
    fun setOldPassword(value: String) { _oldPassword.value = value }

    private val _newPassword = MutableStateFlow("")
    val newPassword: StateFlow<String> = _newPassword.asStateFlow()
    fun setNewPassword(value: String) { _newPassword.value = value }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(value: String) { _query.value = value; touch() }

    /** 会话换代计数：解锁/锁定后强制重订阅笔记流，杜绝迟到明文。 */
    private val epoch = MutableStateFlow(0)

    val notes: StateFlow<List<Note>> = combine(epoch, _query) { _, q -> q }
        .flatMapLatest { q ->
            val s = repo.currentSession()
            if (s.isValid) {
                repo.notes(s).map { list -> list.filter { noteMatches(it, q) } }
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val biometricEnabled: StateFlow<Boolean> = repo.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 详情页正文：默认 null（隐藏），点「显示正文」后才赋值。 */
    private val _revealedBody = MutableStateFlow<String?>(null)
    val revealedBody: StateFlow<String?> = _revealedBody.asStateFlow()

    private var idleJob: Job? = null
    private var clipboardJob: Job? = null
    @Volatile
    private var lastInteraction = SystemClock.elapsedRealtime()
    @Volatile
    private var copiedAt = 0L

    init {
        viewModelScope.launch {
            _state.value = if (repo.isInitialized()) VaultState.LOCKED else VaultState.SETUP
            if (repo.currentSession().isValid) repo.lock()
        }
    }

    fun clearMessage() { _message.value = null }

    /** 供 UI 回调报告非加密错误（例如生物识别被取消、文件读写失败）。 */
    fun notify(text: String) { _message.value = text }

    /** 首次设置主口令：二次一致性在 ViewModel 再校验一次（UI 也会拦截）。 */
    fun setup() {
        val first = _password.value
        val second = _confirmPassword.value
        if (first.length < MIN_PASSWORD_LENGTH) {
            _message.value = "主口令至少 $MIN_PASSWORD_LENGTH 位"
            return
        }
        if (first != second) {
            _message.value = "两次输入的口令不一致"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                val chars = first.toCharArray()
                try {
                    repo.setup(chars)
                } finally {
                    chars.fill('\u0000')
                }
                _password.value = ""
                _confirmPassword.value = ""
                val unlockChars = first.toCharArray()
                val ok = try {
                    repo.unlock(unlockChars)
                } finally {
                    unlockChars.fill('\u0000')
                }
                if (ok) {
                    onUnlocked()
                    _message.value = "主口令已设置并解锁，请务必记住它"
                } else {
                    _state.value = VaultState.LOCKED
                    _message.value = "设置成功，请用主口令解锁"
                }
            } catch (t: Throwable) {
                _message.value = "设置失败：${t.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun unlock() {
        val chars = _unlockPassword.value.toCharArray()
        viewModelScope.launch {
            _busy.value = true
            try {
                val ok = try {
                    repo.unlock(chars)
                } finally {
                    chars.fill('\u0000')
                }
                if (ok) {
                    _unlockPassword.value = ""
                    onUnlocked()
                } else {
                    _message.value = "主口令错误，笔记未解锁"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** 生物识别/设备凭据认证成功后由 UI 回调。 */
    fun unlockBiometric() {
        viewModelScope.launch {
            _busy.value = true
            try {
                val ok = repo.unlockBiometric(null)
                if (ok) {
                    onUnlocked()
                } else {
                    _message.value = "指纹解锁失败：未启用、密钥失效或认证已超时，请用主口令解锁"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** 必须在生物识别认证成功回调中调用，否则 Keystore 不允许使用认证约束密钥。 */
    fun enableBiometric() {
        viewModelScope.launch {
            val ok = repo.enableBiometric(repo.currentSession())
            _message.value = if (ok) {
                "已启用指纹解锁（密钥绑定用户认证）"
            } else {
                "启用失败：请在生物识别验证后立即重试，或设备未设置锁屏凭据"
            }
        }
    }

    fun disableBiometric() {
        viewModelScope.launch {
            repo.disableBiometric()
            _message.value = "已关闭指纹解锁，主口令不受影响"
        }
    }

    fun changePassword() {
        val old = _oldPassword.value
        val next = _newPassword.value
        if (next.length < MIN_PASSWORD_LENGTH) {
            _message.value = "新口令至少 $MIN_PASSWORD_LENGTH 位"
            return
        }
        if (old == next) {
            _message.value = "新口令不能与原口令相同"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            val o = old.toCharArray()
            val n = next.toCharArray()
            try {
                val ok = repo.changePassword(o, n)
                _message.value = if (ok) {
                    _oldPassword.value = ""
                    _newPassword.value = ""
                    "主口令已更新，笔记内容保持不变"
                } else {
                    "原口令不正确，未对数据做任何修改"
                }
            } finally {
                o.fill('\u0000')
                n.fill('\u0000')
                _busy.value = false
            }
        }
    }

    private fun onUnlocked() {
        _revealedBody.value = null
        _query.value = ""
        _message.value = null
        _state.value = VaultState.UNLOCKED
        epoch.value += 1
        lastInteraction = SystemClock.elapsedRealtime()
        restartIdleTimer()
    }

    /** 手动锁定 / 切后台 / 熄屏 / 离开页面都走这里。 */
    fun lockNow() {
        if (_state.value != VaultState.UNLOCKED && !repo.currentSession().isValid) return
        repo.lock()
        epoch.value += 1
        idleJob?.cancel()
        _revealedBody.value = null
        _query.value = ""
        _unlockPassword.value = ""
        _message.value = null
        _state.value = VaultState.LOCKED
    }

    /** 任意交互都重置 5 分钟闲置计时。 */
    fun touch() {
        if (_state.value != VaultState.UNLOCKED) return
        lastInteraction = SystemClock.elapsedRealtime()
        restartIdleTimer()
    }

    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = viewModelScope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (isActive && _state.value == VaultState.UNLOCKED &&
                SystemClock.elapsedRealtime() - lastInteraction >= IDLE_TIMEOUT_MS
            ) {
                lockNow()
            }
        }
    }

    fun revealBody(id: String) {
        viewModelScope.launch {
            touch()
            val text = repo.revealBody(repo.currentSession(), id)
            if (text == null) {
                _revealedBody.value = ""
                _message.value = "正文解密失败：会话可能已锁定或数据损坏"
            } else {
                _revealedBody.value = text
            }
        }
    }

    fun hideBody() {
        _revealedBody.value = null
    }

    /**
     * 复制正文并启动 30 秒清理。只清理自己复制的内容：
     * 记录明文 SHA-256，到期后读取剪贴板再比对，不一致说明用户已复制别的内容，不动它。
     */
    fun copyBody(context: Context, text: String) {
        if (_state.value != VaultState.UNLOCKED) return
        touch()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        try {
            cm.setPrimaryClip(ClipData.newPlainText("安全笔记", text))
        } catch (t: Throwable) {
            _message.value = "复制失败：${t.message ?: "剪贴板不可用"}"
            return
        }
        val expected = sha256(text)
        copiedAt = SystemClock.elapsedRealtime()
        clipboardJob?.cancel()
        clipboardJob = viewModelScope.launch {
            delay(CLIPBOARD_TTL_MS)
            if (SystemClock.elapsedRealtime() - copiedAt < CLIPBOARD_TTL_MS) return@launch
            val stillMine = try {
                val clip = cm.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    false
                } else {
                    sha256(clip.getItemAt(0).coerceToText(context).toString()) == expected
                }
            } catch (t: Throwable) {
                false
            }
            if (stillMine) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cm.clearPrimaryClip()
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                } catch (t: Throwable) {
                    // 系统拒绝清理时不提示正文内容，只给通用提示
                }
            }
        }
        _message.value = "已复制，30 秒后自动清理剪贴板"
    }

    fun saveNote(id: String?, title: String, body: String, category: String) {
        if (title.isBlank()) {
            _message.value = "标题不能为空"
            return
        }
        viewModelScope.launch {
            touch()
            try {
                repo.upsert(
                    session = repo.currentSession(),
                    title = title.trim(),
                    body = body,
                    category = category.trim().ifBlank { "未分类" },
                    id = id,
                )
                _message.value = if (id == null) "笔记已创建" else "笔记已更新"
            } catch (t: Throwable) {
                _message.value = "保存失败：${t.message ?: "会话可能已锁定"}"
            }
        }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch {
            touch()
            repo.delete(id)
            _revealedBody.value = null
            _message.value = "笔记已删除"
        }
    }

    /** 分类候选：从当前解密记录中提取，不建明文索引。 */
    suspend fun loadCategories(): Set<String> = repo.allCategories(repo.currentSession())

    suspend fun buildBackup(): String {
        touch()
        return repo.exportBackup()
    }

    fun importBackup(payload: String) {
        viewModelScope.launch {
            try {
                val count = repo.importBackup(payload)
                epoch.value += 1
                _revealedBody.value = null
                _message.value = null
                _state.value = VaultState.LOCKED
                _message.value = "已恢复 $count 条加密笔记，请用备份对应的主口令解锁"
            } catch (t: Throwable) {
                _message.value = "恢复失败：${t.message ?: "备份格式错误"}"
            }
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun onCleared() {
        idleJob?.cancel()
        clipboardJob?.cancel()
        super.onCleared()
    }
}

class NoteViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NoteViewModel::class.java)) {
            return NoteViewModel(NoteRepository(context.applicationContext)) as T
        }
        throw IllegalArgumentException("未支持的 ViewModel: ${modelClass.name}")
    }
}
