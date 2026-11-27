package com.handykit.app.feature.music

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.handykit.app.core.db.MusicLyric
import com.handykit.app.core.db.MusicPlaylist
import com.handykit.app.core.db.MusicTrack
import com.handykit.app.core.db.PlaylistTrack
import com.handykit.app.core.prefs.AppPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

sealed interface MusicEffect {
    data class Toast(val message: String) : MusicEffect
}

/** 音乐页状态与交互。依赖全部手动构造，不持有 Activity Context。 */
class MusicViewModel(
    private val repo: MusicRepository,
    private val prefs: AppPrefs,
) : ViewModel() {

    data class UiState(
        val tracks: List<MusicTrack> = emptyList(),
        val playlists: List<MusicPlaylist> = emptyList(),
        val playlistTracks: List<PlaylistTrack> = emptyList(),
        val playback: PlaybackState = PlaybackState(),
        val tasks: Map<String, CacheTaskState> = emptyMap(),
        val wifiOnly: Boolean = true,
        val resolveState: ResolveResult? = null,
        val resolving: Boolean = false,
        val selectedPage: Int = 1,
        val playlistsOpen: Boolean = false,
        val activePlaylistId: String? = null,
        val lyricOpen: Boolean = false,
        val lyricTrackId: String? = null,
        val lyricText: String = "",
        val lyricLines: List<LyricLine> = emptyList(),
        val lyricFormat: String = "lrc",
        val lyricError: String = "",
        val cacheStats: CacheStats = CacheStats(0, 0),
        val message: String = "",
    )

    private val resolver = BiliResolver()
    private val activePlaylistId = MutableStateFlow<String?>(null)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<MusicEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<MusicEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            repo.observeTracks().collect { list ->
                _uiState.update { it.copy(tracks = list) }
                refreshCacheStats()
            }
        }
        viewModelScope.launch {
            repo.observePlaylists().collect { list -> _uiState.update { it.copy(playlists = list) } }
        }
        viewModelScope.launch {
            PlayerController.state.collect { state -> _uiState.update { it.copy(playback = state) } }
        }
        viewModelScope.launch {
            repo.tasks.collect { map -> _uiState.update { it.copy(tasks = map) } }
        }
        viewModelScope.launch {
            prefs.musicWifiOnly.collect { value -> _uiState.update { it.copy(wifiOnly = value) } }
        }
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            activePlaylistId.flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else repo.observePlaylistTracks(id)
            }.collectLatest { list -> _uiState.update { it.copy(playlistTracks = list) } }
        }
    }

    private fun toast(message: String) {
        _effects.tryEmit(MusicEffect.Toast(message))
    }

    private suspend fun refreshCacheStats() {
        val stats = repo.cacheStats()
        _uiState.update { it.copy(cacheStats = stats) }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { prefs.setMusicWifiOnly(value) }
    }

    // ------------------------------------------------------------------
    // 播放
    // ------------------------------------------------------------------

    fun play(trackId: String) {
        val tracks = _uiState.value.tracks
        if (!PlayerController.playQueue(tracks, trackId, resume = true)) {
            toast("该曲目没有可播放的本地音频，请先缓存或检查文件")
        }
    }

    fun playPause() = PlayerController.playPause()
    fun next() = PlayerController.next()
    fun previous() = PlayerController.previous()
    fun toggleRepeat() = PlayerController.toggleRepeat()
    fun toggleShuffle() = PlayerController.toggleShuffle()
    fun seekTo(positionMs: Long) = PlayerController.seekTo(positionMs)
    fun seekToLyric(timeMs: Long?) {
        if (timeMs != null) PlayerController.seekTo(timeMs)
    }

    // ------------------------------------------------------------------
    // B 站解析与缓存
    // ------------------------------------------------------------------

    fun resolveLink(input: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = true, resolveState = null, selectedPage = 1) }
            val result = resolver.resolve(input)
            val firstPage = result.pages.firstOrNull()?.page ?: 1
            _uiState.update { it.copy(resolving = false, resolveState = result, selectedPage = firstPage) }
        }
    }

    fun closeResolve() = _uiState.update { it.copy(resolveState = null, resolving = false) }

    fun selectPage(page: Int) {
        val state = _uiState.value
        val result = state.resolveState ?: return
        val info = result.info ?: return
        val target = result.pages.firstOrNull { it.page == page } ?: return
        if (state.selectedPage == page && result is ResolveResult.Success) return
        viewModelScope.launch {
            _uiState.update { it.copy(resolving = true, selectedPage = page) }
            val audio = resolver.fetchAudioUrl(info.bvid, target.cid)
            val updated = when (audio) {
                is ResolveResult.Success ->
                    ResolveResult.Success(info, result.pages, audio.audioUrl, BiliResolver.AUDIO_NOTE)
                is ResolveResult.NeedLogin -> ResolveResult.NeedLogin(audio.reason, info, result.pages)
                is ResolveResult.NoAudioTrack -> ResolveResult.NoAudioTrack(audio.reason, info, result.pages)
                is ResolveResult.NetworkFail -> ResolveResult.NetworkFail(audio.reason, info, result.pages)
                else -> ResolveResult.ParseFail(audio.reason)
            }
            _uiState.update { it.copy(resolving = false, resolveState = updated) }
        }
    }

    fun cacheSelectedPage() {
        val state = _uiState.value
        val result = state.resolveState as? ResolveResult.Success ?: return
        val info = result.info ?: return
        if (result.audioUrl.isNullOrBlank()) {
            toast("该分 P 没有可用音频直链，未加入缓存")
            return
        }
        val page = result.pages.firstOrNull { it.page == state.selectedPage } ?: return
        viewModelScope.launch {
            try {
                val id = repo.enqueueBiliCache(info, page)
                PlayerController.clearPosition(id)
                toast("已加入缓存队列：${page.part.ifBlank { "P${page.page}" }}")
            } catch (e: Exception) {
                toast("加入缓存失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun retryCache(trackId: String) {
        viewModelScope.launch {
            repo.retryCache(trackId)
            toast("已重新排队")
        }
    }

    fun cancelCache(trackId: String) {
        repo.cancelCache(trackId)
        toast("已请求取消缓存任务")
    }

    fun clearTrackCache(trackId: String) {
        viewModelScope.launch {
            repo.clearTrackCache(trackId)
            refreshCacheStats()
            toast("已清理该曲目的缓存")
        }
    }

    fun removeTrack(trackId: String) {
        viewModelScope.launch {
            repo.removeTrack(trackId)
            PlayerController.clearPosition(trackId)
            refreshCacheStats()
            toast("曲目已移除")
        }
    }

    fun clearAllCache() {
        viewModelScope.launch {
            repo.clearAllCache()
            refreshCacheStats()
            toast("音频缓存已全部清理")
        }
    }

    fun importLocal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val summary = repo.importLocal(uris)
            refreshCacheStats()
            val message = buildString {
                append("导入 ${summary.added} 首")
                if (summary.duplicates > 0) append("，重复跳过 ${summary.duplicates} 首")
                if (summary.invalid > 0) append("，不可播放 ${summary.invalid} 首")
                summary.invalidDetails.firstOrNull()?.let { append("（$it）") }
            }
            toast(message)
        }
    }

    // ------------------------------------------------------------------
    // 歌单
    // ------------------------------------------------------------------

    fun togglePlaylistPanel(open: Boolean) = _uiState.update { it.copy(playlistsOpen = open) }

    fun selectPlaylist(id: String?) {
        activePlaylistId.value = id
        _uiState.update { it.copy(activePlaylistId = id) }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) {
            toast("请输入歌单名")
            return
        }
        viewModelScope.launch {
            val id = repo.createPlaylist(name)
            selectPlaylist(id)
            toast("歌单已创建")
        }
    }

    fun renamePlaylist(id: String, name: String) {
        viewModelScope.launch {
            repo.renamePlaylist(id, name)
            toast("歌单已重命名")
        }
    }

    fun deletePlaylist(id: String) {
        viewModelScope.launch {
            repo.deletePlaylist(id)
            if (_uiState.value.activePlaylistId == id) selectPlaylist(null)
            toast("歌单已删除")
        }
    }

    fun addCurrentToPlaylist(playlistId: String) {
        val trackId = _uiState.value.playback.trackId
        if (trackId == null) {
            toast("请先播放一首曲目")
            return
        }
        viewModelScope.launch {
            if (_uiState.value.playlistTracks.any { it.trackId == trackId }) {
                toast("该曲目已在歌单中")
                return@launch
            }
            repo.addToPlaylist(playlistId, trackId)
            toast("已加入歌单")
        }
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            repo.removeFromPlaylist(playlistId, trackId)
        }
    }

    // ------------------------------------------------------------------
    // 歌词
    // ------------------------------------------------------------------

    fun openLyrics(trackId: String) {
        viewModelScope.launch {
            val lyric: MusicLyric? = repo.lyric(trackId)
            val decoded = lyric?.content?.takeIf { it.isNotBlank() }
                ?.let { LyricCodec.decode(it.toByteArray(StandardCharsets.UTF_8)) }
            val lines = when (decoded) {
                is LyricDecodeResult.Success -> decoded.lines
                else -> emptyList()
            }
            _uiState.update {
                it.copy(
                    lyricOpen = true,
                    lyricTrackId = trackId,
                    lyricText = lyric?.content.orEmpty(),
                    lyricLines = lines,
                    lyricFormat = when (decoded) {
                        is LyricDecodeResult.Success -> decoded.format
                        else -> "lrc"
                    },
                    lyricError = if (decoded is LyricDecodeResult.InvalidUtf8) "歌词内容不是合法 UTF-8" else "",
                )
            }
        }
    }

    fun closeLyrics() = _uiState.update {
        it.copy(lyricOpen = false, lyricTrackId = null, lyricError = "")
    }

    fun onLyricTextChange(text: String) = _uiState.update { it.copy(lyricText = text) }

    fun saveLyric() {
        val state = _uiState.value
        val trackId = state.lyricTrackId ?: return
        val bytes = state.lyricText.toByteArray(StandardCharsets.UTF_8)
        val decoded = LyricCodec.decode(bytes)
        if (decoded is LyricDecodeResult.InvalidUtf8) {
            _uiState.update { it.copy(lyricError = "歌词内容不是合法 UTF-8，未保存") }
            return
        }
        val lines = when (decoded) {
            is LyricDecodeResult.Success -> decoded.lines
            else -> emptyList()
        }
        val format = when (decoded) {
            is LyricDecodeResult.Success -> decoded.format
            else -> "lrc"
        }
        viewModelScope.launch {
            repo.saveLyric(trackId, format, state.lyricText)
            _uiState.update {
                it.copy(lyricLines = lines, lyricFormat = format, lyricError = "", message = "歌词已保存")
            }
            toast("歌词已保存")
        }
    }

    fun loadLyricBytes(trackId: String, bytes: ByteArray) {
        val decoded = LyricCodec.decode(bytes)
        if (decoded is LyricDecodeResult.InvalidUtf8) {
            _uiState.update { it.copy(lyricError = "文件不是合法 UTF-8，未导入") }
            return
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        val lines = when (decoded) {
            is LyricDecodeResult.Success -> decoded.lines
            else -> emptyList()
        }
        _uiState.update {
            it.copy(
                lyricTrackId = trackId,
                lyricText = text,
                lyricLines = lines,
                lyricFormat = when (decoded) {
                    is LyricDecodeResult.Success -> decoded.format
                    else -> "lrc"
                },
                lyricError = "",
            )
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = "") }

    override fun onCleared() {
        repo.close()
        super.onCleared()
    }
}

class MusicViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MusicViewModel::class.java)) {
            val app = context.applicationContext
            PlayerController.init(app)
            return MusicViewModel(MusicRepository(app), AppPrefs(app)) as T
        }
        throw IllegalArgumentException("未支持的 ViewModel: ${modelClass.name}")
    }
}
