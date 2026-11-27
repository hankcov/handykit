package com.handykit.app.feature.music

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.handykit.app.core.db.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

data class PlaybackState(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
)

/**
 * 进程内唯一的 ExoPlayer 控制器。
 *
 * 位置只在本地持久化，供用户主动点播时续播；初始化时不读取、不自动 seek，
 * 因此进程被杀死后不会自动重播。音频焦点由 ExoPlayer 的 setAudioAttributes(..., true) 处理。
 */
object PlayerController {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val fileMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var player: ExoPlayer? = null
    @Volatile
    private var session: MediaSession? = null
    @Volatile
    private var positionsFile: File? = null

    private var positions: MutableMap<String, Long> = linkedMapOf()
    private var tickerStarted = false

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> get() = _state

    /** 必须在任何播放操作前调用一次（applicationContext）。 */
    fun init(context: Context) {
        player(context)
    }

    fun player(context: Context): ExoPlayer = synchronized(lock) {
        appContext = context.applicationContext
        player ?: run {
            val ctx = context.applicationContext
            positionsFile = File(ctx.filesDir, "playback_positions.json")
            positions = loadPositions(positionsFile!!)
            val attributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            val created = ExoPlayer.Builder(ctx)
                .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
                .build()
            created.repeatMode = Player.REPEAT_MODE_OFF
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) = refresh(created)
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    refresh(created)
                    if (!isPlaying) {
                        saveCurrentPosition(created)
                        flushPositions()
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    saveCurrentPosition(created)
                    refresh(created)
                }
                override fun onRepeatModeChanged(repeatMode: Int) = refresh(created)
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = refresh(created)
            })
            player = created
            startTicker()
            created
        }
    }

    fun session(context: Context): MediaSession = synchronized(lock) {
        session ?: MediaSession.Builder(context.applicationContext, player(context)).build().also { session = it }
    }

    fun currentSession(): MediaSession? = session

    /** 只把存在且可播放的曲目放进队列；返回 false 表示没有可播内容。 */
    fun playQueue(tracks: List<MusicTrack>, startTrackId: String, resume: Boolean = true): Boolean {
        val p = requirePlayer()
        val playable = tracks.filter {
            it.filePath.isNotBlank() && File(it.filePath).exists() && it.status == MusicRepository.STATUS_READY
        }
        if (playable.isEmpty()) return false
        saveCurrentPosition(p)
        val items = playable.map { track ->
            MediaItem.Builder().setUri(track.filePath).setMediaId(track.id).build()
        }
        val index = playable.indexOfFirst { it.id == startTrackId }.let { if (it < 0) 0 else it }
        val startPosition = if (resume) savedPosition(playable[index].id) else 0L
        p.setMediaItems(items, index, startPosition)
        p.prepare()
        p.play()
        refresh(p)
        return true
    }

    fun playPause() {
        val p = requirePlayer()
        if (p.isPlaying) p.pause() else p.play()
        refresh(p)
    }

    fun pause() {
        val p = requirePlayer()
        p.pause()
        refresh(p)
    }

    fun seekTo(positionMs: Long) {
        val p = requirePlayer()
        p.seekTo(positionMs.coerceAtLeast(0L))
        refresh(p)
    }

    fun next() {
        val p = requirePlayer()
        p.seekToNext()
        refresh(p)
    }

    fun previous() {
        val p = requirePlayer()
        if (p.currentPosition > 3000L) p.seekTo(0L) else p.seekToPrevious()
        refresh(p)
    }

    fun toggleRepeat() {
        val p = requirePlayer()
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        refresh(p)
    }

    fun toggleShuffle() {
        val p = requirePlayer()
        p.shuffleModeEnabled = !p.shuffleModeEnabled
        refresh(p)
    }

    fun savedPosition(trackId: String): Long = synchronized(lock) { positions[trackId] ?: 0L }

    fun clearPosition(trackId: String) {
        synchronized(lock) { positions.remove(trackId) }
        flushPositions()
    }

    /** 备份导出/恢复用，不触发播放器 seek。 */
    fun snapshotPositions(): Map<String, Long> = synchronized(lock) { positions.toMap() }

    fun restorePositions(map: Map<String, Long>) {
        synchronized(lock) { positions = LinkedHashMap(map) }
        flushPositions()
    }

    private fun requirePlayer(): ExoPlayer = player ?: player(
        appContext ?: error("PlayerController 尚未初始化，请先调用 init(context)")
    )

    private fun refresh(p: ExoPlayer) {
        val duration = if (p.duration == C.TIME_UNSET) 0L else p.duration
        _state.value = PlaybackState(
            trackId = p.currentMediaItem?.mediaId,
            isPlaying = p.isPlaying,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            repeatMode = p.repeatMode,
            shuffle = p.shuffleModeEnabled,
        )
    }

    private fun startTicker() {
        if (tickerStarted) return
        tickerStarted = true
        scope.launch {
            while (isActive) {
                delay(1000L)
                player?.let { p ->
                    if (p.isPlaying) {
                        saveCurrentPosition(p)
                        refresh(p)
                        flushPositions()
                    }
                }
            }
        }
    }

    private fun saveCurrentPosition(p: ExoPlayer) {
        val id = p.currentMediaItem?.mediaId ?: return
        val position = p.currentPosition
        if (position < 0L) return
        synchronized(lock) { positions[id] = position }
    }

    private fun flushPositions() {
        val file = positionsFile ?: return
        val snapshot = synchronized(lock) { positions.toMap() }
        scope.launch {
            fileMutex.withLock {
                withContext(Dispatchers.IO) {
                    val temp = File(file.absolutePath + ".tmp")
                    try {
                        temp.writeText(json.encodeToString(snapshot))
                        if (!temp.renameTo(file)) {
                            file.writeText(json.encodeToString(snapshot))
                            temp.delete()
                        }
                    } catch (e: Exception) {
                        temp.delete()
                    }
                }
            }
        }
    }

    private fun loadPositions(file: File): MutableMap<String, Long> = try {
        if (!file.exists()) mutableMapOf()
        else json.decodeFromString<Map<String, Long>>(file.readText()).toMutableMap()
    } catch (e: Exception) {
        mutableMapOf()
    }
}
