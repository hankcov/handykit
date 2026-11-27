package com.handykit.app.feature.note

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.handykit.app.core.crypto.NoteDatabase
import com.handykit.app.core.crypto.SecureNoteEntity
import com.handykit.app.core.crypto.VaultCrypto
import com.handykit.app.core.crypto.VaultMeta
import com.handykit.app.core.crypto.VaultSession
import com.handykit.app.core.crypto.VaultSessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

private val Context.noteBiometricPrefs by preferencesDataStore(name = "note_biometric")
private val KEY_BIO_WRAPPED_DEK = stringPreferencesKey("wrapped_dek")
private const val PLACEHOLDER = "（无法解密）"

/** 领域对象：所有文本均为已解密的明文，仅在有效会话内短暂存在。 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val category: String,
    val createdAt: Long,
    val updatedAt: Long,
    val decryptError: Boolean = false,
)

/** 解密失败时只给占位符，绝不把乱码/半明文交给 UI。 */
internal fun noteMatches(note: Note, query: String): Boolean {
    if (note.decryptError) return false
    val q = query.trim()
    if (q.isEmpty()) return true
    return note.title.contains(q, ignoreCase = true) ||
        note.body.contains(q, ignoreCase = true) ||
        note.category.contains(q, ignoreCase = true)
}

/**
 * 独立加密笔记的数据层。
 *
 * K2/K3：独立 Room 库 + 主口令派生 KEK + 随机 DEK；标题/正文/分类全部 AES-GCM 落密文。
 * K6：所有读路径都先检查会话有效性；会话换代后旧流不得再吐出明文。
 * K7：改口令只重封装同一个 DEK，任何一步失败都不触碰 meta 与笔记行。
 * N9：备份导出的是"已加密的密文快照 + 独立备份密钥封装"，不含任何明文。
 */
