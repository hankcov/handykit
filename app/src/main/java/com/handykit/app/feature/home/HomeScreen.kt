package com.handykit.app.feature.home

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.handykit.app.navigation.SubRoute
import com.handykit.app.navigation.TopDestination

@Composable
fun HomeScreen(
    onOpen: (TopDestination) -> Unit,
    onSub: (SubRoute) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("手边", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("随手可用的工具箱 · 数据仅存本机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(4.dp))

        SectionLabel("常用")
        listOf(
            TopDestination.MUSIC,
            TopDestination.NEWS,
            TopDestination.AI,
        ).forEach { dest ->
            ToolCard(
                title = dest.label,
                subtitle = dest.subtitle,
                icon = dest.icon,
                onClick = { onOpen(dest) },
            )
        }

        Spacer(Modifier.height(4.dp))
        SectionLabel("工具")
        ToolCard("油耗助手", "满箱法统计 · 多车 · CSV 导入导出", Icons.Filled.DirectionsCar, onClick = { onSub(SubRoute.FUEL) })
        ToolCard("体脂秤", "蓝牙测量 · 多成员 · 八项指标", Icons.Filled.MonitorWeight, onClick = { onSub(SubRoute.SCALE) })
        ToolCard("安全笔记", "独立加密 · 主口令 + 指纹解锁", Icons.Filled.Shield, onClick = { onSub(SubRoute.NOTE) })

        Spacer(Modifier.height(4.dp))
        SectionLabel("数据")
        ToolCard("备份与恢复", "分域加密备份 · 原子恢复", Icons.Filled.BakeryDining, onClick = { onSub(SubRoute.BACKUP) })
        ToolCard("导航排序", "调整首页与底部入口顺序", Icons.Filled.Reorder, onClick = { onSub(SubRoute.REORDER) })

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun ToolCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val TopDestination.label: String
    get() = when (this) {
        TopDestination.HOME -> "首页"
        TopDestination.MUSIC -> "音乐"
        TopDestination.NEWS -> "新闻"
        TopDestination.AI -> "AI"
    }

private val TopDestination.subtitle: String
    get() = when (this) {
        TopDestination.HOME -> ""
        TopDestination.MUSIC -> "本地音频 · B 站缓存 · 离线播放"
        TopDestination.NEWS -> "需求待定，暂未开放"
        TopDestination.AI -> "多会话 · 角色 · 图文问答"
    }
