package com.handykit.app.feature.backup

import android.content.Context
import androidx.room.withTransaction
import com.handykit.app.core.crypto.SecureNoteEntity
import com.handykit.app.core.crypto.VaultCrypto
import com.handykit.app.core.crypto.VaultSession
import com.handykit.app.core.db.AiDao
import com.handykit.app.core.db.AiMessage
import com.handykit.app.core.db.AiProvider
import com.handykit.app.core.db.AiRole
import com.handykit.app.core.db.AiSession
import com.handykit.app.core.db.AppDatabase
import com.handykit.app.core.db.FuelDao
import com.handykit.app.core.db.VehicleDao
import com.handykit.app.core.db.FuelRecord
import com.handykit.app.core.db.Measurement
import com.handykit.app.core.db.MusicDao
import com.handykit.app.core.db.MusicLyric
import com.handykit.app.core.db.MusicPlaylist
import com.handykit.app.core.db.MusicTrack
import com.handykit.app.core.db.PlaylistTrack
import com.handykit.app.core.db.ScaleDao
import com.handykit.app.core.db.ScaleMember
import com.handykit.app.core.db.Vehicle
import com.handykit.app.core.crypto.NoteDatabase
import com.handykit.app.feature.music.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate

@Serializable
enum class BackupDomain {
    NOTES, AI, FUEL, SCALE, MUSIC,
}

@Serializable
data class BackupOptions(
    val domains: List<BackupDomain>,
    /** AI 附件默认包含，可排除。API Key 与敏感头始终排除。 */
    val includeAiAttachments: Boolean = true,
)

@Serializable
data class BackupEnvelope(
    val format: String = "handykit-backup",
    val version: Int = 1,
    val createdAt: Long,
    val domains: List<String>,
    val recordCounts: Map<String, Int> = emptyMap(),
    val iterations: Int,
    val salt: String,
    /** 笔记域独立密钥的派生盐，与 AI 凭据无关。 */
    val notesSalt: String,
    val payload: String,
    val payloadDigest: String,
    val notesDigest: String? = null,
    val excluded: List<String> = emptyList(),
)

@Serializable
data class BackupPayload(
    val notesCipher: String? = null,
    val ai: AiBackup? = null,
    val fuel: FuelBackup? = null,
    val scale: ScaleBackup? = null,
    val music: MusicBackup? = null,
)

@Serializable data class NoteDto(
    val id: String, val title: String, val body: String, val category: String,
    val createdAt: Long, val updatedAt: Long,
)

@Serializable data class AiSessionDto(
    val id: String, val title: String, val roleId: String?, val rolePromptSnapshot: String,
    val paramsSnapshotJson: String, val providerId: String, val model: String,
    val createdAt: Long, val updatedAt: Long, val contextClearedAt: Long,
)

@Serializable data class AiMessageDto(
    val id: String, val sessionId: String, val role: String, val content: String,
    val status: String, val provider: String, val model: String, val createdAt: Long,
    val providerReady: Boolean, val errorKind: String?, val attachmentIdsJson: String,
    val includeInContext: Boolean,
)

@Serializable data class AiRoleDto(
    val id: String, val name: String, val systemPrompt: String, val paramsJson: String, val createdAt: Long,
)

@Serializable data class AiProviderDto(
    val id: String, val name: String, val protocol: String, val baseUrl: String,
    val model: String, val imageCapability: String, val pdfCapability: String, val createdAt: Long,
)

@Serializable data class AttachmentDto(
    val id: String, val sessionId: String, val name: String, val type: String, val mime: String,
    val sizeBytes: Long, val localPath: String, val status: String, val extractedText: String,
    val pdfPages: Int, val createdAt: Long, val bytesBase64: String? = null,
)

@Serializable data class AiBackup(
    val sessions: List<AiSessionDto> = emptyList(),
    val messages: List<AiMessageDto> = emptyList(),
    val roles: List<AiRoleDto> = emptyList(),
    val providers: List<AiProviderDto> = emptyList(),
    val attachments: List<AttachmentDto> = emptyList(),
)

@Serializable data class VehicleDto(
    val id: Long, val name: String, val plate: String, val tankCapacity: Double,
    val fuelGrade: String, val createdAt: Long,
)

@Serializable data class FuelRecordDto(
    val id: Long, val vehicleId: Long, val date: String, val odometer: Int,
    val liters: Double, val amount: Double, val price: Double, val isFull: Boolean,
    val station: String?, val note: String?, val estimated: Boolean, val invalidSegment: Boolean,
)

@Serializable data class FuelBackup(
    val vehicles: List<VehicleDto> = emptyList(),
    val records: List<FuelRecordDto> = emptyList(),
)

@Serializable data class ScaleMemberDto(
    val id: String, val name: String, val sex: String, val heightCm: Double,
    val birthDateEpochMs: Long, val referenceWeightKg: Double,
)

