package com.handykit.app.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.handykit.app.core.ui.ScreenTitle

@Composable
fun AiSettingsScreen(
    vm: AiViewModel,
    onBack: () -> Unit,
) {
    val networkEnabled by vm.networkEnabled.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        ScreenTitle(title = "AI 设置", subtitle = "联网与隐私边界", onBack = onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("允许联网", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "关闭后 AI 不可发送，本地功能（角色/会话/附件整理）仍可用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = networkEnabled,
                        onCheckedChange = { vm.setNetworkEnabled(it) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingsNote("配对的安全笔记不会自动加入问答；需要时请手动复制文本。")
            SettingsNote("API Key 与自定义请求头使用本机设备密钥加密保存，不写数据库、不写日志、不提供明文导出。")
            SettingsNote("查看明文前需通过本机锁屏或生物识别验证；若无验证方式，只能替换，不能显示。")
            SettingsNote("一期仅支持 OpenAI 兼容协议；Anthropic/Gemini 明确未实现，不会伪装成可用。")
            SettingsNote("附件限制：单文件 20MB、最多 6 个、PDF 最多 40 页、图像边长 4096。")
            SettingsNote("PDF 只能本地统计页数与渲染校验，无法提取文本；请勿假定模型已理解 PDF 正文。")
            SettingsNote("清除上下文只移动游标，历史消息仍完整保留在设备上。")
        }
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
