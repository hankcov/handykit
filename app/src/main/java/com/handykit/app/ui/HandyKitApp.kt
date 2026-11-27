package com.handykit.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.handykit.app.core.crypto.VaultSessionHolder
import com.handykit.app.feature.ai.AiScreen
import com.handykit.app.feature.backup.BackupScreen
import com.handykit.app.feature.fuel.FuelScreen
import com.handykit.app.feature.home.HomeScreen
import com.handykit.app.feature.home.ReorderScreen
import com.handykit.app.feature.music.MusicScreen
import com.handykit.app.feature.news.NewsScreen
import com.handykit.app.feature.note.NoteScreen
import com.handykit.app.feature.scale.ScaleScreen
import com.handykit.app.navigation.NavOrderStore
import com.handykit.app.navigation.SubRoute
import com.handykit.app.navigation.TopDestination
import com.handykit.app.ui.theme.HandyKitTheme

@Composable
fun HandyKitApp() {
    HandyKitTheme {
        val context = LocalContext.current
        val store = remember { NavOrderStore(context) }
        val order by store.order.collectAsStateWithLifecycle(initialValue = TopDestination.DEFAULT_ORDER)

        var current by rememberSaveable { mutableStateOf(TopDestination.HOME) }
        var subRoute by rememberSaveable { mutableStateOf<SubRoute?>(null) }
        val snackbar = remember { SnackbarHostState() }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    order.forEach { dest ->
                        NavigationBarItem(
                            selected = dest == current && subRoute == null,
                            onClick = {
                                subRoute = null
                                current = dest
                            },
                            modifier = Modifier.pointerInput(dest) {
                                detectTapGestures(
                                    onLongPress = { subRoute = SubRoute.REORDER },
                                )
                            },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                val activeSub = subRoute
                if (activeSub != null) {
                    when (activeSub) {
                        SubRoute.FUEL -> FuelScreen(onBack = { subRoute = null })
                        SubRoute.SCALE -> ScaleScreen(onBack = { subRoute = null })
                        SubRoute.NOTE -> NoteScreen(onBack = { subRoute = null })
                        SubRoute.BACKUP -> BackupScreen(
                            vaultSessionProvider = { VaultSessionHolder.current() },
                            onBack = { subRoute = null },
                        )
                        SubRoute.REORDER -> ReorderScreen(store = store, onBack = { subRoute = null })
                    }
                } else {
                    when (current) {
                        TopDestination.HOME -> HomeScreen(
                            onOpen = { current = it },
                            onSub = { subRoute = it },
                        )
                        TopDestination.MUSIC -> MusicScreen()
                        TopDestination.NEWS -> NewsScreen()
                        TopDestination.AI -> AiScreen()
                    }
                }
            }
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
