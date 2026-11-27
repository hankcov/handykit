package com.handykit.app.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import com.handykit.app.R

enum class TopDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.tab_home, Icons.Filled.Home),
    MUSIC("music", R.string.tab_music, Icons.Filled.MusicNote),
    NEWS("news", R.string.tab_news, Icons.Filled.Article),
    AI("ai", R.string.tab_ai, Icons.Filled.SmartToy),
    ;

    companion object {
        val DEFAULT_ORDER = entries.toList()

        fun fromRoute(route: String?): TopDestination =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}
