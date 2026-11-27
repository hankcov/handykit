package com.handykit.app.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "ai_role")
data class AiRole(
    @PrimaryKey val id: String,
    val name: String,
    val systemPrompt: String,
    /** 默认参数 JSON（temperature 等），不伪造统一默认值 */
    val paramsJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ai_provider")
data class AiProvider(
    @PrimaryKey val id: String,
    val name: String,
    /** OpenAI 兼容 / Anthropic / Gemini */
    val protocol: String = "openai",
    val baseUrl: String,
    val model: String,
    /** 图片/PDF/文件上传能力标记，"unknown"/"verified"/"user" */
    val imageCapability: String = "unknown",
    val pdfCapability: String = "unknown",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * 会话。角色快照在新建时固化，后续改角色不影响旧会话。
 * API Key 不存这里，存 Keystore 包裹的凭据区，按 providerId 索引。
 */
@Entity(tableName = "ai_session", indices = [Index("providerId")])
data class AiSession(
    @PrimaryKey val id: String,
    val title: String,
    val roleId: String?,
    /** 新建时的角色系统提示词快照 */
    val rolePromptSnapshot: String,
    val paramsSnapshotJson: String = "{}",
    val providerId: String,
    val model: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 清除上下文的游标：只纳入 updatedAt > 此值的消息 */
    val contextClearedAt: Long = 0L,
)

@Entity(
    tableName = "ai_message",
    foreignKeys = [ForeignKey(
        entity = AiSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class AiMessage(
    @PrimaryKey val id: String,
    val sessionId: String,
    /** user / assistant / system */
    val role: String,
    val content: String,
    /** pending / streaming / done / stopped / failed / interrupted */
    val status: String = "done",
    val provider: String = "",
    val model: String = "",
    val createdAt: Long,
    val providerReady: Boolean = false,
    val errorKind: String? = null,
    /** 该条消息关联附件的 id 列表 JSON */
    val attachmentIdsJson: String = "[]",
    /** 用户是否选择纳入上下文 */
    val includeInContext: Boolean = true,
)

@Entity(
    tableName = "ai_attachment",
    foreignKeys = [ForeignKey(
        entity = AiSession::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class AiAttachment(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    /** image / pdf / txt / markdown */
    val type: String,
    val mime: String,
    val sizeBytes: Long,
    /** 应用私有副本路径 */
    val localPath: String,
    val status: String = "ready",
    /** 本地解析出的文本（TXT/MD/PDF 文本页），或空 */
    val extractedText: String = "",
    val pdfPages: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
