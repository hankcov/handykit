package com.handykit.app.navigation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.handykit.app.navOrderDataStore

private val ORDER_KEY = stringPreferencesKey("top_destination_order")

class NavOrderStore(context: Context) {
    private val app = context.applicationContext as android.app.Application

    val order: Flow<List<TopDestination>> = app.navOrderDataStore.data.map { prefs ->
        val raw = prefs[ORDER_KEY].orEmpty()
        if (raw.isBlank()) {
            TopDestination.DEFAULT_ORDER
        } else {
            val parsed = raw.split(",").mapNotNull { route ->
                TopDestination.entries.firstOrNull { it.route == route }
            }
            // 非法或残缺存储回退默认，不恢复半套顺序。
            if (parsed.size == TopDestination.entries.size && parsed.toSet().size == parsed.size) {
                parsed
            } else {
                TopDestination.DEFAULT_ORDER
            }
        }
    }

    suspend fun save(order: List<TopDestination>) {
        require(order.size == TopDestination.entries.size && order.toSet().size == order.size) {
            "导航顺序必须包含且仅包含全部顶层入口"
        }
        app.navOrderDataStore.edit { prefs ->
            prefs[ORDER_KEY] = order.joinToString(",") { it.route }
        }
    }
}
