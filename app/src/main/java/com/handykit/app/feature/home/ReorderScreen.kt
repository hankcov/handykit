package com.handykit.app.feature.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handykit.app.R
import com.handykit.app.core.ui.ScreenTitle
import com.handykit.app.navigation.NavOrderStore
import com.handykit.app.navigation.TopDestination
import kotlinx.coroutines.launch

/** 导航排序。用上下移动按钮，稳定可靠；顺序持久化到 DataStore，重启不回退默认。 */
@Composable
fun ReorderScreen(
    store: NavOrderStore,
    onBack: () -> Unit,
) {
    val order by store.order.collectAsStateWithLifecycle(initialValue = TopDestination.DEFAULT_ORDER)
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title = "导航排序", subtitle = "调整底部四个入口的顺序", onBack = onBack)
        Text(
            "首页卡片顺序与此处一致，修改即时保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            order.forEachIndexed { index, dest ->
                Card(
                    Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(dest.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(dest.labelRes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                if (index > 0) {
                                    val swapped = order.toMutableList()
                                    val tmp = swapped[index - 1]
                                    swapped[index - 1] = swapped[index]
                                    swapped[index] = tmp
                                    scope.launch { store.save(swapped) }
                                }
                            },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "上移")
                        }
                        IconButton(
                            onClick = {
                                if (index < order.lastIndex) {
                                    val swapped = order.toMutableList()
                                    val tmp = swapped[index]
                                    swapped[index] = swapped[index + 1]
                                    swapped[index + 1] = tmp
                                    scope.launch { store.save(swapped) }
                                }
                            },
                            enabled = index < order.lastIndex,
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "下移")
                        }
                    }
                }
            }
        }
    }
}
