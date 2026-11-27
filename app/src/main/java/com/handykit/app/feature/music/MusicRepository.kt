package com.handykit.app.feature.music

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.handykit.app.core.db.AppDatabase
import com.handykit.app.core.db.MusicLyric
import com.handykit.app.core.db.MusicPlaylist
import com.handykit.app.core.db.MusicTrack
import com.handykit.app.core.db.PlaylistTrack
import com.handykit.app.core.prefs.AppPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CacheTaskState(
    val trackId: String,
    val status: String,
    val progress: Float,
    val message: String,
)

data class LocalImportSummary(
    val added: Int,
    val duplicates: Int,
    val invalid: Int,
    val invalidDetails: List<String>,
)

data class CacheStats(val bytes: Long, val fileCount: Int)

private data class Probe(val durationMs: Long, val hasAudio: Boolean, val hasVideo: Boolean)

/** 曲库与缓存数据层。手动构造传 applicationContext，不使用 Hilt。 */
class MusicRepository(context: Context) {

    private val app = context.applicationContext
    private val db = AppDatabase.get(app)
    private val dao = db.musicDao()
    private val prefs = AppPrefs(app)
    private val resolver = BiliResolver()
    private val http = OkHttpClient.Builder().followRedirects(true).build()

    private val audioDir = File(app.filesDir, "audio").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val reasons = ConcurrentHashMap<String, String>()

    private val _tasks = MutableStateFlow<Map<String, CacheTaskState>>(emptyMap())
    val tasks: StateFlow<Map<String, CacheTaskState>> = _tasks.asStateFlow()

    fun observeTracks(): Flow<List<MusicTrack>> = dao.observeTracks()
    fun observePlaylists(): Flow<List<MusicPlaylist>> = dao.observePlaylists()
    fun observePlaylistTracks(playlistId: String): Flow<List<PlaylistTrack>> = flow {
        emit(dao.playlistTracks(playlistId))
    }

    suspend fun tracks(): List<MusicTrack> = withContext(Dispatchers.IO) { dao.tracks() }
    suspend fun track(id: String): MusicTrack? = withContext(Dispatchers.IO) { dao.track(id) }
    suspend fun playlistTracks(playlistId: String): List<PlaylistTrack> = withContext(Dispatchers.IO) {
        dao.playlistTracks(playlistId)
    }

    fun failureReason(trackId: String): String? = reasons[trackId]

    // ------------------------------------------------------------------
    // B 站缓存任务：queued → parsing → downloading → verifying → ready/failed/cancel
    // ------------------------------------------------------------------

    /** 先落一条 queued 记录（按 bvid+cid 去重），后台启动下载，返回曲目 id。 */
    suspend fun enqueueBiliCache(info: BiliVideoInfo, page: BiliPage): String = withContext(Dispatchers.IO) {
        val existing = dao.findBili(info.bvid, page.cid)
        if (existing != null && existing.status == STATUS_READY &&
            existing.filePath.isNotBlank() && File(existing.filePath).exists()
        ) {
            return@withContext existing.id
        }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val title = if (page.part.isBlank()) info.title else "${info.title} · ${page.part}"
        val track = MusicTrack(
            id = id,
            title = title,
            author = info.author,
            source = "bili",
            bvid = info.bvid,
            cid = page.cid,
            sourceUrl = "${info.sourceUrl}?p=${page.page}",
            filePath = "",
            coverPath = "",
            durationMs = page.durationSec * 1000L,
            sizeBytes = 0L,
            status = STATUS_QUEUED,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
        )
        dao.upsertTrack(track)
        reasons.remove(id)
        setTask(id, STATUS_QUEUED, 0f, "已排队")
        startCache(id, info.bvid, page.cid)
        id
    }