class NoteRepository private constructor(
    private val db: NoteDatabase,
    private val context: Context,
) {
    constructor(context: Context) : this(
        NoteDatabase.get(context.applicationContext),
        context.applicationContext,
    )

    private val dao = db.noteDao()

    /** 进程内唯一权威会话。锁定时 generation 递增，旧 DEK 引用立即失效。 */
    @Volatile
    var session: VaultSession = VaultSession.locked()
        private set

    fun currentSession(): VaultSession = session

    /** 是否已有保险库元信息（Room 查询是 suspend，因此本方法也 suspend）。 */
    suspend fun isInitialized(): Boolean = withContext(Dispatchers.IO) {
        dao.meta() != null
    }

    /** 首次设置主口令。调用方（UI）必须已完成二次确认一致性校验。 */
    suspend fun setup(password: CharArray) = withContext(Dispatchers.IO) {
        require(dao.meta() == null) { "安全笔记已初始化，不能重复设置" }
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
        val kek = VaultCrypto.deriveKek(password, salt)
        val dek = VaultCrypto.newDek()
        dao.saveMeta(
            VaultMeta(
                salt = VaultCrypto.b64(salt),
                iterations = VaultCrypto.PBKDF2_ITERATIONS,
                wrappedDek = VaultCrypto.wrapDek(dek, kek),
            )
        )
    }

    /** 主口令解锁。任何认证失败都收敛为 false，异常不外泄到 UI。 */
    suspend fun unlock(password: CharArray): Boolean = withContext(Dispatchers.IO) {
        val meta = dao.meta() ?: return@withContext false
        try {
            val kek = VaultCrypto.deriveKek(password, VaultCrypto.unb64(meta.salt), meta.iterations)
            val dek = VaultCrypto.unwrapDek(meta.wrappedDek, kek)
            session = VaultSession.unlocked(dek)
            VaultSessionHolder.publish(session)
            true
        } catch (t: Throwable) {
            session.lock()
            false
        }
    }

    /**
     * 改口令：旧口令验证通过后，仅用新 KEK 重封装同一个 DEK。
     * 校验失败时直接返回 false，meta 与全部笔记行保持原样（K7）。
     */
    suspend fun changePassword(old: CharArray, new: CharArray): Boolean = withContext(Dispatchers.IO) {
        val meta = dao.meta() ?: return@withContext false
        val dek = try {
            val oldKek = VaultCrypto.deriveKek(old, VaultCrypto.unb64(meta.salt), meta.iterations)
            VaultCrypto.unwrapDek(meta.wrappedDek, oldKek)
        } catch (t: Throwable) {
            return@withContext false
        }
        val salt = VaultCrypto.randomBytes(VaultCrypto.SALT_BYTES)
        val newKek = VaultCrypto.deriveKek(new, salt, meta.iterations)
        val newWrapped = try {
            VaultCrypto.wrapDek(dek, newKek)
        } catch (t: Throwable) {
            return@withContext false
        }
        dao.saveMeta(
            meta.copy(
                salt = VaultCrypto.b64(salt),
                wrappedDek = newWrapped,
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    /** 锁定：只递增 generation 并丢弃 DEK 引用，密文数据不受影响。 */
    fun lock() {
        session.lock()
        VaultSessionHolder.clear()
    }

    /**
     * 观察笔记。每条记录解密前检查会话；锁定后发空列表而不是迟到明文。
     * 单条认证失败只发占位 Note(decryptError=true)，不显示乱码。
     */
    fun notes(session: VaultSession): Flow<List<Note>> = dao.observeAll().map { entities ->
        if (!session.isValid) {
            emptyList()
        } else {
            val gen = session.currentGeneration()
            entities.mapNotNull { decryptEntity(it, session, gen) }
        }
    }

    /** 内存过滤：读取密文、解密后匹配，不落任何明文索引。 */
    suspend fun search(session: VaultSession, query: String): List<Note> = withContext(Dispatchers.IO) {
        if (!session.isValid) return@withContext emptyList()
        val gen = session.currentGeneration()
        dao.all().mapNotNull { decryptEntity(it, session, gen) }.filter { noteMatches(it, query) }
    }

    /** 正文按需解密：详情页点「显示正文」时才调用，列表流不负责正文展示。 */
    suspend fun revealBody(session: VaultSession, id: String): String? = withContext(Dispatchers.IO) {
        if (!session.isValid) return@withContext null
        val gen = session.currentGeneration()
        val key = try {
            session.requireDek(gen)
        } catch (t: IllegalStateException) {
            return@withContext null
        }
        val entity = dao.get(id) ?: return@withContext null
        try {
            VaultCrypto.decrypt(entity.bodyCipher, key).toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    /** 新增或编辑。编辑时保留 createdAt；id 为空则新建。返回最终 id。 */
    suspend fun upsert(
        session: VaultSession,
        title: String,
        body: String,
        category: String,
        id: String? = null,
    ): String = withContext(Dispatchers.IO) {
        require(session.isValid) { "笔记已锁定" }
        val gen = session.currentGeneration()
        val key = session.requireDek(gen)
        val now = System.currentTimeMillis()
        val existing = if (id == null) null else dao.get(id)
        val entity = SecureNoteEntity(
            id = id ?: UUID.randomUUID().toString(),
            titleCipher = VaultCrypto.encrypt(title.toByteArray(Charsets.UTF_8), key),
            bodyCipher = VaultCrypto.encrypt(body.toByteArray(Charsets.UTF_8), key),
            categoryCipher = VaultCrypto.encrypt(category.toByteArray(Charsets.UTF_8), key),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entity)
        entity.id
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
    }

    /** 已有分类列表（从解密后的记录中提取，不落明文索引）。 */
    suspend fun allCategories(session: VaultSession): Set<String> = withContext(Dispatchers.IO) {
        if (!session.isValid) return@withContext emptySet()
        val gen = session.currentGeneration()
        dao.all().mapNotNull { decryptEntity(it, session, gen) }
            .filterNot { it.decryptError }
            .map { it.category }
            .filter { it.isNotBlank() && it != PLACEHOLDER }
            .toSet()
    }

    /** 是否已启用生物识别解锁（由 DataStore 中是否存在封装 DEK 决定）。 */
    val biometricEnabled: Flow<Boolean> = context.noteBiometricPrefs.data.map { prefs ->
        prefs.contains(KEY_BIO_WRAPPED_DEK)
    }

    /**
     * 用带用户认证约束的 Keystore 密钥再封装一份 DEK。
     * 重要：调用前必须由 UI 完成生物识别/设备凭据认证（success 回调里调用），
     * 否则 Keystore 会拒绝密钥使用。
     */
    suspend fun enableBiometric(session: VaultSession): Boolean = withContext(Dispatchers.IO) {
        if (!session.isValid) return@withContext false
        val gen = session.currentGeneration()
        val dek = try {
            session.requireDek(gen)
        } catch (t: IllegalStateException) {
            return@withContext false
        }
        try {
            val biometricKey = VaultCrypto.biometricKek("note")
            val wrapped = VaultCrypto.wrapWithKeystore(dek.encoded, biometricKey)
            context.noteBiometricPrefs.edit { prefs ->
                prefs[KEY_BIO_WRAPPED_DEK] = wrapped
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 用认证后的 Keystore 密钥解封 DEK 并建立会话。
     * cipher 参数用于承接 BiometricPrompt.CryptoObject 的 Cipher；
     * 当前 biometricKek 使用 30 秒有效期（非单次操作绑定），
     * 因此紧随认证之后用 VaultCrypto 的 GCM 解封即可，无需该 Cipher 参与。
     */
    suspend fun unlockBiometric(cipher: javax.crypto.Cipher? = null): Boolean =
        withContext(Dispatchers.IO) {
            cipher // 保留参数以对接 CryptoObject；实际解封走下面的 Keystore 路径
            val payload = context.noteBiometricPrefs.data.first()[KEY_BIO_WRAPPED_DEK]
            if (payload.isNullOrBlank()) return@withContext false
            try {
                val biometricKey = VaultCrypto.biometricKek("note")
                val dekBytes = VaultCrypto.unwrapWithKeystore(payload, biometricKey)
                require(dekBytes.size == 32) { "DEK 长度非法" }
                session = VaultSession.unlocked(javax.crypto.spec.SecretKeySpec(dekBytes, "AES"))
                VaultSessionHolder.publish(session)
                true
            } catch (t: Throwable) {
                session.lock()
                false
            }
        }

    /** 关闭生物识别入口，只清掉封装体；不影响主口令与笔记数据。 */
    suspend fun disableBiometric() = withContext(Dispatchers.IO) {
        context.noteBiometricPrefs.edit { prefs ->
            prefs.remove(KEY_BIO_WRAPPED_DEK)
        }
    }

    private suspend fun clearBiometricEnrollmentInternal() {
        context.noteBiometricPrefs.edit { prefs ->
            prefs.remove(KEY_BIO_WRAPPED_DEK)
        }
    }

    /** 导出独立加密备份：密文快照用设备绑定备份密钥封装，绝不出现明文。 */
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        val meta = dao.meta() ?: error("安全笔记尚未初始化")
        val lines = buildList<String> {
            add("HKNOTE1")
            add(meta.salt)
            add(meta.iterations.toString())
            add(meta.wrappedDek)
            dao.all().forEach { n ->
                add(
                    listOf(
                        n.id,
                        n.titleCipher,
                        n.bodyCipher,
                        n.categoryCipher,
                        n.createdAt.toString(),
                        n.updatedAt.toString(),
                    ).joinToString("\t")
                )
            }
        }
        val snapshot = lines.joinToString("\n")
        VaultCrypto.wrapWithKeystore(
            snapshot.toByteArray(Charsets.UTF_8),
            VaultCrypto.backupKey("note"),
        )
    }

    /** 恢复备份：校验格式后原子替换 meta 与笔记行，并立即锁定（主口令可能不同）。 */
    suspend fun importBackup(payload: String): Int = withContext(Dispatchers.IO) {
        val raw = try {
            VaultCrypto.unwrapWithKeystore(payload, VaultCrypto.backupKey("note"))
                .toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            error("备份无法解密或已损坏")
        }
        val lines = raw.lines()
        require(lines.firstOrNull() == "HKNOTE1") { "备份格式不支持" }
        require(lines.size >= 4) { "备份内容不完整" }
        val meta = VaultMeta(
            salt = lines[1],
            iterations = lines[2].toIntOrNull() ?: error("备份迭代次数非法"),
            wrappedDek = lines[3],
            updatedAt = System.currentTimeMillis(),
        )
        val notes = lines.drop(4).filter { it.isNotBlank() }.map { line ->
            val p = line.split("\t")
            require(p.size == 6) { "备份笔记记录非法" }
            SecureNoteEntity(
                id = p[0],
                titleCipher = p[1],
                bodyCipher = p[2],
                categoryCipher = p[3],
                createdAt = p[4].toLongOrNull() ?: error("备份时间非法"),
                updatedAt = p[5].toLongOrNull() ?: error("备份时间非法"),
            )
        }
        db.withTransaction {
            dao.clearAll()
            dao.saveMeta(meta)
            notes.forEach { dao.upsert(it) }
        }
        clearBiometricEnrollmentInternal()
        session.lock()
        notes.size
    }

    private fun decryptEntity(
        entity: SecureNoteEntity,
        session: VaultSession,
        generation: Int,
    ): Note? {
        if (!session.isValid) return null
        val key = try {
            session.requireDek(generation)
        } catch (t: IllegalStateException) {
            return null
        }
        return try {
            Note(
                id = entity.id,
                title = VaultCrypto.decrypt(entity.titleCipher, key).toString(Charsets.UTF_8),
                body = VaultCrypto.decrypt(entity.bodyCipher, key).toString(Charsets.UTF_8),
                category = VaultCrypto.decrypt(entity.categoryCipher, key).toString(Charsets.UTF_8),
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
        } catch (t: Throwable) {
            Note(
                id = entity.id,
                title = PLACEHOLDER,
                body = "",
                category = "",
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                decryptError = true,
            )
        }
    }
}
