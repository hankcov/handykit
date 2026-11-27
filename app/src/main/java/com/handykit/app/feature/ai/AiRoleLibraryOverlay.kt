package com.handykit.app.feature.ai

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.handykit.app.core.db.AiRole
import com.handykit.app.core.ui.ConfirmDialog
import com.handykit.app.core.ui.EmptyHint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID

private data class RoleDraft(
    val id: String,
    val name: String,
    val prompt: String,
    val params: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiRoleLibraryOverlay(
    roles: List<AiRole>,
    onSave: (AiRole) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf<RoleDraft?>(null) }
    var original by remember { mutableStateOf<AiRole?>(null) }
    var pendingDelete by remember { mutableStateOf<AiRole?>(null) }
    var jsonError by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("角色库", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    original = null
                    draft = RoleDraft("", "", "", "{}")
                    jsonError = null
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("新建")
                }
            }
            Text(
                "角色在系统提示词快照中固定到会话；修改角色不影响已创建的会话。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (roles.isEmpty()) {
                EmptyHint("还没有角色，可新建后再发起会话。")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(roles, key = { it.id }) { role ->
                        ListItem(
                            headlineContent = { Text(role.name) },
                            supportingContent = {
                                Text(
                                    role.systemPrompt.take(80).replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = {
                                        original = role
                                        draft = RoleDraft(
                                            role.id,
                                            role.name,
                                            role.systemPrompt,
                                            role.paramsJson,
                                        )
                                        jsonError = null
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "编辑角色")
                                    }
                                    IconButton(onClick = { pendingDelete = role }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除角色")
                                    }
                                }
                            },
                            modifier = Modifier.clickable {
                                original = role
                                draft = RoleDraft(role.id, role.name, role.systemPrompt, role.paramsJson)
                                jsonError = null
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    draft?.let { editing ->
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text(if (editing.id.isBlank()) "新建角色" else "编辑角色") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = editing.name,
                        onValueChange = { draft = editing.copy(name = it) },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editing.prompt,
                        onValueChange = { draft = editing.copy(prompt = it) },
                        label = { Text("系统提示词") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editing.params,
                        onValueChange = { draft = editing.copy(params = it) },
                        label = { Text("参数 JSON（如 temperature）") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (jsonError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            jsonError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val params = editing.params.ifBlank { "{}" }
                    if (runCatching { Json.parseToJsonElement(params) }.getOrNull() !is JsonObject) {
                        jsonError = "参数必须是合法 JSON 对象"
                        return@TextButton
                    }
                    if (editing.name.isBlank() || editing.prompt.isBlank()) {
                        jsonError = "名称与系统提示词不能为空"
                        return@TextButton
                    }
                    onSave(
                        AiRole(
                            id = editing.id.ifBlank { UUID.randomUUID().toString() },
                            name = editing.name.trim(),
                            systemPrompt = editing.prompt,
                            paramsJson = params,
                            createdAt = original?.createdAt ?: System.currentTimeMillis(),
                        ),
                    )
                    draft = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("取消") } },
        )
    }

    pendingDelete?.let { target ->
        ConfirmDialog(
            title = "删除角色",
            message = "删除「${target.name}」？已有会话仍保留创建时的角色快照。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                onDelete(target.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