@Serializable data class MeasurementDto(
    val id: String, val measuredAtEpochMs: Long, val weightKg: Double, val impedanceOhm: Double,
    val bmi: Double, val bodyFatPct: Double, val muscleKg: Double, val waterPct: Double,
    val proteinPct: Double, val boneMassKg: Double, val basalMetabolic: Double,
    val memberId: String?, val memberNameSnapshot: String?, val source: String,
    val algorithmVersion: String,
)

@Serializable data class ScaleBackup(
    val members: List<ScaleMemberDto> = emptyList(),
    val measurements: List<MeasurementDto> = emptyList(),
)

@Serializable data class MusicTrackDto(
    val id: String, val title: String, val author: String, val source: String,
    val bvid: String, val cid: String, val sourceUrl: String, val filePath: String,
    val coverPath: String, val contentHash: String, val durationMs: Long,
    val sizeBytes: Long, val status: String, val addedAt: Long,
)

@Serializable data class MusicPlaylistDto(val id: String, val name: String, val createdAt: Long)
@Serializable data class PlaylistTrackDto(val id: Long, val playlistId: String, val trackId: String, val position: Int)
@Serializable data class MusicLyricDto(val trackId: String, val format: String, val content: String, val source: String)

@Serializable data class MusicBackup(
    val tracks: List<MusicTrackDto> = emptyList(),
    val playlists: List<MusicPlaylistDto> = emptyList(),
    val links: List<PlaylistTrackDto> = emptyList(),
    val lyrics: List<MusicLyricDto> = emptyList(),
    /** 播放进度；不含音频文件本身。 */
    val positions: Map<String, Long> = emptyMap(),
)

data class BackupPreview(
    val domains: List<BackupDomain>,
    val counts: Map<String, Int>,
    val warnings: List<String>,
    val createdAt: Long,
    val version: Int,
    val attachmentCount: Int,
    val hasNotes: Boolean,
    val hasAudio: Boolean,
)

data class ExportResult(
    val bytes: Long,
    val counts: Map<String, Int>,
    val warnings: List<String>,
)

data class RestoreResult(
    val domains: List<BackupDomain>,
    val counts: Map<String, Int>,
    val warnings: List<String>,
)

class BackupException(message: String) : Exception(message)

private data class VerifiedBackup(
    val envelope: BackupEnvelope,
    val payload: BackupPayload,
    val notes: List<NoteDto>?,
)

/**
 * 分域备份恢复。
 *
 * 安全属性：
 *  - 包体用备份口令 PBKDF2 派生 KEK + AES-GCM 加密，附明文 SHA-256 摘要；
 *  - 笔记域只有传入有效 VaultSession 才导出，且用独立派生密钥加密，不与 AI 凭据共用；
 *  - 导入先完整校验（口令 / 摘要 / 版本 / 域 / 关联），任何一步失败都不写库；
 *  - 写库前保存所选域快照，跨库写入失败时按快照回滚；其它域不触碰。
 *
 * 限制：AppDatabase 与 NoteDatabase 是两个 Room 库，无法共用一个数据库事务；
 * 极端情况下（进程被强杀）跨库写入可能只完成一部分，恢复后请复核。
 */
