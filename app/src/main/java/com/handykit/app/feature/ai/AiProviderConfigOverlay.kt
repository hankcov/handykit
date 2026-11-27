package com.handykit.app.feature.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.handykit.app.core.db.AiProvider
import com.handykit.app.core.ui.ConfirmDialog
import com.handykit.app.core.ui.EmptyHint
import kotlinx.coroutines.launch
import java.util.UUID

private data class ProviderDraft(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val image: Boolean,
    val pdf: Boolean,
    val keyText: String,
    val headers: List<AiHeaderEntry>,
    val keyExists: Boolean,
    val unlocked: Boolean,
    val advanced: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderConfigOverlay(
    providers: List<AiProvider>,
    vm: AiViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ProviderDraft?>(null) }
    var original by remember { mutableStateOf<AiProvider?>(null) }
    var pendingDelete by remember { mutableStateOf<AiProvider?>(null) }
    var headerKey by rememberSaveable { mutableStateOf("") }
    var headerValue by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                draft?.let { d ->
                    draft = d.copy(
                        unlocked = true,
                        keyText = vm.revealApiKey(d.id),
                        headers = vm.revealHeaders(d.id),
                    )
                }
            }
        } else {
            Toast.makeText(context, "未通过验证，凭据未显示", Toast.LENGTH_SHORT).show()
        }
    }

    fun openEditor(provider: AiProvider?) {
        original = provider
        headerKey = ""
        headerValue = ""
        passwordVisible = false
        urlError = null
        if (provider == null) {
            draft = ProviderDraft(
                id = UUID.randomUUID().toString(),
                name = "",
                baseUrl = "https://",
                model = "",
                image = false,
                pdf = false,
                keyText = "",
                headers = emptyList(),
                keyExists = false,
                unlocked = true,
                advanced = false,
            )
        } else {
            scope.launch {
                draft = ProviderDraft(
                    id = provider.id,
                    name = provider.name,
                    baseUrl = provider.baseUrl,
                    model = provider.model,
                    image = provider.imageCapability != "unknown",
                    pdf = provider.pdfCapability != "unknown",
                    keyText = "",
                    headers = emptyList(),
                    keyExists = vm.hasApiKey(provider.id),
                    unlocked = false,
                    advanced = false,
                )
            }
        }
    }

    fun requestReveal() {
        val target = draft ?: return
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, "当前环境无法发起身份验证", Toast.LENGTH_SHORT).show()
            return
        }
        when (val gate = vm.revealGate(activity)) {
            is RevealGate.NoLock -> Toast.makeText(
                context,
                "本机未设置锁屏或生物识别：只能替换，不能查看明文",
                Toast.LENGTH_LONG,
            ).show()
            is RevealGate.NeedAuth -> authLauncher.launch(gate.intent)
        }
    }

    fun commit() {
        val d = draft ?: return
        runCatching { requireHttpsBaseUrl(d.baseUrl) }.exceptionOrNull()?.let { ex ->
            urlError = ex.message ?: "Base URL 非法"
            return
        }
        if (d.name.isBlank() || d.model.isBlank()) {
            urlError = "名称与模型不能为空"
            return
        }
        vm.saveProvider(d.id, d.name, d.baseUrl, d.model, d.image, d.pdf)
        scope.launch {
            if (d.keyText.isNotBlank()) vm.saveApiKey(d.id, d.keyText)
            // 未解锁时不覆盖已保存的隐藏请求头，避免误清空。
            if (d.unlocked || !d.keyExists) vm.saveHeaders(d.id, d.headers)
        }
        draft = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("服务商配置", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { openEditor(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
            Text(
                "一期只支持 OpenAI 兼容协议（含 DeepSeek/Kimi/自建兼容端点）。Anthropic/Gemini 未实现。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (providers.isEmpty()) {
                EmptyHint("还没有服务商，新建后才能发送。")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(providers, key = { it.id }) { provider ->
                        val capabilities = listOfNotNull(
                            if (provider.imageCapability != "unknown") "图片" else null,
                            if (provider.pdfCapability != "unknown") "PDF" else null,
                        ).joinToString("/")
                        ListItem(
                            headlineContent = { Text(provider.name) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        "OpenAI 兼容",
                                        provider.baseUrl,
                                        provider.model,
                                        capabilities.ifBlank { null },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { openEditor(provider) }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑服务商")
                                    }
                                    IconButton(onClick = { pendingDelete = provider }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除服务商")
                                    }
                                }
                            },
                            modifier = Modifier.clickable { openEditor(provider) },
                        )
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "联网开关在「设置」中；关闭后本地功能仍可用，但 AI 无法发送。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    draft?.let { d ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text(if (original == null) "新建服务商" else "编辑服务商") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = d.name,
                        onValueChange = { draft = d.copy(name = it) },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = d.baseUrl,
                        onValueChange = { draft = d.copy(baseUrl = it) },
                        label = { Text("Base URL（必须 https://）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "示例：https://api.example.com/v1（自动拼接 /chat/completions）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = d.model,
                        onValueChange = { draft = d.copy(model = it) },
                        label = { Text("模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("协议：OpenAI 兼容（一期唯一实现）", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = d.image,
                            onCheckedChange = { draft = d.copy(image = it) },
                        )
                        Text("声明支持图片输入", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = d.pdf,
                            onCheckedChange = { draft = d.copy(pdf = it) },
                        )
                        Text("声明支持 PDF 输入", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "能力标记仅为用户声明，不代表已验证；本地无法提取 PDF 文本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))

                    if (d.keyExists && !d.unlocked) {
                        Text("API Key 已保存（已隐藏）", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { requestReveal() }) {
                            Text("验证本机身份后查看/编辑")
                        }
                    } else {
                        OutlinedTextField(
                            value = d.keyText,
                            onValueChange = { draft = d.copy(keyText = it) },
                            label = { Text("API Key") },
                            singleLine = true,
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (passwordVisible) "隐藏" else "显示",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { draft = d.copy(advanced = !d.advanced) }) {
                        Text(if (d.advanced) "收起高级选项" else "展开高级选项（自定义请求头）")
                    }
                    if (d.advanced) {
                        Text(
                            "自定义请求头会加密保存；Authorization/Content-Type/Host 等保留名被禁止。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        d.headers.forEachIndexed { index, entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = entry.key,
                                    onValueChange = { value ->
                                        draft = d.copy(
                                            headers = d.headers.toMutableList().also {
                                                it[index] = entry.copy(key = value)
                                            },
                                        )
                                    },
                                    label = { Text("头名") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = entry.value,
                                    onValueChange = { value ->
                                        draft = d.copy(
                                            headers = d.headers.toMutableList().also {
                                                it[index] = entry.copy(value = value)
                                            },
                                        )
                                    },
                                    label = { Text("值") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1.4f),
                                )
                                IconButton(onClick = {
                                    draft = d.copy(headers = d.headers.filterIndexed { i, _ -> i != index })
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除请求头")
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = headerKey,
                                onValueChange = { headerKey = it },
                                label = { Text("新头名") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            OutlinedTextField(
                                value = headerValue,
                                onValueChange = { headerValue = it },
                                label = { Text("新值") },
                                singleLine = true,
                                modifier = Modifier.weight(1.4f),
                            )
                            IconButton(onClick = {
                                if (headerKey.isBlank()) return@IconButton
                                if (isReservedHeaderName(headerKey)) {
                                    Toast.makeText(context, "该头名为保留名，不可添加", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                draft = d.copy(
                                    headers = d.headers + AiHeaderEntry(headerKey.trim(), headerValue),
                                )
                                headerKey = ""
                                headerValue = ""
                            }) {
                                Icon(Icons.Filled.Add, contentDescription = "添加请求头")
                            }
                        }
                    }
                    if (urlError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            urlError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { commit() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("取消") } },
        )
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除服务商",
            message = "删除「${target.name}」及其本机加密凭据？使用该服务商的会话需重新配置。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteProvider(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
