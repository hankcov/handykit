package com.handykit.app.feature.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handykit.app.core.db.AiMessage
import com.handykit.app.core.ui.EmptyHint
import com.handykit.app.core.ui.ScreenTitle
import kotlinx.coroutines.launch

@Composable
fun AiScreen(
    onBack: (() -> Unit)? = null,
    vm: AiViewModel = viewModel(factory = AiViewModelFactory(LocalContext.current)),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val sessions by vm.sessions.collectAsState()
    val session by vm.currentSession.collectAsState()
    val messages by vm.messages.collectAsState()
    val roles by vm.roles.collectAsState()
    val providers by vm.providers.collectAsState()
    val draft by vm.draft.collectAsState()
    val generating by vm.generating.collectAsState()
    val streamingSid by vm.streamingSessionId.collectAsState()
    val networkEnabled by vm.networkEnabled.collectAsState()
    val pending by vm.pendingAttachments.collectAsState()
    val firstUseSeen by vm.firstUseSeen.collectAsState()
    val streaming = vm.streamingText.value

    var showSessions by rememberSaveable { mutableStateOf(false) }
    var showRoles by rememberSaveable { mutableStateOf(false) }
    var showProviders by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showContext by rememberSaveable { mutableStateOf(false) }
    var contextLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var firstUseDismissed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                is AiEffect.Toast -> snackbar.showSnackbar(effect.message)
            }
        }
    }

    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addAttachments(uris)
    }

    val role = roles.firstOrNull { it.id == session?.roleId }
    val provider = providers.firstOrNull { it.id == session?.providerId }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScreenTitle(
                    title = session?.title ?: "AI 助手",
                    subtitle = listOfNotNull(role?.name, provider?.name, session?.model)
                        .joinToString(" · ")
                        .ifBlank { "尚未选择服务商" },
                    onBack = onBack,
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                )

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showSessions = true }) {
                        Text(session?.title ?: "选择会话")
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "会话列表")
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { showRoles = true }) {
                        Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(role?.name ?: "角色")
                    }
                    TextButton(onClick = { showProviders = true }) {
                        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(provider?.model ?: "模型")
                    }
                }

                if (!networkEnabled) {
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            "联网已关闭：AI 暂不可发送，角色/会话/附件等本地功能仍可使用。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (messages.isEmpty()) {
                    EmptyHint("还没有消息。选择角色与服务商后，输入内容开始对话。")
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageRow(
                            message = msg,
                            showStreaming = msg.status == "streaming" && streamingSid == session?.id,
                            streamingText = streaming,
                            vm = vm,
                        )
                    }
                }

                if (pending.isNotEmpty()) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pending, key = { it.id }) { attachment ->
                            Card(shape = RoundedCornerShape(10.dp)) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(attachment.name, style = MaterialTheme.typography.bodySmall)
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { vm.removeAttachment(attachment.id) },
                                        enabled = !generating,
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "移除附件")
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = { attachmentLauncher.launch("*/*") },
                        enabled = !generating && session != null,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "添加附件")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { vm.setDraft(it) },
                        modifier = Modifier.weight(1f),
                        minLines = 1,
                        maxLines = 5,
                        placeholder = { Text("输入消息，支持换行…") },
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                contextLines = vm.contextPreview(draft)
                                showContext = true
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = "查看上下文范围")
                    }
                    if (generating) {
                        IconButton(onClick = { vm.stop() }) {
                            Icon(Icons.Filled.Stop, contentDescription = "停止生成")
                        }
                    } else {
                        IconButton(
                            onClick = { vm.send(draft) },
                            enabled = networkEnabled && session != null &&
                                (draft.isNotBlank() || pending.isNotEmpty()),
                        ) {
                            Icon(Icons.Filled.Send, contentDescription = "发送")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (showSessions) {
            AiSessionListOverlay(
                sessions = sessions,
                currentId = session?.id,
                onSelect = { id -> vm.selectSession(id); showSessions = false },
                onNew = { vm.newSession(); showSessions = false },
                onDelete = { vm.deleteSession(it) },
                onDismiss = { showSessions = false },
            )
        }
        if (showRoles) {
            AiRoleLibraryOverlay(
                roles = roles,
                onSave = { vm.saveRole(it) },
                onDelete = { vm.deleteRole(it) },
                onDismiss = { showRoles = false },
            )
        }
        if (showProviders) {
            AiProviderConfigOverlay(
                providers = providers,
                vm = vm,
                onDismiss = { showProviders = false },
            )
        }
        if (showSettings) {
            AiSettingsScreen(vm = vm, onBack = { showSettings = false })
        }
        if (showContext) {
            AlertDialog(
                onDismissRequest = { showContext = false },
                title = { Text("本次发送范围") },
                text = {
                    Column {
                        if (contextLines.isEmpty()) {
                            Text("暂无可预览内容")
                        } else {
                            contextLines.forEach { line ->
                                Text("· $line", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showContext = false }) { Text("关闭") } },
            )
        }
        if (!firstUseSeen && !firstUseDismissed) {
            FirstUseDialog(
                onConfirm = {
                    firstUseDismissed = true
                    vm.dismissFirstUse()
                },
            )
        }
    }
}

@Composable
private fun MessageRow(
    message: AiMessage,
    showStreaming: Boolean,
    streamingText: String,
    vm: AiViewModel,
) {
    val isUser = message.role == "user"
    val container = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = container),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (isUser) "我" else "助手",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                val shown = if (showStreaming) streamingText else message.content
                Text(
                    shown.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                val attachmentCount = AiRepository.decodeIds(message.attachmentIdsJson).size
                if (attachmentCount > 0) {
                    Text(
                        "附件 ×$attachmentCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!showStreaming) {
                    Text(
                        statusLabel(message.status, message.errorKind),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.status == "failed") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (message.status == "failed") {
                        TextButton(onClick = { vm.retry(message.id) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重试")
                        }
                    }
                }
                if (isUser && !showStreaming) {
                    TextButton(onClick = { vm.toggleIncludeInContext(message.id) }) {
                        Text(
                            if (message.includeInContext) "纳入上下文" else "不纳入上下文",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FirstUseDialog(onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onConfirm,
        title = { Text("首次使用说明") },
        text = {
            Column {
                Text("· 接收方：你在「服务商配置」中填写的 HTTPS 接口地址，消息将发送到该服务商。")
                Spacer(Modifier.height(6.dp))
                Text("· 发送范围：角色系统提示词快照、你选择纳入的历史消息、当前输入，以及你主动添加的附件内容。")
                Spacer(Modifier.height(6.dp))
                Text("· 配对的安全笔记不会自动加入问答；需要时请手动复制文本。")
                Spacer(Modifier.height(6.dp))
                Text("· API Key 与自定义请求头使用设备密钥加密保存在本机；查看明文需先通过锁屏或生物识别验证。")
                Spacer(Modifier.height(6.dp))
                Text("· 一期仅支持 OpenAI 兼容协议，流式输出逐段显示，结束后才写入本地记录。")
                Spacer(Modifier.height(6.dp))
                Text("· 可在设置中随时关闭联网；关闭后本地功能仍可用。")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("我知道了") } },
    )
}
