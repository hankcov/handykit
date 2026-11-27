package com.handykit.app.feature.backup

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handykit.app.core.crypto.VaultSession
import com.handykit.app.core.ui.ConfirmDialog
import com.handykit.app.core.ui.InfoCard
import com.handykit.app.core.ui.LabeledField
import com.handykit.app.core.ui.LoadingRow
import com.handykit.app.core.ui.ScreenTitle
import com.handykit.app.core.ui.SectionHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupViewModel(
    private val app: Context,
    private val manager: BackupManager,
    private val vaultAccess: () -> VaultSession?,
) : ViewModel() {

    data class UiState(
        val domains: Set<BackupDomain> = BackupDomain.entries.toSet(),
        val includeAttachments: Boolean = true,
        val password: String = "",
        val confirmPassword: String = "",
        val importPassword: String = "",
        val preview: BackupPreview? = null,
        val previewUri: Uri? = null,
        val restoreSelection: Set<BackupDomain> = emptySet(),
        val busy: Boolean = false,
        val message: String = "",
        val lastExport: String = "",
        val lastImport: String = "",
        val attachmentBytes: Long = 0L,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val bytes = manager.estimateAttachmentBytes()
            _uiState.update { it.copy(attachmentBytes = bytes) }
        }
    }

    fun toggleDomain(domain: BackupDomain, enabled: Boolean) {
        _uiState.update { state ->
            val next = if (enabled) state.domains + domain else state.domains - domain
            state.copy(domains = next)
        }
    }

    fun setIncludeAttachments(value: Boolean) = _uiState.update { it.copy(includeAttachments = value) }

    fun setPassword(value: String) = _uiState.update { it.copy(password = value) }
    fun setConfirmPassword(value: String) = _uiState.update { it.copy(confirmPassword = value) }
    fun setImportPassword(value: String) = _uiState.update { it.copy(importPassword = value) }

    fun setMessage(message: String) = _uiState.update { it.copy(message = message) }
    fun clearMessage() = _uiState.update { it.copy(message = "") }
    fun clearResult() = _uiState.update { it.copy(lastExport = "", lastImport = "") }

    fun passwordConfirmed(): Boolean {
        val state = _uiState.value
        return state.password.length >= 6 && state.password == state.confirmPassword
    }

    fun exportTo(uri: Uri) {
        val state = _uiState.value
        if (!passwordConfirmed()) {
            _uiState.update { it.copy(message = "请先设置备份口令并确认一致（至少 6 位）；未导出文件") }
            return
        }
        if (state.domains.isEmpty()) {
            _uiState.update { it.copy(message = "请至少选择一个数据域；未导出文件") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "", lastExport = "") }
            try {
                val output = app.contentResolver.openOutputStream(uri)
                    ?: throw BackupException("无法写入所选位置")
                val result = manager.export(
                    options = BackupOptions(state.domains.toList(), state.includeAttachments),
                    password = state.password.toCharArray(),
                    output = output,
                )
                val summary = buildString {
                    append("已导出 ${result.bytes / 1024} KB：")
                    append(result.counts.entries.joinToString("，") { "${it.key} ${it.value} 条" })
                    result.warnings.forEach { append("\n").append(it) }
                }
                _uiState.update { it.copy(busy = false, lastExport = summary) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(busy = false, message = "导出失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun inspect(uri: Uri) {
        val password = _uiState.value.importPassword
        if (password.isBlank()) {
            _uiState.update {
                it.copy(message = "请输入备份口令后再预览；未读取或改动任何数据", preview = null)
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "", preview = null, previewUri = null) }
            try {
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw BackupException("无法读取所选文件")
                val preview = manager.inspect(input, password.toCharArray())
                _uiState.update {
                    it.copy(
                        busy = false,
                        preview = preview,
                        previewUri = uri,
                        restoreSelection = preview.domains.toSet(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(busy = false, preview = null, previewUri = null,
                        message = "导入失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun toggleRestoreDomain(domain: BackupDomain, enabled: Boolean) {
        _uiState.update { state ->
            val next = if (enabled) state.restoreSelection + domain else state.restoreSelection - domain
            state.copy(restoreSelection = next)
        }
    }

    fun clearPreview() = _uiState.update {
        it.copy(preview = null, previewUri = null, restoreSelection = emptySet(), importPassword = "")
    }

    fun confirmRestore() {
        val state = _uiState.value
        val uri = state.previewUri ?: return
        val preview = state.preview ?: return
        val selected = state.restoreSelection.filter { it in preview.domains }
        if (selected.isEmpty()) {
            _uiState.update { it.copy(message = "请选择至少一个要恢复的数据域") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, message = "", lastImport = "") }
            try {
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw BackupException("无法读取所选备份文件")
                val result = manager.restore(input, state.importPassword.toCharArray(), selected)
                val summary = buildString {
                    append("已恢复：")
                    append(result.counts.entries.joinToString("，") { "${it.key} ${it.value} 条" })
                    result.warnings.forEach { append("\n").append(it) }
                }
                _uiState.update {
                    it.copy(
                        busy = false, preview = null, previewUri = null,
                        restoreSelection = emptySet(), importPassword = "", lastImport = summary,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(busy = false, message = "恢复失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }
}

class BackupViewModelFactory(
    private val context: Context,
    private val vaultAccess: () -> VaultSession?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            val app = context.applicationContext
            return BackupViewModel(app, BackupManager(app, vaultAccess), vaultAccess) as T
        }
        throw IllegalArgumentException("未支持的 ViewModel: ${modelClass.name}")
    }
}

@Composable
fun BackupScreen(
    modifier: Modifier = Modifier,
    /** 宿主传入已解锁的笔记会话；不传则笔记域不可用并明确提示。 */
    vaultSessionProvider: () -> VaultSession? = { null },
    onBack: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val factory = remember { BackupViewModelFactory(context.applicationContext, vaultSessionProvider) }
    val vm: BackupViewModel = viewModel(factory = factory)
    val ui by vm.uiState.collectAsStateWithLifecycle()

    var importUri by remember { mutableStateOf<Uri?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val vaultUnlocked = (vaultSessionProvider()?.isValid == true)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) vm.exportTo(uri) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            vm.clearMessage()
            vm.clearPreview()
            importUri = uri
        }
    }

    LaunchedEffect(ui.message) {
        if (ui.message.isNotBlank()) Toast.makeText(context, ui.message, Toast.LENGTH_LONG).show()
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle(title = "备份与恢复", subtitle = "分域加密 · 完整校验后才覆盖", onBack = onBack)
        if (ui.busy) LoadingRow("处理中，请勿关闭页面…")

        SectionHeader(text = "选择备份域")
        backupDomains.forEach { info ->
            val domain = info.domain
            DomainRow(
                name = info.name,
                included = info.included,
                excluded = info.excluded,
                checked = domain in ui.domains,
                extra = when (domain) {
                    BackupDomain.NOTES -> if (!vaultUnlocked) "需先解锁安全笔记，否则不导出" else "已在解锁会话中，使用独立笔记密钥"
                    BackupDomain.AI -> "API Key 与敏感头始终排除"
                    BackupDomain.MUSIC -> "音频缓存排除，恢复后需重新缓存"
                    else -> ""
                },
                onCheckedChange = { vm.toggleDomain(domain, it) },
            )
            if (domain == BackupDomain.AI && domain in ui.domains) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("包含 AI 附件内容", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "约 ${ui.attachmentBytes / (1024 * 1024)} MB；附件不含凭据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ui.includeAttachments, onCheckedChange = vm::setIncludeAttachments)
                }
            }
        }

        SectionHeader(text = "导出备份")
        Column(Modifier.padding(horizontal = 16.dp)) {
            LabeledField(
                label = "备份口令",
                value = ui.password,
                onValueChange = vm::setPassword,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            LabeledField(
                label = "确认备份口令",
                value = ui.confirmPassword,
                onValueChange = vm::setConfirmPassword,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "口令只用于 PBKDF2 派生加密密钥，不保存在应用中；口令丢失无法恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (!vm.passwordConfirmed()) {
                        vm.setMessage("请先设置备份口令并确认一致（至少 6 位）；未导出文件")
                    } else {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                        exportLauncher.launch("handykit-backup-$stamp.hkb")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.busy,
            ) { Text("选择位置并导出") }
            if (ui.lastExport.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                InfoCard(title = "导出结果", value = "成功", note = ui.lastExport)
            }
        }

        SectionHeader(text = "导入恢复")
        Column(Modifier.padding(horizontal = 16.dp)) {
            Button(
                onClick = { importLauncher.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !ui.busy,
            ) { Text("选择备份文件") }

            if (importUri != null) {
                Spacer(Modifier.height(8.dp))
                Text("文件：${displayName(context, importUri)}", style = MaterialTheme.typography.bodySmall)
                LabeledField(
                    label = "备份口令",
                    value = ui.importPassword,
                    onValueChange = vm::setImportPassword,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { importUri?.let { vm.inspect(it) } }, enabled = !ui.busy) { Text("预览") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { importUri = null; vm.clearPreview() }) { Text("取消") }
                }
            }

            ui.preview?.let { preview ->
                Spacer(Modifier.height(12.dp))
                InfoCard(
                    title = "备份信息",
                    value = "版本 ${preview.version} · ${preview.domains.size} 个数据域",
                    note = "创建时间：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.createdAt))}",
                )
                Spacer(Modifier.height(8.dp))
                preview.domains.forEach { domain ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = domain in ui.restoreSelection,
                            onCheckedChange = { vm.toggleRestoreDomain(domain, it) },
                        )
                        Text(
                            "${domainLabel(domain)}：${preview.counts[domain.name] ?: 0} 条记录",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                preview.warnings.forEach { warning ->
                    Text(
                        "· $warning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                    )
                }
                if (preview.hasNotes && !vaultUnlocked) {
                    Text(
                        "· 笔记域需先解锁才能恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { confirmRestore = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !ui.busy && ui.restoreSelection.isNotEmpty(),
                ) { Text("确认恢复所选域") }
            }

            if (ui.lastImport.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                InfoCard(title = "恢复结果", value = "成功", note = ui.lastImport)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmRestore) {
        ConfirmDialog(
            title = "确认恢复",
            message = "将用备份内容原子覆盖所选数据域，其他域不动。校验已通过；口令、完整性或版本任何一项失败都不会改动原数据。",
            confirmText = "恢复",
            danger = true,
            onConfirm = { confirmRestore = false; vm.confirmRestore() },
            onDismiss = { confirmRestore = false },
        )
    }
}

private data class DomainInfo(
    val domain: BackupDomain,
    val name: String,
    val included: String,
    val excluded: String,
)

private val backupDomains = listOf(
    DomainInfo(
        BackupDomain.NOTES, "安全笔记",
        "解锁后的笔记标题/正文/分类（独立密钥）",
        "不导出：笔记库主口令、设备 Keystore 密钥",
    ),
    DomainInfo(
        BackupDomain.AI, "AI",
        "会话 / 消息 / 角色 / 服务商配置 / 附件",
        "始终排除：API Key、敏感请求头；附件可排除",
    ),
    DomainInfo(
        BackupDomain.FUEL, "油耗",
        "车辆信息 + 加油记录（含无效区间标记）",
        "无",
    ),
    DomainInfo(
        BackupDomain.SCALE, "体脂",
        "成员 + 测量 + 来源 + 算法版本",
        "无",
    ),
    DomainInfo(
        BackupDomain.MUSIC, "音乐元数据",
        "曲目来源 / 分 P / 歌单 / 播放进度",
        "默认排除：音频缓存（恢复后需重新缓存）",
    ),
)

private fun domainLabel(domain: BackupDomain): String = when (domain) {
    BackupDomain.NOTES -> "安全笔记"
    BackupDomain.AI -> "AI"
    BackupDomain.FUEL -> "油耗"
    BackupDomain.SCALE -> "体脂"
    BackupDomain.MUSIC -> "音乐元数据"
}

@Composable
private fun DomainRow(
    name: String,
    included: String,
    excluded: String,
    checked: Boolean,
    extra: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text("包含：$included", style = MaterialTheme.typography.bodySmall)
            Text("排除：$excluded", style = MaterialTheme.typography.bodySmall)
            if (extra.isNotBlank()) {
                Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri?): String {
    if (uri == null) return "未选择"
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else uri.toString()
    } ?: uri.toString()
}