class BackupManager(
    context: Context,
    /** 由宿主提供已解锁的笔记会话；返回 null 时笔记域不可用。 */
    private val vaultAccess: () -> VaultSession? = { null },
) {
    private val app = context.applicationContext
    private val db = AppDatabase.get(app)
    private val noteDb = NoteDatabase.get(app)
    private val vehicleDao: VehicleDao = db.vehicleDao()
    private val fuelDao: FuelDao = db.fuelDao()
    private val scaleDao: ScaleDao = db.scaleDao()
    private val aiDao: AiDao = db.aiDao()
    private val musicDao: MusicDao = db.musicDao()
    private val noteDao = noteDb.noteDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val attachmentDir = File(app.filesDir, "ai_attachments").apply { mkdirs() }

    suspend fun estimateAttachmentBytes(): Long = withContext(Dispatchers.IO) {
        val sessions = aiDao.sessions()
        sessions.flatMap { aiDao.attachmentsBySession(it.id) }
            .distinctBy { it.id }
            .sumOf { attachment ->
                val path = attachment.localPath
                if (path.isNotBlank()) File(path).length() else 0L
            }
    }

    /** 导出：先完整构造并加密，最后一次性写流。任何校验失败都不会产生输出。 */
    suspend fun export(
        options: BackupOptions,
        password: CharArray,
        output: OutputStream,
    ): ExportResult = withContext(Dispatchers.IO) {
        if (password.isEmpty()) throw BackupException("请先设置备份口令")
        if (options.domains.isEmpty()) throw BackupException("请至少选择一个数据域")
        val domains = options.domains.distinct()
        val warnings = mutableListOf<String>()

        val notesKey: javax.crypto.SecretKey?
        var notesCipher: String? = null
        var notesDigest: String? = null
        var notesDto: List<NoteDto>? = null
        val notesSalt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
        if (BackupDomain.NOTES in domains) {
            val session = vaultAccess()
                ?: throw BackupException("笔记域需要先解锁安全笔记；未解锁，未生成备份文件")
            if (!session.isValid) throw BackupException("笔记已锁定，未生成备份文件")
            val dek = session.requireDek(session.currentGeneration())
            notesDto = noteDao.all().map { note ->
                NoteDto(
                    id = note.id,
                    title = String(VaultCrypto.decrypt(note.titleCipher, dek), StandardCharsets.UTF_8),
                    body = String(VaultCrypto.decrypt(note.bodyCipher, dek), StandardCharsets.UTF_8),
                    category = String(VaultCrypto.decrypt(note.categoryCipher, dek), StandardCharsets.UTF_8),
                    createdAt = note.createdAt,
                    updatedAt = note.updatedAt,
                )
            }
            notesKey = deriveNotesKey(password, notesSalt)
            val plain = json.encodeToString(notesDto).toByteArray(StandardCharsets.UTF_8)
            notesDigest = sha256Hex(plain)
            notesCipher = VaultCrypto.encrypt(plain, notesKey)
        } else {
            notesKey = null
        }

        var ai: AiBackup? = null
        if (BackupDomain.AI in domains) {
            val sessions = aiDao.sessions().map { s ->
                AiSessionDto(s.id, s.title, s.roleId, s.rolePromptSnapshot, s.paramsSnapshotJson,
                    s.providerId, s.model, s.createdAt, s.updatedAt, s.contextClearedAt)
            }
            val messages = sessions.flatMap { aiDao.messages(it.id) }.map { m ->
                AiMessageDto(m.id, m.sessionId, m.role, m.content, m.status, m.provider, m.model,
                    m.createdAt, m.providerReady, m.errorKind, m.attachmentIdsJson, m.includeInContext)
            }
            val attachments = sessions.flatMap { aiDao.attachmentsBySession(it.id) }.distinctBy { it.id }
            val attachmentDtos = attachments.map { a ->
                val path = a.localPath
                val bytes = if (options.includeAiAttachments && path.isNotBlank() && File(path).exists()) {
                    VaultCrypto.b64(File(path).readBytes())
                } else {
                    if (options.includeAiAttachments && a.sizeBytes > 0L) {
                        warnings += "附件「${a.name}」文件已丢失，包中不含其内容"
                    }
                    null
                }
                AttachmentDto(a.id, a.sessionId, a.name, a.type, a.mime, a.sizeBytes, a.localPath,
                    a.status, a.extractedText, a.pdfPages, a.createdAt, bytes)
            }
            ai = AiBackup(
                sessions = sessions,
                messages = messages,
                roles = aiDao.roles().map { AiRoleDto(it.id, it.name, it.systemPrompt, it.paramsJson, it.createdAt) },
                providers = aiDao.providers().map {
                    AiProviderDto(it.id, it.name, it.protocol, it.baseUrl, it.model,
                        it.imageCapability, it.pdfCapability, it.createdAt)
                },
                attachments = attachmentDtos,
            )
            warnings += "API Key 与敏感请求头未导出；恢复后需在 AI 设置中重新配置（待补充）"
            if (!options.includeAiAttachments) warnings += "已按要求排除附件内容"
        }

        var fuel: FuelBackup? = null
        if (BackupDomain.FUEL in domains) {
            val vehicles = vehicleDao.all().map {
                VehicleDto(it.id, it.name, it.plate, it.tankCapacity, it.fuelGrade, it.createdAt)
            }
            val records = fuelDao.all().map { r ->
                FuelRecordDto(r.id, r.vehicleId, r.date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE),
                    r.odometer, r.liters, r.amount, r.price, r.isFull, r.station, r.note,
                    r.estimated, r.invalidSegment)
            }
            fuel = FuelBackup(vehicles, records)
        }

        var scale: ScaleBackup? = null
        if (BackupDomain.SCALE in domains) {
            val members = scaleDao.members().map {
                ScaleMemberDto(it.id, it.name, it.sex, it.heightCm, it.birthDateEpochMs, it.referenceWeightKg)
            }
            val measurements = scaleDao.measurements().map { m ->
                MeasurementDto(m.id, m.measuredAtEpochMs, m.weightKg, m.impedanceOhm, m.bmi,
                    m.bodyFatPct, m.muscleKg, m.waterPct, m.proteinPct, m.boneMassKg, m.basalMetabolic,
                    m.memberId, m.memberNameSnapshot, m.source, m.algorithmVersion)
            }
            scale = ScaleBackup(members, measurements)
        }

        var music: MusicBackup? = null
        if (BackupDomain.MUSIC in domains) {
            val tracks = musicDao.tracks().map {
                MusicTrackDto(it.id, it.title, it.author, it.source, it.bvid, it.cid, it.sourceUrl,
                    it.filePath, it.coverPath, it.contentHash, it.durationMs, it.sizeBytes, it.status, it.addedAt)
            }
            val playlists = musicDao.observePlaylists().first().map {
                MusicPlaylistDto(it.id, it.name, it.createdAt)
            }
            val links = playlists.flatMap { p -> musicDao.playlistTracks(p.id) }.map {
                PlaylistTrackDto(it.id, it.playlistId, it.trackId, it.position)
            }
            val lyrics = tracks.mapNotNull { t -> musicDao.lyric(t.id) }.map {
                MusicLyricDto(it.trackId, it.format, it.content, it.source)
            }
            music = MusicBackup(tracks, playlists, links, lyrics, PlayerController.snapshotPositions())
            warnings += "音乐音频缓存未包含（约 ${trackCacheBytes(tracks.map { it.filePath })} 字节）；恢复后需重新缓存"
        }

        val payload = BackupPayload(notesCipher, ai, fuel, scale, music)
        val payloadBytes = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
        val kek = VaultCrypto.deriveKek(password, salt)
        val envelope = BackupEnvelope(
            createdAt = System.currentTimeMillis(),
            domains = domains.map { it.name },
            recordCounts = countsOf(payload, notesDto),
            iterations = VaultCrypto.PBKDF2_ITERATIONS,
            salt = VaultCrypto.b64(salt),
            notesSalt = VaultCrypto.b64(notesSalt),
            payload = VaultCrypto.encrypt(payloadBytes, kek),
            payloadDigest = sha256Hex(payloadBytes),
            notesDigest = notesDigest,
            excluded = listOf("ai.api_keys", "ai.sensitive_headers", "music.audio_cache"),
        )
        val bytes = json.encodeToString(envelope).toByteArray(StandardCharsets.UTF_8)
        output.write(bytes)
        output.flush()
        ExportResult(bytes.size.toLong(), envelope.recordCounts, warnings)
    }

    /** 只读预览：完成口令、完整性、版本、关联校验，不触碰任何数据。 */
    suspend fun inspect(input: InputStream, password: CharArray): BackupPreview = withContext(Dispatchers.IO) {
        val verified = readVerified(input, password)
        val payload = verified.payload
        val domains = verified.envelope.domains.map { name ->
            runCatching { BackupDomain.valueOf(name) }.getOrNull()
                ?: throw BackupException("备份包含不支持的数据域：$name")
        }
        val warnings = mutableListOf<String>()
        if (payload.ai != null) {
            warnings += "API Key 与敏感头未在包内；恢复后需重新配置（待补充）"
        }
        if (payload.music != null) {
            warnings += "音频缓存未包含在包内；恢复后曲目标记「需重新缓存」"
        }
        payload.ai?.attachments?.takeIf { it.isNotEmpty() }?.let { attachments ->
            val included = attachments.count { it.bytesBase64 != null }
            warnings += "AI 附件 ${attachments.size} 个，其中含内容 $included 个"
        }
        BackupPreview(
            domains = domains,
            counts = verified.envelope.recordCounts,
            warnings = warnings,
            createdAt = verified.envelope.createdAt,
            version = verified.envelope.version,
            attachmentCount = payload.ai?.attachments?.size ?: 0,
            hasNotes = payload.notesCipher != null,
            hasAudio = false,
        )
    }

    /** 恢复：再次完整校验后按所选域原子写入，失败按快照回滚。 */
    suspend fun restore(
        input: InputStream,
        password: CharArray,
        selected: List<BackupDomain>,
    ): RestoreResult = withContext(Dispatchers.IO) {
        val verified = readVerified(input, password)
        val payload = verified.payload
        val available = verified.envelope.domains.mapNotNull { runCatching { BackupDomain.valueOf(it) }.getOrNull() }
        val unknown = selected.filter { it !in available }
        if (unknown.isNotEmpty()) throw BackupException("备份包不含所选域：${unknown.joinToString { it.name }}")
        if (selected.isEmpty()) throw BackupException("请选择要恢复的数据域")

        val notes = if (BackupDomain.NOTES in selected) {
            val session = vaultAccess() ?: throw BackupException("恢复笔记前需要先解锁安全笔记")
            if (!session.isValid) throw BackupException("笔记已锁定，未执行恢复")
            // 先校验笔记密文可解开，再进入任何写入流程。
            val generation = session.currentGeneration()
            val dek = session.requireDek(generation)
            val notesKey = deriveNotesKey(password, VaultCrypto.unb64(verified.envelope.notesSalt))
            val notesBytes = VaultCrypto.decrypt(
                payload.notesCipher ?: throw BackupException("备份包不含笔记域数据"),
                notesKey,
            )
            if (sha256Hex(notesBytes) != verified.envelope.notesDigest) {
                throw BackupException("笔记域完整性校验失败，未执行恢复")
            }
            val list = json.decodeFromString<List<NoteDto>>(String(notesBytes, StandardCharsets.UTF_8))
            // 用目标库的 DEK 加密，保证笔记仍由笔记密钥保护，与备份口令/AI 凭据无关。
            list.map { dto -> dto to SessionDek(dek, generation) }
        } else {
            emptyList()
        }

        val snapshot = capture(selected)
        try {
            if (BackupDomain.NOTES in selected) {
                noteDb.withTransaction {
                    noteDao.clearAll()
                    notes.forEach { (dto, dekHolder) ->
                        val liveSession = vaultAccess()
                            ?: throw BackupException("笔记会话已失效，恢复中止并回滚")
                        if (!liveSession.isValid ||
                            liveSession.currentGeneration() != dekHolder.generation
                        ) {
                            throw BackupException("笔记已锁定或会话已变更，恢复中止并回滚")
                        }
                        noteDao.upsert(
                            SecureNoteEntity(
                                id = dto.id,
                                titleCipher = VaultCrypto.encrypt(
                                    dto.title.toByteArray(StandardCharsets.UTF_8), dekHolder.key
                                ),
                                bodyCipher = VaultCrypto.encrypt(
                                    dto.body.toByteArray(StandardCharsets.UTF_8), dekHolder.key
                                ),
                                categoryCipher = VaultCrypto.encrypt(
                                    dto.category.toByteArray(StandardCharsets.UTF_8), dekHolder.key
                                ),
                                createdAt = dto.createdAt,
                                updatedAt = dto.updatedAt,
                            )
                        )
                    }
                }
            }
            db.withTransaction {
                if (BackupDomain.FUEL in selected) applyFuel(payload.fuel ?: FuelBackup())
                if (BackupDomain.SCALE in selected) applyScale(payload.scale ?: ScaleBackup())
                if (BackupDomain.AI in selected) applyAi(payload.ai ?: AiBackup())
                if (BackupDomain.MUSIC in selected) applyMusic(payload.music ?: MusicBackup())
            }
            payload.music?.let { PlayerController.restorePositions(it.positions) }

            val counts = countsOf(payload, if (BackupDomain.NOTES in selected) notes.map { it.first } else null)
            val warnings = mutableListOf<String>()
            if (BackupDomain.AI in selected) warnings += "AI 凭据未恢复，标记为待补充"
            if (BackupDomain.MUSIC in selected) warnings += "音频缓存未恢复，标记为需重新缓存"
            RestoreResult(selected, counts, warnings)
        } catch (e: Exception) {
            runCatching { rollback(snapshot, selected) }
            throw if (e is BackupException) e else BackupException("恢复失败，已尝试回滚原数据：${e.message ?: "未知错误"}")
        }
    }

    // ------------------------------------------------------------------
    // 校验与派生
    // ------------------------------------------------------------------

    private fun readVerified(input: InputStream, password: CharArray): VerifiedBackup {
        val bytes = try {
            input.readBytes()
        } catch (e: Exception) {
            throw BackupException("读取备份文件失败：${e.message ?: "未知错误"}")
        }
        if (bytes.isEmpty() || bytes.size > MAX_BACKUP_BYTES) {
            throw BackupException("备份文件为空或超过 ${MAX_BACKUP_BYTES} 字节上限")
        }
        val envelope = try {
            json.decodeFromString<BackupEnvelope>(String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF"))
        } catch (e: Exception) {
            throw BackupException("文件不是有效的备份包")
        }
        if (envelope.format != FORMAT) throw BackupException("不是 HandyKit 备份包（format=${envelope.format}）")
        if (envelope.version != VERSION) {
            throw BackupException("备份版本 ${envelope.version} 不受支持（当前支持 $VERSION）")
        }
        if (envelope.domains.isEmpty()) throw BackupException("备份包未声明数据域")
        val kek = VaultCrypto.deriveKek(password, VaultCrypto.unb64(envelope.salt))
        val payloadBytes = try {
            VaultCrypto.decrypt(envelope.payload, kek)
        } catch (e: Exception) {
            throw BackupException("备份口令错误或文件已损坏")
        }
        if (sha256Hex(payloadBytes) != envelope.payloadDigest) {
            throw BackupException("完整性校验失败：文件已损坏或口令错误")
        }
        val payload = try {
            json.decodeFromString<BackupPayload>(String(payloadBytes, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("备份内容无法解析，已中止")
        }
        validateRelations(payload)
        val notes = payload.notesCipher?.let { cipher ->
            val notesKey = deriveNotesKey(password, VaultCrypto.unb64(envelope.notesSalt))
            val plain = try {
                VaultCrypto.decrypt(cipher, notesKey)
            } catch (e: Exception) {
                throw BackupException("笔记域口令错误或密文已损坏")
            }
            if (envelope.notesDigest == null || sha256Hex(plain) != envelope.notesDigest) {
                throw BackupException("笔记域完整性校验失败")
            }
            try {
                json.decodeFromString<List<NoteDto>>(String(plain, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                throw BackupException("笔记域内容无法解析")
            }
        }
        return VerifiedBackup(envelope, payload, notes)
    }

    private fun validateRelations(payload: BackupPayload) {
        val problems = mutableListOf<String>()
        payload.ai?.let { ai ->
            val sessionIds = ai.sessions.map { it.id }.toSet()
            val providerIds = ai.providers.map { it.id }.toSet()
            val roleIds = ai.roles.map { it.id }.toSet()
            ai.sessions.forEach { s ->
                if (s.providerId !in providerIds) problems += "会话 ${s.id} 引用了不存在的服务商"
                if (s.roleId != null && s.roleId !in roleIds) problems += "会话 ${s.id} 引用了不存在的角色"
            }
            ai.messages.forEach { if (it.sessionId !in sessionIds) problems += "消息 ${it.id} 引用了不存在的会话" }
            ai.attachments.forEach { if (it.sessionId !in sessionIds) problems += "附件 ${it.id} 引用了不存在的会话" }
        }
        payload.fuel?.let { fuel ->
            val vehicleIds = fuel.vehicles.map { it.id }.toSet()
            fuel.records.forEach {
                if (it.vehicleId !in vehicleIds) problems += "油耗记录 ${it.id} 引用了不存在的车辆"
                if (runCatching { LocalDate.parse(it.date) }.isFailure) {
                    problems += "油耗记录 ${it.id} 日期非法"
                }
            }
        }
        payload.scale?.let { scale ->
            val memberIds = scale.members.map { it.id }.toSet()
            scale.measurements.forEach {
                if (it.memberId != null && it.memberId !in memberIds) problems += "测量 ${it.id} 引用了不存在的成员"
            }
        }
        payload.music?.let { music ->
            val trackIds = music.tracks.map { it.id }.toSet()
            val playlistIds = music.playlists.map { it.id }.toSet()
            music.links.forEach {
                if (it.trackId !in trackIds) problems += "歌单关联 ${it.id} 引用了不存在的曲目"
                if (it.playlistId !in playlistIds) problems += "歌单关联 ${it.id} 引用了不存在的歌单"
            }
            music.lyrics.forEach { if (it.trackId !in trackIds) problems += "歌词引用了不存在的曲目 ${it.trackId}" }
        }
        if (problems.isNotEmpty()) throw BackupException("关联校验失败：${problems.take(3).joinToString("；")}")
    }

    private fun deriveNotesKey(password: CharArray, salt: ByteArray): javax.crypto.SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update("handykit-notes-backup-v1".toByteArray(StandardCharsets.UTF_8))
        return VaultCrypto.deriveKek(password, digest.digest().copyOf(VaultCrypto.SALT_BYTES))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun countsOf(payload: BackupPayload, notes: List<NoteDto>?): Map<String, Int> = buildMap {
        notes?.let { put(BackupDomain.NOTES.name, it.size) }
        payload.ai?.let {
            put(BackupDomain.AI.name, it.sessions.size + it.messages.size + it.roles.size +
                it.providers.size + it.attachments.size)
        }
        payload.fuel?.let { put(BackupDomain.FUEL.name, it.vehicles.size + it.records.size) }
        payload.scale?.let { put(BackupDomain.SCALE.name, it.members.size + it.measurements.size) }
        payload.music?.let {
            put(BackupDomain.MUSIC.name, it.tracks.size + it.playlists.size + it.links.size + it.lyrics.size)
        }
    }

    private fun trackCacheBytes(paths: List<String>): Long = paths.sumOf { path ->
        if (path.isNotBlank() && File(path).exists()) File(path).length() else 0L
    }

    // ------------------------------------------------------------------
    // 快照与回滚（跨库无法单事务，失败时按快照恢复）
    // ------------------------------------------------------------------

    private data class SessionDek(val key: javax.crypto.SecretKey, val generation: Int)

    private data class Snapshot(
        val notes: List<SecureNoteEntity>? = null,
        val vehicles: List<Vehicle>? = null,
        val records: List<FuelRecord>? = null,
        val members: List<ScaleMember>? = null,
        val measurements: List<Measurement>? = null,
        val sessions: List<AiSession>? = null,
        val messages: List<AiMessage>? = null,
        val roles: List<AiRole>? = null,
        val providers: List<AiProvider>? = null,
        val attachments: List<com.handykit.app.core.db.AiAttachment>? = null,
        val tracks: List<MusicTrack>? = null,
        val playlists: List<MusicPlaylist>? = null,
        val links: List<PlaylistTrack>? = null,
        val lyrics: List<MusicLyric>? = null,
        val positions: Map<String, Long>? = null,
    )

    private suspend fun capture(selected: List<BackupDomain>): Snapshot = Snapshot(
        notes = if (BackupDomain.NOTES in selected) noteDao.all() else null,
        vehicles = if (BackupDomain.FUEL in selected) vehicleDao.all() else null,
        records = if (BackupDomain.FUEL in selected) fuelDao.all() else null,
        members = if (BackupDomain.SCALE in selected) scaleDao.members() else null,
        measurements = if (BackupDomain.SCALE in selected) scaleDao.measurements() else null,
        sessions = if (BackupDomain.AI in selected) aiDao.sessions() else null,
        messages = if (BackupDomain.AI in selected) {
            aiDao.sessions().flatMap { aiDao.messages(it.id) }
        } else null,
        roles = if (BackupDomain.AI in selected) aiDao.roles() else null,
        providers = if (BackupDomain.AI in selected) aiDao.providers() else null,
        attachments = if (BackupDomain.AI in selected) {
            aiDao.sessions().flatMap { aiDao.attachmentsBySession(it.id) }.distinctBy { it.id }
        } else null,
        tracks = if (BackupDomain.MUSIC in selected) musicDao.tracks() else null,
        playlists = if (BackupDomain.MUSIC in selected) musicDao.observePlaylists().first() else null,
        links = if (BackupDomain.MUSIC in selected) {
            musicDao.observePlaylists().first().flatMap { musicDao.playlistTracks(it.id) }
        } else null,
        lyrics = if (BackupDomain.MUSIC in selected) {
            musicDao.tracks().mapNotNull { musicDao.lyric(it.id) }
        } else null,
        positions = if (BackupDomain.MUSIC in selected) PlayerController.snapshotPositions() else null,
    )

    private suspend fun rollback(snapshot: Snapshot, selected: List<BackupDomain>) {
        if (BackupDomain.NOTES in selected && snapshot.notes != null) {
            noteDb.withTransaction {
                noteDao.clearAll()
                snapshot.notes.forEach { noteDao.upsert(it) }
            }
        }
        db.withTransaction {
            if (BackupDomain.FUEL in selected) {
                fuelDao.all().forEach { fuelDao.deleteById(it.id) }
                vehicleDao.all().forEach { vehicleDao.deleteById(it.id) }
                snapshot.vehicles?.forEach { vehicleDao.insert(it) }
                snapshot.records?.forEach { fuelDao.insert(it) }
            }
            if (BackupDomain.SCALE in selected) {
                scaleDao.measurements().forEach { scaleDao.deleteMeasurement(it.id) }
                scaleDao.members().forEach { scaleDao.deleteMember(it) }
                snapshot.members?.forEach { scaleDao.upsertMember(it) }
                snapshot.measurements?.forEach { scaleDao.upsertMeasurement(it) }
            }
            if (BackupDomain.AI in selected) {
                aiDao.sessions().forEach { session ->
                    aiDao.deleteMessages(session.id)
                    aiDao.deleteAttachments(session.id)
                    aiDao.deleteSession(session.id)
                }
                aiDao.roles().forEach { aiDao.deleteRole(it.id) }
                aiDao.providers().forEach { aiDao.deleteProvider(it.id) }
                snapshot.providers?.forEach { aiDao.upsertProvider(it) }
                snapshot.roles?.forEach { aiDao.upsertRole(it) }
                snapshot.sessions?.forEach { aiDao.upsertSession(it) }
                snapshot.messages?.forEach { aiDao.upsertMessage(it) }
                snapshot.attachments?.forEach { aiDao.upsertAttachment(it) }
            }
            if (BackupDomain.MUSIC in selected) {
                clearMusic()
                snapshot.tracks?.forEach { musicDao.upsertTrack(it) }
                snapshot.playlists?.forEach { musicDao.upsertPlaylist(it) }
                snapshot.links?.forEach { musicDao.addPlaylistTrack(it) }
                snapshot.lyrics?.forEach { musicDao.upsertLyric(it) }
            }
        }
        snapshot.positions?.let { PlayerController.restorePositions(it) }
    }

    // ------------------------------------------------------------------
    // 应用各域
    // ------------------------------------------------------------------

    private suspend fun clearMusic() {
        musicDao.observePlaylists().first().forEach { musicDao.clearPlaylist(it.id) }
        musicDao.observePlaylists().first().forEach { musicDao.deletePlaylist(it.id) }
        musicDao.tracks().forEach { track ->
            musicDao.upsertLyric(MusicLyric(track.id, "txt", "", "manual"))
            musicDao.deleteTrack(track.id)
        }
    }

    private suspend fun applyFuel(fuel: FuelBackup) {
        fuelDao.all().forEach { fuelDao.deleteById(it.id) }
        vehicleDao.all().forEach { vehicleDao.deleteById(it.id) }
        fuel.vehicles.forEach { v ->
            vehicleDao.insert(Vehicle(v.id, v.name, v.plate, v.tankCapacity, v.fuelGrade, v.createdAt))
        }
        fuel.records.forEach { r ->
            fuelDao.insert(
                FuelRecord(
                    id = r.id, vehicleId = r.vehicleId, date = LocalDate.parse(r.date),
                    odometer = r.odometer, liters = r.liters, amount = r.amount, price = r.price,
                    isFull = r.isFull, station = r.station, note = r.note,
                    estimated = r.estimated, invalidSegment = r.invalidSegment,
                )
            )
        }
    }

    private suspend fun applyScale(scale: ScaleBackup) {
        scaleDao.measurements().forEach { scaleDao.deleteMeasurement(it.id) }
        scaleDao.members().forEach { scaleDao.deleteMember(it) }
        scale.members.forEach {
            scaleDao.upsertMember(ScaleMember(it.id, it.name, it.sex, it.heightCm, it.birthDateEpochMs, it.referenceWeightKg))
        }
        scale.measurements.forEach {
            scaleDao.upsertMeasurement(
                Measurement(
                    id = it.id, measuredAtEpochMs = it.measuredAtEpochMs, weightKg = it.weightKg,
                    impedanceOhm = it.impedanceOhm, bmi = it.bmi, bodyFatPct = it.bodyFatPct,
                    muscleKg = it.muscleKg, waterPct = it.waterPct, proteinPct = it.proteinPct,
                    boneMassKg = it.boneMassKg, basalMetabolic = it.basalMetabolic,
                    memberId = it.memberId, memberNameSnapshot = it.memberNameSnapshot,
                    source = it.source, algorithmVersion = it.algorithmVersion,
                )
            )
        }
    }

    private suspend fun applyAi(ai: AiBackup) {
        aiDao.sessions().forEach { session ->
            aiDao.deleteMessages(session.id)
            aiDao.deleteAttachments(session.id)
            aiDao.deleteSession(session.id)
        }
        aiDao.roles().forEach { aiDao.deleteRole(it.id) }
        aiDao.providers().forEach { aiDao.deleteProvider(it.id) }
        ai.providers.forEach {
            aiDao.upsertProvider(
                AiProvider(it.id, it.name, it.protocol, it.baseUrl, it.model,
                    it.imageCapability, it.pdfCapability, it.createdAt)
            )
        }
        ai.roles.forEach { aiDao.upsertRole(AiRole(it.id, it.name, it.systemPrompt, it.paramsJson, it.createdAt)) }
        ai.sessions.forEach {
            aiDao.upsertSession(
                AiSession(it.id, it.title, it.roleId, it.rolePromptSnapshot, it.paramsSnapshotJson,
                    it.providerId, it.model, it.createdAt, it.updatedAt, it.contextClearedAt)
            )
        }
        ai.messages.forEach {
            aiDao.upsertMessage(
                AiMessage(it.id, it.sessionId, it.role, it.content, it.status, it.provider, it.model,
                    it.createdAt, it.providerReady, it.errorKind, it.attachmentIdsJson, it.includeInContext)
            )
        }
        ai.attachments.forEach { a ->
            var path = a.localPath
            val bytes = a.bytesBase64?.let { VaultCrypto.unb64(it) }
            if (bytes != null) {
                val safeName = File(a.name).name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val file = File(attachmentDir, "${UUIDHelper.safeId(a.id)}_$safeName")
                file.writeBytes(bytes)
                path = file.absolutePath
            }
            aiDao.upsertAttachment(
                com.handykit.app.core.db.AiAttachment(
                    id = a.id, sessionId = a.sessionId, name = a.name, type = a.type, mime = a.mime,
                    sizeBytes = if (bytes != null) bytes.size.toLong() else a.sizeBytes,
                    localPath = path, status = if (bytes == null) "missing" else a.status,
                    extractedText = a.extractedText, pdfPages = a.pdfPages, createdAt = a.createdAt,
                )
            )
        }
    }

    private suspend fun applyMusic(music: MusicBackup) {
        clearMusic()
        music.tracks.forEach {
            musicDao.upsertTrack(
                MusicTrack(
                    id = it.id, title = it.title, author = it.author, source = it.source,
                    bvid = it.bvid, cid = it.cid, sourceUrl = it.sourceUrl,
                    // 音频缓存不在包内：保留来源元数据，清空本地路径并标记需重新缓存。
                    filePath = "", coverPath = it.coverPath, contentHash = it.contentHash,
                    durationMs = it.durationMs, sizeBytes = 0L, status = "missing", addedAt = it.addedAt,
                )
            )
        }
        music.playlists.forEach { musicDao.upsertPlaylist(MusicPlaylist(it.id, it.name, it.createdAt)) }
        music.links.forEach { musicDao.addPlaylistTrack(PlaylistTrack(it.id, it.playlistId, it.trackId, it.position)) }
        music.lyrics.forEach { musicDao.upsertLyric(MusicLyric(it.trackId, it.format, it.content, it.source)) }
    }

    private object UUIDHelper {
        fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
    }

    companion object {
        const val FORMAT = "handykit-backup"
        const val VERSION = 1
        const val MAX_BACKUP_BYTES = 512L * 1024 * 1024
    }
}
