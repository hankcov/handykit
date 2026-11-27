package com.handykit.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.handykit.app.feature.ai.AiScreen
import com.handykit.app.feature.home.HomeScreen
import com.handykit.app.feature.music.MusicScreen
import com.handykit.app.feature.news.NewsScreen
import com.handykit.app.navigation.TopDestination
import com.handykit.app.ui.theme.HandyKitTheme

@Composable
fun HandyKitApp() {
    HandyKitTheme {
        var current by rememberSaveable { mutableStateOf(TopDestination.HOME) }
        // 骨架阶段按默认顺序展示；排序持久化在 NavOrderStore，接入见任务 3。
        val order = remember { TopDestination.DEFAULT_ORDER }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    order.forEach { dest ->
                        NavigationBarItem(
                            selected = dest == current,
                            onClick = { current = dest },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (current) {
                    TopDestination.HOME -> HomeScreen(onOpen = { current = it })
                    TopDestination.MUSIC -> MusicScreen()
                    TopDestination.NEWS -> NewsScreen()
                    TopDestination.AI -> AiScreen()
                }
            }
        }
    }
}
