package com.handykit.app.feature.note

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handykit.app.core.ui.ConfirmDialog
import com.handykit.app.core.ui.EmptyHint
import com.handykit.app.core.ui.LabeledField
import com.handykit.app.core.ui.LoadingRow
import com.handykit.app.core.ui.ScreenTitle
import com.handykit.app.core.ui.SectionHeader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class NoteDraft(
    val id: String? = null,
    val title: String = "",
    val body: String = "",
    val category: String = "",
)

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    onBack: (() -> Unit)? = null,
    vm: NoteViewModel = viewModel(factory = NoteViewModelFactory(LocalContext.current)),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val notes by vm.notes.collectAsState()
    val query by vm.query.collectAsState()
    val password by vm.password.collectAsState()
    val confirm by vm.confirmPassword.collectAsState()
    val unlockPwd by vm.unlockPassword.collectAsState()
    val oldPwd by vm.oldPassword.collectAsState()
    val newPwd by vm.newPassword.collectAsState()
    val biometricEnabled by vm.biometricEnabled.collectAsState()
    val revealedBody by vm.revealedBody.collectAsState()

    var editor by remember { mutableStateOf<NoteDraft?>(null) }
    var detail by remember { mutableStateOf<Note?>(null) }
    var pendingDelete by remember { mutableStateOf<Note?>(null) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showBiometricManager by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var pendingBiometric by remember { mutableStateOf<(() -> Unit)?>(null) }
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }

    // N8：本页始终禁止截屏/录屏；离开页面立即移除标记并锁定。
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                vm.lockNow()
            }
        } else {
            onDispose { vm.lockNow() }
        }
    }

    // N5：切后台立即锁定。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) vm.lockNow()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // N5：熄屏立即锁定（系统广播）。
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) vm.lockNow()
            }
        }
        var registered = false
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        } catch (t: Throwable) {
            // 注册失败时仍有 ON_STOP 兜底
        }
        onDispose {
            if (registered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (t: Throwable) {
                    // 已解绑则忽略
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.message.collect { msg ->
            if (msg != null) {
                snackbar.showSnackbar(msg)
                vm.clearMessage()
            }
        }
    }

    LaunchedEffect(state) {
        if (state == VaultState.UNLOCKED) {
            categories = vm.loadCategories().sorted()
        }
    }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result: ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingBiometric?.invoke()
        } else {
            vm.notify("已取消凭据验证")
        }
    }

    // 生物识别优先用系统 BiometricPrompt（API 28+）；无生物硬件或旧系统降级到设备凭据。
    // 系统 BiometricManager 没有公开的 from(Context)（那是 androidx.biometric 库的 API），
    // 因此这里用 KeyguardManager 判断设备是否安全，并用 BiometricPrompt 的错误码决定降级。
    fun requestBiometric(onVerified: () -> Unit) {
        vm.touch()
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard == null || !keyguard.isDeviceSecure) {
            vm.notify("设备未设置锁屏凭据，无法使用此功能")
            return
        }
        fun fallbackToDeviceCredential() {
            val intent = keyguard.createConfirmDeviceCredentialIntent("安全笔记", "验证设备凭据以继续")
            if (intent != null) {
                pendingBiometric = onVerified
                credentialLauncher.launch(intent)
            } else {
                vm.notify("无法启动凭据验证")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt.Builder(context)
                .setTitle("安全笔记")
                .setSubtitle("验证生物识别以继续")
                .setNegativeButton("取消", executor) { _, _ -> vm.notify("已取消生物识别验证") }
                .build()
            prompt.authenticate(
                CancellationSignal(),
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ||
                            errorCode == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE
                        ) {
                            fallbackToDeviceCredential()
                        } else {
                            vm.notify("生物识别未通过：$errString")
                        }
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onVerified()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                    }
                },
            )
            return
        }
        fallbackToDeviceCredential()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val payload = vm.buildBackup()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(payload.toByteArray(Charsets.UTF_8))
                    }
                    vm.notify("加密备份已导出（不含明文）")
                } catch (t: Throwable) {
                    vm.notify("导出失败：${t.message ?: "未知错误"}")
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (t: Throwable) {
                    vm.notify("读取备份文件失败：${t.message ?: "未知错误"}")
                    null
                }
                if (text != null) pendingImport = text
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state == VaultState.UNLOCKED) {
                FloatingActionButton(onClick = {
                    vm.touch()
                    editor = NoteDraft()
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "新增笔记")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) { detectTapGestures { vm.touch() } },
        ) {
            ScreenTitle(
                title = "安全笔记",
                subtitle = "独立加密 · 主口令解锁",
                onBack = onBack,
                actions = {
                    if (state == VaultState.UNLOCKED) {
                        IconButton(onClick = { vm.lockNow() }) {
                            Icon(Icons.Filled.Lock, contentDescription = "立即锁定")
                        }
                        IconButton(onClick = { showChangePassword = true }) {
                            Icon(Icons.Filled.Password, contentDescription = "修改主口令")
                        }
                        IconButton(onClick = { showBiometricManager = true }) {
                            Icon(Icons.Filled.Fingerprint, contentDescription = "指纹解锁设置")
                        }
                        IconButton(onClick = {
                            exportLauncher.launch("note_backup_${System.currentTimeMillis()}.bin")
                        }) {
                            Icon(Icons.Filled.Download, contentDescription = "导出加密备份")
                        }
                        IconButton(onClick = { importLauncher.launch("*/*") }) {
                            Icon(Icons.Filled.Upload, contentDescription = "恢复加密备份")
                        }
                    }
                },
            )

            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    "安全策略：30 秒剪贴板清理 · 切后台立即锁定 · 5 分钟闲置锁定",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            when (state) {
                VaultState.LOADING -> LoadingRow("正在检查保险库")
                VaultState.SETUP -> SetupSection(
                    password = password,
                    confirm = confirm,
                    busy = busy,
                    onPassword = vm::setPassword,
                    onConfirm = vm::setConfirmPassword,
                    onSubmit = vm::setup,
                )

                VaultState.LOCKED -> UnlockSection(
                    unlockPwd = unlockPwd,
                    busy = busy,
                    biometricEnabled = biometricEnabled,
                    onPassword = vm::setUnlockPassword,
                    onUnlock = vm::unlock,
                    onBiometric = { requestBiometric { vm.unlockBiometric() } },
                )

                VaultState.UNLOCKED -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.setQuery(it) },
                        label = { Text("搜索标题 / 正文 / 分类（内存中匹配）") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    SectionHeader(
                        text = "笔记",
                        trailing = { Text("${notes.size} 条", style = MaterialTheme.typography.labelSmall) },
                    )
                    if (notes.isEmpty()) {
                        EmptyHint(
                            if (query.isBlank()) "还没有笔记，点右下角新增" else "没有匹配的笔记",
                        )
                    } else {
                        notes.forEach { note ->
                            NoteCard(
                                note = note,
                                onClick = {
                                    vm.touch()
                                    vm.hideBody()
                                    detail = note
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }

    editor?.let { draft ->
        NoteEditorSheet(
            draft = draft,
            categories = categories,
            onDraft = { editor = it },
            onDismiss = { editor = null },
            onSave = {
                vm.saveNote(draft.id, draft.title, draft.body, draft.category)
                editor = null
            },
        )
    }

    detail?.let { note ->
        AlertDialog(
            onDismissRequest = {
                vm.hideBody()
                detail = null
            },
            title = { Text(if (note.decryptError) "数据异常" else note.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "分类：${note.category}　更新：${formatTime(note.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (note.decryptError) {
                        Text(
                            "该条笔记认证失败，已跳过显示，避免展示乱码。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (revealedBody == null) {
                        Text(
                            "正文已隐藏。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { vm.revealBody(note.id) }) {
                            Text("显示正文")
                        }
                    } else {
                        Text(revealedBody!!, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { vm.copyBody(context, revealedBody!!) }) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("复制（30 秒清理）")
                            }
                            TextButton(onClick = { vm.hideBody() }) { Text("重新隐藏") }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        editor = NoteDraft(
                            id = note.id,
                            title = note.title,
                            body = note.body,
                            category = note.category,
                        )
                        vm.hideBody()
                        detail = null
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("编辑")
                    }
                    TextButton(onClick = {
                        pendingDelete = note
                        detail = null
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.hideBody()
                    detail = null
                }) { Text("关闭") }
            },
        )
    }

    pendingDelete?.let { note ->
        ConfirmDialog(
            title = "删除这条笔记？",
            message = "删除后无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteNote(note.id); pendingDelete = null },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showChangePassword) {
        AlertDialog(
            onDismissRequest = { showChangePassword = false },
            title = { Text("修改主口令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledField(
                        "原口令",
                        oldPwd,
                        vm::setOldPassword,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    LabeledField(
                        "新口令（至少 6 位）",
                        newPwd,
                        vm::setNewPassword,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        "只有原口令验证通过才会重新封装数据密钥；失败不会改动任何笔记。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.changePassword(); showChangePassword = false }) {
                    Text("确认修改")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePassword = false }) { Text("取消") }
            },
        )
    }

    if (showBiometricManager) {
        AlertDialog(
            onDismissRequest = { showBiometricManager = false },
            title = { Text("指纹解锁") },
            text = {
                Text(
                    if (biometricEnabled) {
                        "已启用。关闭后仍可用主口令解锁，已保存的笔记不受影响。"
                    } else {
                        "启用时需先验证指纹或设备凭据；验证通过后才会用带用户认证约束的密钥封装数据密钥。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (biometricEnabled) {
                    TextButton(onClick = {
                        vm.disableBiometric()
                        showBiometricManager = false
                    }) { Text("关闭指纹解锁") }
                } else {
                    TextButton(onClick = {
                        showBiometricManager = false
                        requestBiometric { vm.enableBiometric() }
                    }) { Text("开始启用") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBiometricManager = false }) { Text("取消") }
            },
        )
    }

    pendingImport?.let { payload ->
        ConfirmDialog(
            title = "恢复加密备份？",
            message = "当前笔记会被备份内容覆盖，恢复后需用备份对应的主口令重新解锁。",
            confirmText = "恢复",
            danger = true,
            onConfirm = { vm.importBackup(payload); pendingImport = null },
            onDismiss = { pendingImport = null },
        )
    }
}

@Composable
private fun SetupSection(
    password: String,
    confirm: String,
    busy: Boolean,
    onPassword: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("首次使用，请设置主口令", style = MaterialTheme.typography.titleMedium)
        Text(
            "主口令派生加密密钥，丢失后无法恢复笔记。至少 6 位。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("主口令") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = onConfirm,
            label = { Text("再次输入主口令") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (password.isNotEmpty() && password.length < 6) {
            Text(
                "口令至少 6 位（当前 ${password.length} 位）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (confirm.isNotEmpty() && confirm != password) {
            Text(
                "两次输入的口令不一致",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = onSubmit,
            enabled = password.length >= 6 && password == confirm && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("设置并解锁")
        }
    }
}

@Composable
private fun UnlockSection(
    unlockPwd: String,
    busy: Boolean,
    biometricEnabled: Boolean,
    onPassword: (String) -> Unit,
    onUnlock: () -> Unit,
    onBiometric: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("保险库已锁定", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = unlockPwd,
            onValueChange = onPassword,
            label = { Text("主口令") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onUnlock,
            enabled = unlockPwd.isNotBlank() && !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("解锁")
        }
        if (biometricEnabled) {
            OutlinedButton(onClick = onBiometric, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("指纹解锁")
            }
        }
        Text(
            "忘记主口令无法恢复；备份需在同一个设备上用备份密钥恢复。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (note.decryptError) "数据异常（已跳过解密）" else note.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (note.decryptError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (note.decryptError) {
                    "该条记录无法解密，未展示任何内容"
                } else {
                    "${note.category} · ${formatTime(note.updatedAt)}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteEditorSheet(
    draft: NoteDraft,
    categories: List<String>,
    onDraft: (NoteDraft) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (draft.id == null) "新增笔记" else "编辑笔记",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onDraft(draft.copy(title = it)) },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.body,
                onValueChange = { onDraft(draft.copy(body = it)) },
                label = { Text("正文") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = draft.category,
                    onValueChange = {
                        onDraft(draft.copy(category = it))
                        expanded = true
                    },
                    label = { Text("分类（可选，可输入新分类）") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                val options = categories.filter { it.contains(draft.category.trim(), ignoreCase = true) }
                ExposedDropdownMenu(
                    expanded = expanded && options.isNotEmpty(),
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onDraft(draft.copy(category = option))
                                expanded = false
                            },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = draft.title.isNotBlank()) { Text("保存") }
            }
        }
    }
}
