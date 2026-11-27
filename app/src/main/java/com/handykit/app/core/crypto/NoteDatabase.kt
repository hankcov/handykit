package com.handykit.app.core.crypto

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * 独立加密笔记库。所有文本字段均为 AES-GCM 密文（base64），
 * 分类也不落明文（K3/K5）。搜索在内存中解密后过滤，不建明文索引。
 */
@Entity(tableName = "secure_note")
data class SecureNoteEntity(
    @PrimaryKey val id: String,
    val titleCipher: String,
    val bodyCipher: String,
    val categoryCipher: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 密钥封装元信息：盐、迭代、封装后的 DEK。单条记录。 */
@Entity(tableName = "vault_meta")
data class VaultMeta(
    @PrimaryKey val id: Int = 1,
    val salt: String,
    val iterations: Int,
    val wrappedDek: String,
    val version: Int = 1,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SecureNoteDao {
    @Query("SELECT * FROM secure_note ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SecureNoteEntity>>

    @Query("SELECT * FROM secure_note ORDER BY updatedAt DESC")
    suspend fun all(): List<SecureNoteEntity>

    @Query("SELECT * FROM secure_note WHERE id = :id")
    suspend fun get(id: String): SecureNoteEntity?

    @Upsert
    suspend fun upsert(note: SecureNoteEntity)

    @Query("DELETE FROM secure_note WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM secure_note")
    suspend fun clearAll()

    @Query("SELECT * FROM vault_meta WHERE id = 1")
    suspend fun meta(): VaultMeta?

    @Upsert
    suspend fun saveMeta(meta: VaultMeta)
}

@androidx.room.Database(
    entities = [SecureNoteEntity::class, VaultMeta::class],
    version = 1,
    exportSchema = false,
)
abstract class NoteDatabase : RoomDatabase() {
    abstract fun noteDao(): SecureNoteDao

    companion object {
        @Volatile
        private var instance: NoteDatabase? = null

        fun get(context: Context): NoteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NoteDatabase::class.java,
                "handykit_notes.db",
            ).build().also { instance = it }
        }
    }
}