    /** 重新缓存已失败/被清理的 B 站曲目。 */
    suspend fun retryCache(trackId: String) = withContext(Dispatchers.IO) {
        val track = dao.track(trackId) ?: return@withContext
        if (track.source != "bili" || track.bvid.isBlank() || track.cid.isBlank()) return@withContext
        jobs[trackId]?.cancel()
        reasons.remove(trackId)
        dao.upsertTrack(track.copy(status = STATUS_QUEUED, sizeBytes = 0L))
        setTask(trackId, STATUS_QUEUED, 0f, "已排队")
        startCache(trackId, track.bvid, track.cid)
    }

    fun cancelCache(trackId: String) {
        jobs[trackId]?.cancel()
    }

    private fun startCache(trackId: String, bvid: String, cid: String) {
        val job = scope.launch {
            var lastError = ""
            for (attempt in 1..MAX_ATTEMPTS) {
                if (!currentCoroutineContext().isActive) {
                    markCancel(trackId)
                    return@launch
                }
                try {
                    checkNetworkAllowed()
                    setTask(trackId, STATUS_PARSING, 0f, "解析音频直链（第 $attempt/$MAX_ATTEMPTS 次）")
                    setStatus(trackId, STATUS_PARSING)

                    val result = resolver.fetchAudioUrl(bvid, cid)
                    val url = (result as? ResolveResult.Success)?.audioUrl
                        ?: throw IOException(result.reason)

                    setTask(trackId, STATUS_DOWNLOADING, 0f, "下载原始音频流（第 $attempt/$MAX_ATTEMPTS 次）")
                    setStatus(trackId, STATUS_DOWNLOADING)
                    val ready = downloadAndVerify(trackId, url) { progress ->
                        setTask(trackId, STATUS_DOWNLOADING, progress, "下载中 ${(progress * 100).toInt()}%")
                    }
                    setTask(trackId, STATUS_VERIFYING, 1f, "完整性与可播放校验")
                    setStatus(trackId, STATUS_VERIFYING)
                    dao.upsertTrack(ready)
                    reasons.remove(trackId)
                    setTask(trackId, STATUS_READY, 1f, "已就绪（低清含音频原始流，非 MP3）")
                    return@launch
                } catch (e: CancellationException) {
                    markCancel(trackId)
                    return@launch
                } catch (e: Exception) {
                    lastError = e.message ?: e::class.java.simpleName
                    if (!currentCoroutineContext().isActive) {
                        markCancel(trackId)
                        return@launch
                    }
                    if (attempt < MAX_ATTEMPTS) {
                        setTask(trackId, STATUS_QUEUED, 0f, "失败，${400L * attempt}ms 后重试：$lastError")
                        delay(400L * attempt)
                    }
                }
            }
            markFailed(trackId, "重试 $MAX_ATTEMPTS 次仍失败：$lastError")
        }
        jobs[trackId] = job
        job.invokeOnCompletion { jobs.remove(trackId, job) }
    }

