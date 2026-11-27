package com.handykit.app.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.handykit.app.R
import com.handykit.app.navigation.TopDestination

@Composable
fun HomeScreen(onOpen: (TopDestination) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.home_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)

        TopDestination.entries.filter { it != TopDestination.HOME }.forEach { dest ->
            OutlinedCard(onClick = { onOpen(dest) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(dest.labelRes), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.placeholder_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
