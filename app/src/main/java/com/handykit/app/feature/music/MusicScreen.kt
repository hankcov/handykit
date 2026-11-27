package com.handykit.app.feature.music

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.handykit.app.core.db.MusicTrack
import com.handykit.app.core.ui.ConfirmDialog
import com.handykit.app.core.ui.EmptyHint
import com.handykit.app.core.ui.InfoCard
import com.handykit.app.core.ui.LoadingRow
import com.handykit.app.core.ui.ScreenTitle
import com.handykit.app.core.ui.SectionHeader
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val factory = remember { MusicViewModelFactory(context.applicationContext) }
    val vm: MusicViewModel = viewModel(factory = factory)
    val ui by vm.uiState.collectAsStateWithLifecycle()

    var linkDialog by rememberSaveable { mutableStateOf(false) }
    var linkText by rememberSaveable { mutableStateOf("") }
    var moreExpanded by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.importLocal(uris)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            if (effect is MusicEffect.Toast) Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
        }
    }

    when {
        ui.resolveState != null -> ResolvePage(ui = ui, vm = vm)
        ui.lyricOpen -> LyricPage(ui = ui, vm = vm)
        ui.playlistsOpen -> PlaylistPage(ui = ui, vm = vm)
        else -> {
            Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ScreenTitle(
                    title = "音乐",
                    subtitle = "本地音频 · B 站低清含音频流",
                    actions = {
                        IconButton(onClick = { moreExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("添加本地音频") },
                                onClick = { moreExpanded = false; audioPicker.launch("*/*") },
                            )
                            DropdownMenuItem(
                                text = { Text("添加 B 站链接") },
                                onClick = { moreExpanded = false; linkDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("歌单管理") },
                                onClick = { moreExpanded = false; vm.togglePlaylistPanel(true) },
                            )
                            DropdownMenuItem(
                                text = { Text("清理全部缓存") },
                                onClick = { moreExpanded = false; confirmClearAll = true },
                            )
                        }
                    },
                )

                NowPlayingCard(ui = ui, vm = vm)

                SectionHeader(
                    text = "曲库（${ui.tracks.size}）",
                    trailing = {
                        Text(
                            "可播放 ${ui.tracks.count { it.status == MusicRepository.STATUS_READY }} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                if (ui.tracks.isEmpty()) {
                    EmptyHint("曲库为空。用右上角「添加本地音频」或「添加 B 站链接」开始。")
                } else {
                    ui.tracks.forEach { track ->
                        TrackRow(
                            track = track,
                            task = ui.tasks[track.id],
                            onPlay = { vm.play(track.id) },
                            onCache = { vm.retryCache(track.id) },
                            onCancel = { vm.cancelCache(track.id) },
                            onClearCache = { vm.clearTrackCache(track.id) },
                            onRemove = { pendingRemove = track.id },
                            onLyrics = { vm.openLyrics(track.id) },
                            onAddToPlaylist = {
                                if (ui.playlists.isEmpty()) {
                                    vm.togglePlaylistPanel(true)
                                } else {
                                    vm.addCurrentToPlaylist(ui.playlists.first().id)
                                }
                            },
                            onRetry = { vm.retryCache(track.id) },
                        )
                    }
                }

                SectionHeader(text = "下载与缓存")
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("仅在 Wi-Fi 下缓存", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "开启后无法确认 Wi-Fi/以太网时不下载",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = ui.wifiOnly, onCheckedChange = vm::setWifiOnly)
                    }
                    Spacer(Modifier.height(8.dp))
                    InfoCard(
                        title = "音频缓存占用",
                        value = "${ui.cacheStats.bytes / (1024 * 1024)} MB",
                        note = "${ui.cacheStats.fileCount} 个文件；备份默认不包含音频缓存",
                    )
                    TextButton(onClick = { confirmClearAll = true }) { Text("清理全部缓存") }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (linkDialog) {
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("添加 B 站链接") },
            text = {
                Column {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        label = { Text("视频链接或 BV 号") },
                        placeholder = { Text("https://www.bilibili.com/video/BV... 或 b23.tv/...") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "不会自动读取剪贴板；需手动粘贴。付费/登录/地区限制会明确失败，不会伪装成功。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val input = linkText.trim()
                    if (input.isNotBlank()) vm.resolveLink(input)
                    linkDialog = false
                }) { Text("解析") }
            },
            dismissButton = { TextButton(onClick = { linkDialog = false }) { Text("取消") } },
        )
    }

    if (confirmClearAll) {
        ConfirmDialog(
            title = "清理全部音频缓存",
            message = "将删除所有已下载音频并把曲目标记为「需重新缓存」。曲目元数据与歌单保留。",
            confirmText = "清理",
            danger = true,
            onConfirm = { confirmClearAll = false; vm.clearAllCache() },
            onDismiss = { confirmClearAll = false },
        )
    }

    pendingRemove?.let { id ->
        ConfirmDialog(
            title = "移除曲目",
            message = "将删除该曲目的音频缓存、歌词与歌单关联，并移除曲目记录。",
            confirmText = "移除",
            danger = true,
            onConfirm = { vm.removeTrack(id); pendingRemove = null },
            onDismiss = { pendingRemove = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingCard(ui: MusicViewModel.UiState, vm: MusicViewModel) {
    val track = ui.tracks.firstOrNull { it.id == ui.playback.trackId }
    val duration = ui.playback.durationMs
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track?.coverPath?.isNotBlank() == true && File(track.coverPath).exists()) {
                    AsyncImage(
                        model = File(track.coverPath),
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track?.title ?: "未在播放", style = MaterialTheme.typography.titleMedium)
                    Text(
                        track?.author?.takeIf { it.isNotBlank() } ?: "本地 / B 站音频",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (track != null) {
                    IconButton(onClick = { vm.openLyrics(track.id) }) {
                        Icon(Icons.Filled.LibraryMusic, contentDescription = "歌词")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Slider(
                value = if (duration > 0) (ui.playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction -> vm.seekTo((fraction * duration).toLong()) },
                enabled = duration > 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(LyricCodec.formatTime(ui.playback.positionMs), style = MaterialTheme.typography.bodySmall)
                Text(LyricCodec.formatTime(duration), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::toggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "随机播放",
                        tint = if (ui.playback.shuffle) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = vm::previous) { Icon(Icons.Filled.SkipPrevious, contentDescription = "上一曲") }
                IconButton(onClick = vm::playPause) {
                    Icon(
                        if (ui.playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (ui.playback.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(onClick = vm::next) { Icon(Icons.Filled.SkipNext, contentDescription = "下一曲") }
                IconButton(onClick = vm::toggleRepeat) {
                    Icon(
                        Icons.Filled.Repeat,
                        contentDescription = "循环模式",
                        tint = if (ui.playback.repeatMode != androidx.media3.common.Player.REPEAT_MODE_OFF)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                when (ui.playback.repeatMode) {
                    androidx.media3.common.Player.REPEAT_MODE_ONE -> "当前：单曲循环"
                    androidx.media3.common.Player.REPEAT_MODE_ALL -> "当前：列表循环"
                    else -> "当前：顺序播放"
                } + if (ui.playback.shuffle) " · 随机已开" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun TrackRow(
    track: MusicTrack,
    task: CacheTaskState?,
    onPlay: () -> Unit,
    onCache: () -> Unit,
    onCancel: () -> Unit,
    onClearCache: () -> Unit,
    onRemove: () -> Unit,
    onLyrics: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRetry: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val busy = task?.status in setOf(
        MusicRepository.STATUS_QUEUED,
        MusicRepository.STATUS_PARSING,
        MusicRepository.STATUS_DOWNLOADING,
        MusicRepository.STATUS_VERIFYING,
    )
    Row(
        Modifier.fillMaxWidth().clickable(enabled = track.status == MusicRepository.STATUS_READY, onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                buildString {
                    if (track.author.isNotBlank()) append(track.author).append(" · ")
                    append(if (track.source == "bili") "B 站" else "本地")
                    append(" · ").append(MusicRepository.statusLabel(track.status))
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (track.status == MusicRepository.STATUS_FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (busy) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${task?.message.orEmpty()} ${((task?.progress ?: 0f) * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (task?.message?.isNotBlank() == true &&
                track.status in setOf(MusicRepository.STATUS_FAILED, MusicRepository.STATUS_MISSING, MusicRepository.STATUS_CANCEL)
            ) {
                Text(task.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (track.status != MusicRepository.STATUS_READY) {
            if (busy) {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Delete, contentDescription = "取消缓存") }
            } else {
                IconButton(onClick = if (track.status == MusicRepository.STATUS_FAILED) onRetry else onCache) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = "重新缓存")
                }
            }
        }
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "曲目操作") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("播放") }, onClick = { expanded = false; onPlay() })
            DropdownMenuItem(text = { Text("歌词") }, onClick = { expanded = false; onLyrics() })
            DropdownMenuItem(text = { Text("加入歌单") }, onClick = { expanded = false; onAddToPlaylist() })
            DropdownMenuItem(text = { Text("仅清缓存") }, onClick = { expanded = false; onClearCache() })
            DropdownMenuItem(text = { Text("移除曲目") }, onClick = { expanded = false; onRemove() })
        }
    }
}

@Composable
private fun ResolvePage(ui: MusicViewModel.UiState, vm: MusicViewModel) {
    val result = ui.resolveState ?: return
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ScreenTitle(title = "B 站解析", subtitle = "公开接口 · 不伪造音频", onBack = vm::closeResolve)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (ui.resolving) LoadingRow("正在请求 B 站公开接口…")
            result.info?.let { info ->
                if (info.coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = info.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                InfoCard(title = "标题", value = info.title, note = "作者：${info.author.ifBlank { "接口未返回" }}")
                Spacer(Modifier.height(8.dp))
            }
            if (result is ResolveResult.Success && !result.audioUrl.isNullOrBlank()) {
                InfoCard(
                    title = "音频直链",
                    value = "已获取",
                    note = BiliResolver.AUDIO_NOTE,
                )
            } else if (result.reason.isNotBlank()) {
                InfoCard(title = "失败原因", value = result.reason, note = "未加入缓存，未伪装成功")
            }
            Spacer(Modifier.height(12.dp))
            SectionHeader(text = "分 P 选择")
            if (result.pages.isEmpty()) {
                EmptyHint("没有可选分 P")
            } else {
                result.pages.forEach { page ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.selectPage(page.page) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = ui.selectedPage == page.page, onClick = { vm.selectPage(page.page) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "P${page.page} ${page.part}".trim(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text("cid ${page.cid}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = vm::cacheSelectedPage,
                enabled = result is ResolveResult.Success && !result.audioUrl.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("缓存所选分 P（低清原始流）")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LyricPage(ui: MusicViewModel.UiState, vm: MusicViewModel) {
    val context = LocalContext.current
    val lyricPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val trackId = ui.lyricTrackId
            if (bytes != null && trackId != null) vm.loadLyricBytes(trackId, bytes)
        }
    }
    val activeIndex = LyricCodec.activeIndex(ui.lyricLines, ui.playback.positionMs)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ScreenTitle(
            title = "歌词",
            subtitle = "当前格式：${ui.lyricFormat.uppercase()} · 点击时间戳跳转",
            onBack = vm::closeLyrics,
            actions = {
                TextButton(onClick = { lyricPicker.launch("text/plain") }) { Text("导入") }
                TextButton(onClick = vm::saveLyric) { Text("保存") }
            },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (ui.lyricLines.isEmpty()) {
                EmptyHint("暂无歌词。可导入 LRC/TXT 文件，或在下方粘贴后保存。")
            } else {
                ui.lyricLines.forEachIndexed { index, line ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.seekToLyric(line.timeMs) }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            line.timeMs?.let { LyricCodec.formatTime(it) } ?: "--:--",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (index == activeIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(52.dp),
                        )
                        Text(
                            line.text,
                            style = if (index == activeIndex) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.bodyMedium,
                            color = if (index == activeIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = ui.lyricText,
                onValueChange = vm::onLyricTextChange,
                label = { Text("LRC / TXT 内容") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
            )
            if (ui.lyricError.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(ui.lyricError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlaylistPage(ui: MusicViewModel.UiState, vm: MusicViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ScreenTitle(title = "歌单", subtitle = "新建 / 重命名 / 管理曲目", onBack = { vm.togglePlaylistPanel(false) })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("新歌单名称") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.createPlaylist(name); name = "" }) { Text("新建") }
            }
            Spacer(Modifier.height(12.dp))
            if (ui.playlists.isEmpty()) {
                EmptyHint("还没有歌单")
            } else {
                ui.playlists.forEach { playlist ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (ui.activePlaylistId == playlist.id) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).clickable { vm.selectPlaylist(playlist.id) },
                        )
                        IconButton(onClick = { renaming = playlist.id; renameText = playlist.name }) {
                            Icon(Icons.Filled.Edit, contentDescription = "重命名")
                        }
                        IconButton(onClick = { vm.deletePlaylist(playlist.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除歌单")
                        }
                    }
                    if (renaming == playlist.id) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = renameText,
                                onValueChange = { renameText = it },
                                label = { Text("新名称") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            TextButton(onClick = {
                                vm.renamePlaylist(playlist.id, renameText)
                                renaming = null
                            }) { Text("保存") }
                        }
                    }
                }
            }
            val activeId = ui.activePlaylistId
            if (activeId != null) {
                Spacer(Modifier.height(12.dp))
                SectionHeader(
                    text = "歌单曲目",
                    trailing = {
                        TextButton(onClick = { vm.addCurrentToPlaylist(activeId) }) { Text("加入当前播放") }
                    },
                )
                if (ui.playlistTracks.isEmpty()) {
                    EmptyHint("该歌单没有曲目")
                } else {
                    ui.playlistTracks.forEach { link ->
                        val track = ui.tracks.firstOrNull { it.id == link.trackId }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(track?.title ?: "曲目已删除", modifier = Modifier.weight(1f))
                            IconButton(onClick = { vm.removeFromPlaylist(activeId, link.trackId) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "从歌单移除")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