    private suspend fun downloadAndVerify(
        trackId: String,
        url: String,
        onProgress: (Float) -> Unit,
    ): MusicTrack = withContext(Dispatchers.IO) {
        val temp = File(audioDir, "$trackId.part")
        if (temp.exists() && !temp.delete()) throw IOException("无法清理旧临时文件")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .header("Referer", "https://www.bilibili.com/")
            .build()

        var written = 0L
        var expected = -1L
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("音频流 HTTP ${response.code}")
            val body = response.body ?: throw IOException("音频流响应体为空")
            expected = body.contentLength()
            if (expected > MAX_STREAM_BYTES) {
                throw IOException("流大小 ${expected} 字节超过 ${MAX_STREAM_BYTES} 上限，已停止以避免下载完整视频")
            }
            body.byteStream().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (!currentCoroutineContext().isActive) throw CancellationException()
                        written += read
                        if (written > MAX_STREAM_BYTES) {
                            throw IOException("下载量超过 ${MAX_STREAM_BYTES} 字节上限，已停止")
                        }
                        output.write(buffer, 0, read)
                        if (expected > 0) onProgress((written.toFloat() / expected).coerceIn(0f, 1f))
                    }
                    output.flush()
                }
            }
        }
        if (written == 0L) throw IOException("下载到 0 字节")
        if (expected > 0 && written != expected) {
            throw IOException("下载不完整：已收 $written / 应有 $expected 字节")
        }
        val probe = probe(temp.path) ?: throw IOException("文件无法被 Media3 解码：格式不支持、损坏或未下载完整")
        if (!probe.hasAudio) throw IOException("流中未检测到音频轨，标记为失败而不加入可播放列表")
        if (!hasAudioMagic(temp)) throw IOException("文件头不是已知音频/音视频容器，拒绝标记为可播放")

        val final = File(audioDir, "${trackId}${extensionFor(url)}")
        if (final.exists() && !final.delete()) throw IOException("无法覆盖旧音频文件")
        if (!temp.renameTo(final)) throw IOException("无法保存音频文件")
        val current = dao.track(trackId) ?: throw IOException("曲目已被删除")
        current.copy(
            filePath = final.absolutePath,
            sizeBytes = final.length(),
            durationMs = if (probe.durationMs > 0) probe.durationMs else current.durationMs,
            contentHash = sha256(final),
            status = STATUS_READY,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun checkNetworkAllowed() {
        if (!prefs.musicWifiOnly.first()) return
        val allowed = try {
            val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = if (cm != null && network != null) cm.getNetworkCapabilities(network) else null
            caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
        } catch (e: SecurityException) {
            false
        }
        if (!allowed) {
            throw IOException("Wi-Fi-only 已开启，当前无法确认处于 Wi-Fi/以太网，已停止下载")
        }
    }

    // ------------------------------------------------------------------
    // 本地音频导入
    // ------------------------------------------------------------------

    suspend fun importLocal(uris: List<Uri>): LocalImportSummary = withContext(Dispatchers.IO) {
        val byHash = dao.tracks().associateBy { it.contentHash }.toMutableMap()
        var added = 0
        var duplicates = 0
        var invalid = 0
        val details = mutableListOf<String>()

        for (uri in uris) {
            val name = displayName(uri) ?: "audio_${UUID.randomUUID().toString().take(8)}"
            val temp = File(audioDir, "import_${UUID.randomUUID()}.part")
            try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output, 64 * 1024) }
                } ?: throw IOException("无法读取系统文件")
                if (temp.length() == 0L) throw IOException("空文件")
                val hash = sha256(temp)
                if (byHash.containsKey(hash)) {
                    duplicates++
                    temp.delete()
                    continue
                }
                if (!hasAudioMagic(temp)) throw IOException("文件头不是已知音频/音视频容器")
                val probe = probe(temp.path) ?: throw IOException("Media3 无法解码该文件")
                if (!probe.hasAudio) throw IOException("未检测到音频轨")
                val id = UUID.randomUUID().toString()
                val final = File(audioDir, "$id${extensionForName(name)}")
                if (!temp.renameTo(final)) throw IOException("无法保存到私有目录")
                val track = MusicTrack(
                    id = id,
                    title = name.substringBeforeLast('.', name),
                    author = "",
                    source = "local",
                    filePath = final.absolutePath,
                    contentHash = hash,
                    durationMs = probe.durationMs,
                    sizeBytes = final.length(),
                    status = STATUS_READY,
                )
                dao.upsertTrack(track)
                byHash[hash] = track
                added++
            } catch (e: Exception) {
                invalid++
                val detail = e.message ?: e::class.java.simpleName
                details += "$name：$detail"
                temp.delete()
            }
        }
        LocalImportSummary(added, duplicates, invalid, details)
    }

    private fun displayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        }
    }

    // ------------------------------------------------------------------
    // 曲库 / 歌单 / 歌词
    // ------------------------------------------------------------------

    suspend fun upsertTrack(track: MusicTrack) = withContext(Dispatchers.IO) { dao.upsertTrack(track) }

    suspend fun removeTrack(trackId: String) = withContext(Dispatchers.IO) {
        jobs[trackId]?.cancel()
        deleteTrackFile(trackId)
        // DAO 未提供删除歌词接口，清空内容等价于移除歌词。歌单关联由外键级联清理。
        dao.upsertLyric(MusicLyric(trackId, "txt", "", "manual"))
        dao.deleteTrack(trackId)
        reasons.remove(trackId)
        _tasks.value = _tasks.value - trackId
    }

    suspend fun clearTrackCache(trackId: String) = withContext(Dispatchers.IO) {
        jobs[trackId]?.cancel()
        deleteTrackFile(trackId)
        dao.track(trackId)?.let { dao.upsertTrack(it.copy(filePath = "", sizeBytes = 0L, status = STATUS_MISSING)) }
        reasons[trackId] = "缓存已清理，需重新缓存"
        setTask(trackId, STATUS_MISSING, 0f, "缓存已清理，需重新缓存")
    }

    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        audioDir.listFiles()?.forEach { it.delete() }
        dao.tracks().forEach { track ->
            dao.upsertTrack(track.copy(filePath = "", sizeBytes = 0L, status = STATUS_MISSING))
            reasons[track.id] = "缓存已清理，需重新缓存"
        }
        _tasks.value = emptyMap()
    }

    suspend fun cacheStats(): CacheStats = withContext(Dispatchers.IO) {
        val files = audioDir.listFiles()?.filter { it.isFile }.orEmpty()
        CacheStats(files.sumOf { it.length() }, files.size)
    }

    private suspend fun deleteTrackFile(trackId: String) {
        dao.track(trackId)?.filePath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        File(audioDir, "$trackId.part").delete()
    }

    suspend fun createPlaylist(name: String): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        dao.upsertPlaylist(MusicPlaylist(id, name.trim().ifBlank { "新歌单" }))
        id
    }

    suspend fun renamePlaylist(id: String, name: String) = withContext(Dispatchers.IO) {
        dao.observePlaylists().first().firstOrNull { it.id == id }?.let { playlist ->
            dao.upsertPlaylist(playlist.copy(name = name.trim().ifBlank { playlist.name }))
        }
        Unit
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        dao.clearPlaylist(id)
        dao.deletePlaylist(id)
    }

    suspend fun addToPlaylist(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        val current = dao.playlistTracks(playlistId)
        val position = (current.maxOfOrNull { it.position } ?: -1) + 1
        dao.addPlaylistTrack(PlaylistTrack(playlistId = playlistId, trackId = trackId, position = position))
    }

    suspend fun removeFromPlaylist(playlistId: String, trackId: String) = withContext(Dispatchers.IO) {
        dao.removePlaylistTrack(playlistId, trackId)
    }

    suspend fun lyric(trackId: String): MusicLyric? = withContext(Dispatchers.IO) { dao.lyric(trackId) }

    suspend fun saveLyric(trackId: String, format: String, content: String) = withContext(Dispatchers.IO) {
        dao.upsertLyric(MusicLyric(trackId, format, content, "manual"))
    }

    /** 备份恢复用：原子替换音乐域数据。 */
    suspend fun replaceAllMusic(
        tracks: List<MusicTrack>,
        playlists: List<MusicPlaylist>,
        links: List<PlaylistTrack>,
        lyrics: List<MusicLyric>,
    ) = withContext(Dispatchers.IO) {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        db.withTransaction {
            dao.observePlaylists().first().forEach { dao.deletePlaylist(it.id) }
            dao.tracks().forEach { dao.deleteTrack(it.id) }
            tracks.forEach { dao.upsertTrack(it) }
            playlists.forEach { dao.upsertPlaylist(it) }
            links.forEach { dao.addPlaylistTrack(it) }
            lyrics.forEach { dao.upsertLyric(it) }
        }
        _tasks.value = emptyMap()
        reasons.clear()
    }

    fun close() {
        jobs.values.forEach { it.cancel() }
        scope.cancel()
    }

    private suspend fun setStatus(trackId: String, status: String) {
        dao.track(trackId)?.let { dao.upsertTrack(it.copy(status = status)) }
    }

    private fun setTask(trackId: String, status: String, progress: Float, message: String) {
        _tasks.value = _tasks.value + (trackId to CacheTaskState(trackId, status, progress, message))
    }

    private suspend fun markFailed(trackId: String, message: String) {
        reasons[trackId] = message
        dao.track(trackId)?.let { dao.upsertTrack(it.copy(status = STATUS_FAILED, sizeBytes = 0L)) }
        setTask(trackId, STATUS_FAILED, 0f, message)
    }

    private suspend fun markCancel(trackId: String) {
        reasons[trackId] = "缓存任务已取消"
        dao.track(trackId)?.let { dao.upsertTrack(it.copy(status = STATUS_CANCEL, sizeBytes = 0L)) }
        setTask(trackId, STATUS_CANCEL, 0f, "缓存任务已取消")
    }

    companion object {
        const val STATUS_READY = "ready"
        const val STATUS_QUEUED = "queued"
        const val STATUS_PARSING = "parsing"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_VERIFYING = "verifying"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCEL = "cancel"
        const val STATUS_MISSING = "missing"

        const val MAX_ATTEMPTS = 3
        const val MAX_STREAM_BYTES = 200L * 1024 * 1024

        fun statusLabel(status: String): String = when (status) {
            STATUS_READY -> "可播放"
            STATUS_QUEUED -> "排队中"
            STATUS_PARSING -> "解析中"
            STATUS_DOWNLOADING -> "下载中"
            STATUS_VERIFYING -> "校验中"
            STATUS_FAILED -> "失败"
            STATUS_CANCEL -> "已取消"
            STATUS_MISSING -> "需重新缓存"
            else -> status
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        }

        private fun probe(path: String): Probe? {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(path)
                val count = extractor.trackCount
                if (count <= 0) return null
                var hasAudio = false
                var hasVideo = false
                var audioIndex = -1
                var durationMs = 0L
                for (i in 0 until count) {
                    val format = extractor.getTrackFormat(i)
                    val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull().orEmpty()
                    val d = runCatching { format.getInteger(MediaFormat.KEY_DURATION).toLong() }.getOrDefault(0L)
                    if (d > durationMs) durationMs = d / 1000L
                    when {
                        mime.startsWith("audio/") -> {
                            hasAudio = true
                            if (audioIndex < 0) audioIndex = i
                        }
                        mime.startsWith("video/") -> hasVideo = true
                    }
                }
                if (!hasAudio || audioIndex < 0) return null
                // 真正读一个音频 sample：仅看 header 不足以发现截断流。
                extractor.selectTrack(audioIndex)
                val buffer = java.nio.ByteBuffer.allocateDirect(64 * 1024)
                if (extractor.readSampleData(buffer, 0) <= 0) return null
                Probe(durationMs, hasAudio, hasVideo)
            } catch (e: Exception) {
                null
            } finally {
                extractor.release()
            }
        }

        /** magic 只做快速排除；最终可播放性以 Media3 实际读 sample 为准。 */
        private fun hasAudioMagic(file: File): Boolean {
            val head = ByteArray(16)
            file.inputStream().use { input ->
                val n = input.read(head)
                if (n < 4) return false
            }
            fun starts(signature: ByteArray, offset: Int = 0): Boolean {
                if (head.size < offset + signature.size) return false
                return signature.indices.all { head[offset + it] == signature[it] }
            }
            return starts("ID3".toByteArray()) ||
                (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) ||
                starts("OggS".toByteArray()) ||
                starts("RIFF".toByteArray()) ||
                starts("fLaC".toByteArray()) ||
                starts("FLV".toByteArray()) ||
                (head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte()) ||
                starts(byteArrayOf(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte()))
        }

        private fun extensionFor(url: String): String {
            val pathPart = url.substringBefore('?').substringAfterLast('/')
            val ext = pathPart.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return if (ext in setOf("flv", "m4a", "mp4", "mp3", "ogg", "wav", "webm")) ".$ext" else ".flv"
        }

        private fun extensionForName(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return if (ext.length in 2..5) ".$ext" else ".bin"
        }
    }
}
