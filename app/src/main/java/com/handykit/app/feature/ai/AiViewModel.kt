package com.handykit.app.feature.ai

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.handykit.app.core.db.AiAttachment
import com.handykit.app.core.db.AiMessage
import com.handykit.app.core.db.AiProvider
import com.handykit.app.core.db.AiRole
import com.handykit.app.core.db.AiSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

sealed interface AiEffect {
    data class Toast(val message: String) : AiEffect
}

class AiViewModel(private val repo: AiRepository) : ViewModel() {

    val sessions: StateFlow<List<AiSession>> = repo.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val roles: StateFlow<List<AiRole>> = repo.observeRoles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val providers: StateFlow<List<AiProvider>> = repo.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedId = MutableStateFlow<String?>(null)

    /** 切换会话时按 fallback 选中列表第一项；生成中的结果始终归属发送时的会话。 */
    val currentSession: StateFlow<AiSession?> = combine(sessions, _selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val currentSessionId: StateFlow<String?> = currentSession.map { it?.id }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: StateFlow<List<AiMessage>> = currentSessionId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.observeMessages(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _attachments = MutableStateFlow<List<AiAttachment>>(emptyList())
    val attachments: StateFlow<List<AiAttachment>> = _attachments.asStateFlow()

    /** 尚未随任何消息发出的附件，即本次可发送的候选。 */
    val pendingAttachments: StateFlow<List<AiAttachment>> = combine(attachments, messages) { atts, msgs ->
        val used = msgs.flatMap { AiRepository.decodeIds(it.attachmentIdsJson) }.toSet()
        atts.filter { it.id !in used }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val drafts = mutableMapOf<String, String>()
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    /** 用 Compose 的 mutableStateOf 累积流式片段；只在开始和结束时写库，不逐字符落库。 */
    private val _streamingText = mutableStateOf("")
    val streamingText: State<String> get() = _streamingText

    private val _streamingSessionId = MutableStateFlow<String?>(null)
    val streamingSessionId: StateFlow<String?> = _streamingSessionId.asStateFlow()

    val networkEnabled: StateFlow<Boolean> = repo.networkEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val firstUseSeen: StateFlow<Boolean> = repo.firstUseSeen()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _effects = MutableSharedFlow<AiEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<AiEffect> = _effects.asSharedFlow()

    private var activeCall: Call? = null
    private val stopRequested = AtomicBoolean(false)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repo.markUnfinishedAsInterrupted()
        }
        viewModelScope.launch {
            currentSessionId.collect { sid ->
                _attachments.value = if (sid == null) emptyList() else repo.attachments(sid)
            }
        }
    }

    private suspend fun toast(text: String) {
        _effects.emit(AiEffect.Toast(text))
    }

    // ---- 会话 ----

    fun selectSession(id: String) {
        val oldId = currentSession.value?.id
        if (oldId != null && oldId != id) drafts[oldId] = _draft.value
        if (oldId != id) {
            _selectedId.value = id
            _draft.value = drafts[id].orEmpty()
        }
    }

    fun newSession() {
        viewModelScope.launch(Dispatchers.IO) {
            val provider = repo.providers().firstOrNull()
            if (provider == null) {
                toast("请先配置一个服务商")
                return@launch
            }
            val role = repo.roles().firstOrNull()
            val session = repo.createSession(role, provider)
            repo.upsertSession(session)
            selectSession(session.id)
            toast("已新建会话")
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteSession(id)
            drafts.remove(id)
            if (currentSession.value?.id == id) {
                _selectedId.value = null
                _draft.value = ""
            }
            toast("会话及其消息、附件已删除")
        }
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = repo.session(id) ?: return@launch
            if (title.isBlank()) return@launch
            repo.upsertSession(session.copy(title = title.trim(), updatedAt = System.currentTimeMillis()))
        }
    }

    fun clearContext(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.clearContext(id)
            toast("上下文已清除，历史消息仍保留")
        }
    }

    // ---- 草稿 / 附件 ----

    fun setDraft(text: String) {
        val sid = currentSession.value?.id ?: return
        drafts[sid] = text
        _draft.value = text
    }

    private fun clearDraft(sessionId: String) {
        drafts[sessionId] = ""
        if (currentSession.value?.id == sessionId) _draft.value = ""
    }

    fun addAttachments(uris: List<Uri>) {
        val sid = currentSession.value?.id ?: return
        if (generating.value) return
        viewModelScope.launch(Dispatchers.IO) {
            var count = pendingAttachments.value.size
            for (uri in uris) {
                if (count >= AttachmentLimits.MAX_COUNT) {
                    toast("附件最多 ${AttachmentLimits.MAX_COUNT} 个")
                    break
                }
                try {
                    val prepared = repo.prepareAttachment(uri, sid, count)
                    repo.saveAttachment(prepared, sid)
                    count++
                } catch (e: AttachmentException) {
                    toast(e.message ?: "附件处理失败")
                }
            }
            _attachments.value = repo.attachments(sid)
        }
    }

    fun removeAttachment(id: String) {
        val sid = currentSession.value?.id ?: return
        if (generating.value) return
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteAttachment(sid, id)
            _attachments.value = repo.attachments(sid)
        }
    }

    suspend fun contextPreview(input: String): List<String> {
        val session = currentSession.value ?: return emptyList()
        return repo.contextPreview(session, input.trim(), pendingAttachments.value.map { it.id })
    }

    // ---- 角色 / 服务商 ----

    fun saveRole(role: AiRole) {
        viewModelScope.launch(Dispatchers.IO) {
            if (role.name.isBlank() || role.systemPrompt.isBlank()) {
                toast("角色名称与系统提示词不能为空")
                return@launch
            }
            repo.upsertRole(role)
            toast("角色已保存")
        }
    }

    fun deleteRole(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteRole(id)
            toast("角色已删除；已有会话仍保留角色快照")
        }
    }

    fun saveProvider(
        id: String,
        name: String,
        baseUrl: String,
        model: String,
        imageCapability: Boolean,
        pdfCapability: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (name.isBlank() || model.isBlank()) {
                toast("名称与模型不能为空")
                return@launch
            }
            runCatching { requireHttpsBaseUrl(baseUrl) }.exceptionOrNull()?.let { ex ->
                toast(ex.message ?: "Base URL 非法")
                return@launch
            }
            val existing = if (id.isBlank()) null else repo.provider(id)
            val provider = AiProvider(
                id = existing?.id ?: UUID.randomUUID().toString(),
                name = name.trim(),
                protocol = "openai",
                baseUrl = baseUrl.trim().trimEnd('/'),
                model = model.trim(),
                imageCapability = if (imageCapability) "user" else "unknown",
                pdfCapability = if (pdfCapability) "user" else "unknown",
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            repo.upsertProvider(provider)
            toast("服务商已保存")
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.deleteProvider(id)
            toast("服务商及其本机凭据已删除")
        }
    }

    suspend fun saveApiKey(providerId: String, apiKey: String) = repo.saveApiKey(providerId, apiKey)

    suspend fun saveHeaders(providerId: String, headers: List<AiHeaderEntry>) =
        repo.saveHeaders(providerId, headers)

    suspend fun hasApiKey(providerId: String): Boolean = repo.hasApiKey(providerId)

    suspend fun revealApiKey(providerId: String): String = repo.revealApiKey(providerId)

    suspend fun revealHeaders(providerId: String): List<AiHeaderEntry> = repo.revealHeaders(providerId)

    fun revealGate(activity: android.app.Activity): RevealGate = repo.revealGate(activity)

    fun setNetworkEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { repo.setNetworkEnabled(enabled) }
    }

    fun dismissFirstUse() {
        viewModelScope.launch(Dispatchers.IO) { repo.setFirstUseSeen() }
    }

    // ---- 消息级上下文控制 ----

    fun toggleIncludeInContext(messageId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = messages.value.firstOrNull { it.id == messageId } ?: return@launch
            repo.upsertMessage(target.copy(includeInContext = !target.includeInContext))
        }
    }

    // ---- 发送 / 停止 / 重试 ----

    fun send(input: String) {
        val session = currentSession.value ?: run {
            viewModelScope.launch { toast("请先新建会话") }
            return
        }
        if (generating.value) return
        val text = input.trim()
        val attachmentIds = pendingAttachments.value.map { it.id }
        if (text.isEmpty() && attachmentIds.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            val problem = repo.validateForSend(session.id)
            if (problem != null) {
                toast(problem)
                return@launch
            }
            _generating.value = true
            stopRequested.set(false)
            val now = System.currentTimeMillis()
            val userMessage = AiMessage(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                role = "user",
                content = text,
                status = "done",
                provider = session.providerId,
                model = session.model,
                createdAt = now,
                attachmentIdsJson = AiRepository.encodeIds(attachmentIds),
            )
            repo.upsertMessage(userMessage)
            if (session.title == "新会话" && text.isNotBlank()) {
                repo.upsertSession(
                    session.copy(title = text.take(16), updatedAt = System.currentTimeMillis()),
                )
            }
            val assistant = AiMessage(
                id = UUID.randomUUID().toString(),
                sessionId = session.id,
                role = "assistant",
                content = "",
                status = "streaming",
                provider = session.providerId,
                model = session.model,
                createdAt = now + 1,
                providerReady = false,
            )
            repo.upsertMessage(assistant)
            clearDraft(session.id)

            val history = repo.messages(session.id).filter { it.id != userMessage.id && it.id != assistant.id }
            runGeneration(session, assistant, text, attachmentIds, history)
        }
    }

    /** 重试复用原 assistant 行与原 user 行，不重复插入用户消息。 */
    fun retry(messageId: String? = null) {
        val sid = currentSession.value?.id ?: return
        if (generating.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val all = repo.messages(sid)
            val index = if (messageId != null) {
                all.indexOfLast { it.id == messageId }
            } else {
                all.indexOfLast {
                    it.role == "assistant" && it.status in setOf("failed", "stopped", "interrupted")
                }
            }
            if (index < 0) return@launch
            val assistant = all[index]
            if (assistant.role != "assistant") return@launch
            val user = all.take(index).lastOrNull { it.role == "user" } ?: return@launch
            val session = repo.session(sid) ?: return@launch
            val problem = repo.validateForSend(sid)
            if (problem != null) {
                toast(problem)
                return@launch
            }
            _generating.value = true
            stopRequested.set(false)
            val reset = assistant.copy(
                content = "",
                status = "streaming",
                errorKind = null,
                providerReady = false,
                createdAt = System.currentTimeMillis(),
            )
            repo.upsertMessage(reset)
            runGeneration(
                session = session,
                assistant = reset,
                input = user.content,
                attachmentIds = AiRepository.decodeIds(user.attachmentIdsJson),
                history = all.take(index).filter { it.id != user.id },
            )
        }
    }

    fun stop() {
        stopRequested.set(true)
        activeCall?.cancel()
    }

    private suspend fun runGeneration(
        session: AiSession,
        assistant: AiMessage,
        input: String,
        attachmentIds: List<String>,
        history: List<AiMessage>,
    ) {
        val sb = StringBuilder()
        _streamingSessionId.value = session.id
        _streamingText.value = ""
        try {
            val outcome = repo.stream(
                session = session,
                input = input,
                history = history,
                attachmentIds = attachmentIds,
                onCall = { call -> activeCall = call },
                onDelta = { delta ->
                    sb.append(delta)
                    _streamingText.value = sb.toString()
                },
            )
            val interrupted = outcome.interrupted || stopRequested.get()
            val finalText = sb.toString().ifBlank {
                if (interrupted) "已停止生成" else "（模型未返回内容）"
            }
            repo.upsertMessage(
                assistant.copy(
                    content = finalText,
                    status = if (interrupted) "stopped" else "done",
                    providerReady = true,
                    errorKind = null,
                ),
            )
            if (interrupted) toast("已停止生成，结果保留在对应会话")
        } catch (e: AiException) {
            repo.upsertMessage(
                assistant.copy(
                    content = sb.toString(),
                    status = "failed",
                    errorKind = e.kind.name.lowercase(),
                    providerReady = false,
                ),
            )
            toast(e.userMessage)
        } catch (e: Exception) {
            repo.upsertMessage(
                assistant.copy(
                    content = sb.toString(),
                    status = "failed",
                    errorKind = AiErrorKind.NETWORK.name.lowercase(),
                    providerReady = false,
                ),
            )
            toast("生成失败：网络或未知错误")
        } finally {
            activeCall = null
            _generating.value = false
            _streamingSessionId.value = null
            _streamingText.value = ""
        }
    }

    override fun onCleared() {
        activeCall?.cancel()
        activeCall = null
        super.onCleared()
    }
}

class AiViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AiViewModel::class.java)) {
            return AiViewModel(AiRepository(context.applicationContext)) as T
        }
        throw IllegalArgumentException("未支持的 ViewModel: ${modelClass.name}")
    }
}

/** UI 展示消息状态用的辅助函数，不暴露任何密钥信息。 */
fun statusLabel(status: String, errorKind: String?): String = when (status) {
    "pending" -> "等待中"
    "streaming" -> "生成中…"
    "done" -> "完成"
    "stopped" -> "已停止"
    "failed" -> "失败 · ${AiErrorKind.from(errorKind).label}"
    "interrupted" -> "被中断（应用曾重启）"
    else -> status
}
