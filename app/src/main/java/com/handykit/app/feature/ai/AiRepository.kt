package com.handykit.app.feature.ai

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.handykit.app.core.crypto.VaultCrypto
import com.handykit.app.core.db.AiAttachment
import com.handykit.app.core.db.AiDao
import com.handykit.app.core.db.AiMessage
import com.handykit.app.core.db.AiProvider
import com.handykit.app.core.db.AiRole
import com.handykit.app.core.db.AiSession
import com.handykit.app.core.db.AppDatabase
import com.handykit.app.core.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.util.UUID

private data class PreparedChat(
    val chat: ChatRequest,
    val request: Request,
)

/** 会话/角色/服务商 CRUD + 请求构建 + 网络发送。所有 IO 在 Dispatchers.IO。 */
class AiRepository(val context: Context) {

    private val appContext = context.applicationContext
    private val dao: AiDao = AppDatabase.get(appContext).aiDao()
    private val credentials = CredentialStore(appContext)
    private val prefs = AppPrefs(appContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val protocol = OpenAiCompatibleProtocol(OpenAiCompatibleProtocol.defaultClient(), json)

    // ---- 会话 ----

    fun observeSessions(): Flow<List<AiSession>> = dao.observeSessions()

    suspend fun sessions(): List<AiSession> = withContext(Dispatchers.IO) { dao.sessions() }

    suspend fun session(id: String): AiSession? = withContext(Dispatchers.IO) { dao.session(id) }

    suspend fun upsertSession(session: AiSession) = withContext(Dispatchers.IO) {
        dao.upsertSession(session)
    }

    suspend fun deleteSession(id: String) = withContext(Dispatchers.IO) {
        dao.deleteMessages(id)
        dao.deleteAttachments(id)
        dao.deleteSession(id)
        AttachmentCodec.deleteSessionFiles(appContext, id)
    }

    fun createSession(role: AiRole?, provider: AiProvider, title: String = "新会话"): AiSession {
        val now = System.currentTimeMillis()
        return AiSession(
            id = UUID.randomUUID().toString(),
            title = title,
            roleId = role?.id,
            rolePromptSnapshot = role?.systemPrompt.orEmpty(),
            paramsSnapshotJson = role?.paramsJson?.ifBlank { "{}" } ?: "{}",
            providerId = provider.id,
            model = provider.model,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 进程被杀时，残留的 pending/streaming 消息标记为 interrupted。 */
    suspend fun markUnfinishedAsInterrupted() = withContext(Dispatchers.IO) {
        dao.sessions().forEach { session ->
            dao.messages(session.id)
                .filter { it.status == "pending" || it.status == "streaming" }
                .forEach { msg -> dao.upsertMessage(msg.copy(status = "interrupted")) }
        }
    }

    // ---- 消息 ----

    fun observeMessages(sessionId: String): Flow<List<AiMessage>> = dao.observeMessages(sessionId)

    suspend fun messages(sessionId: String): List<AiMessage> =
        withContext(Dispatchers.IO) { dao.messages(sessionId) }

    suspend fun upsertMessage(message: AiMessage) = withContext(Dispatchers.IO) {
        dao.upsertMessage(message)
        dao.session(message.sessionId)?.let { session ->
            dao.upsertSession(session.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun clearContext(sessionId: String) = withContext(Dispatchers.IO) {
        val session = dao.session(sessionId) ?: return@withContext
        val now = System.currentTimeMillis()
        // 只移动游标，历史消息仍完整保留。
        dao.upsertSession(session.copy(contextClearedAt = now, updatedAt = now))
    }

    // ---- 角色 ----

    fun observeRoles(): Flow<List<AiRole>> = dao.observeRoles()

    suspend fun roles(): List<AiRole> = withContext(Dispatchers.IO) { dao.roles() }

    suspend fun upsertRole(role: AiRole) = withContext(Dispatchers.IO) { dao.upsertRole(role) }

    suspend fun deleteRole(id: String) = withContext(Dispatchers.IO) { dao.deleteRole(id) }

    // ---- 服务商 ----

    fun observeProviders(): Flow<List<AiProvider>> = dao.observeProviders()

    suspend fun providers(): List<AiProvider> = withContext(Dispatchers.IO) { dao.providers() }

    suspend fun provider(id: String): AiProvider? = withContext(Dispatchers.IO) { dao.provider(id) }

    suspend fun upsertProvider(provider: AiProvider) = withContext(Dispatchers.IO) {
        dao.upsertProvider(provider)
    }

    suspend fun deleteProvider(id: String) = withContext(Dispatchers.IO) {
        dao.deleteProvider(id)
        credentials.delete(id)
    }

    // ---- 凭据 / 设置（只暴露必要能力，不提供明文导出） ----

    fun firstUseSeen(): Flow<Boolean> = credentials.firstUseSeen

    suspend fun setFirstUseSeen() = credentials.setFirstUseSeen()

    fun networkEnabled(): Flow<Boolean> = prefs.aiNetworkEnabled

    suspend fun setNetworkEnabled(enabled: Boolean) = prefs.setAiNetwork(enabled)

    suspend fun hasApiKey(providerId: String): Boolean = credentials.hasApiKey(providerId)

    suspend fun saveApiKey(providerId: String, apiKey: String) =
        credentials.saveApiKey(providerId, apiKey)

    suspend fun saveHeaders(providerId: String, headers: List<AiHeaderEntry>) =
        credentials.saveHeaders(providerId, headers)

    suspend fun revealApiKey(providerId: String): String = credentials.revealApiKey(providerId)

    suspend fun revealHeaders(providerId: String): List<AiHeaderEntry> =
        credentials.revealHeaders(providerId)

    fun revealGate(activity: Activity): RevealGate = credentials.revealGate(activity)

    // ---- 附件 ----

    suspend fun attachments(sessionId: String): List<AiAttachment> =
        withContext(Dispatchers.IO) { dao.attachmentsBySession(sessionId) }

    suspend fun attachments(ids: List<String>): List<AiAttachment> =
        withContext(Dispatchers.IO) { if (ids.isEmpty()) emptyList() else dao.attachments(ids) }

    suspend fun prepareAttachment(
        uri: Uri,
        sessionId: String,
        existingCount: Int,
    ): PreparedAttachment = AttachmentCodec.prepare(uri, appContext, sessionId, existingCount)

    suspend fun saveAttachment(
        prepared: PreparedAttachment,
        sessionId: String,
    ): AiAttachment = withContext(Dispatchers.IO) {
        val entity = AiAttachment(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            name = prepared.name,
            type = prepared.type,
            mime = prepared.mime,
            sizeBytes = prepared.sizeBytes,
            localPath = prepared.localPath,
            status = "ready",
            extractedText = prepared.extractedText,
            pdfPages = prepared.pdfPages,
        )
        dao.upsertAttachment(entity)
        entity
    }

    /** DAO 没有单条删除，先整会话重建再删文件。 */
    suspend fun deleteAttachment(sessionId: String, attachmentId: String) =
        withContext(Dispatchers.IO) {
            val all = dao.attachmentsBySession(sessionId)
            val target = all.firstOrNull { it.id == attachmentId } ?: return@withContext
            dao.deleteAttachments(sessionId)
            all.filter { it.id != attachmentId }.forEach { dao.upsertAttachment(it) }
            AttachmentCodec.deleteFile(target.localPath)
        }

    // ---- 发送前校验 ----

    /** 返回给用户的错误提示；null 表示可以发送。不返回任何密钥信息。 */
    suspend fun validateForSend(sessionId: String): String? = withContext(Dispatchers.IO) {
        if (!prefs.aiNetworkEnabled.first()) {
            return@withContext "联网已关闭：请在设置中开启后再发送，本地功能不受影响。"
        }
        val session = dao.session(sessionId) ?: return@withContext "会话不存在，请新建会话。"
        val provider = dao.provider(session.providerId)
            ?: return@withContext "服务商配置已不存在，请重新配置。"
        val protocolKind = AiProtocol.from(provider.protocol)
        if (!protocolKind.implemented) {
            return@withContext when (protocolKind) {
                AiProtocol.Anthropic -> "Anthropic 协议一期未实现，请选择 OpenAI 兼容服务商。"
                AiProtocol.Gemini -> "Gemini 协议一期未实现，请选择 OpenAI 兼容服务商。"
                else -> "该协议一期未实现。"
            }
        }
        runCatching { requireHttpsBaseUrl(provider.baseUrl) }.exceptionOrNull()?.let { ex ->
            return@withContext ex.message ?: "Base URL 非法。"
        }
        if (provider.model.isBlank()) return@withContext "服务商未配置模型。"
        if (credentials.apiKeyForRequest(provider.id).isBlank()) {
            return@withContext "服务商未配置 API Key。"
        }
        null
    }

    // ---- 请求构建与发送 ----

    /**
     * system = 角色提示词快照；随后是用户筛选后的历史 + 当前输入 + 附件内容。
     * 清除上下文只影响历史筛选，不删消息。
     */
    private suspend fun prepareChat(
        session: AiSession,
        input: String,
        history: List<AiMessage>,
        attachmentIds: List<String>,
        stream: Boolean,
    ): PreparedChat = withContext(Dispatchers.IO) {
        val provider = dao.provider(session.providerId)
            ?: throw AiException(AiErrorKind.VALIDATION, "服务商配置已不存在")
        val protocolKind = AiProtocol.from(provider.protocol)
        if (!protocolKind.implemented) {
            throw AiException(AiErrorKind.UNSUPPORTED, "协议 ${protocolKind.id} 一期未实现")
        }
        val baseUrl = requireHttpsBaseUrl(provider.baseUrl)
        if (provider.model.isBlank()) {
            throw AiException(AiErrorKind.VALIDATION, "服务商未配置模型")
        }
        val apiKey = credentials.apiKeyForRequest(provider.id)
        if (apiKey.isBlank()) throw AiException(AiErrorKind.VALIDATION, "服务商未配置 API Key")

        val messages = mutableListOf<ChatMsg>()
        if (session.rolePromptSnapshot.isNotBlank()) {
            messages += ChatMsg("system", JsonPrimitive(session.rolePromptSnapshot))
        }
        history.forEach { msg ->
            if (!msg.includeInContext || msg.createdAt <= session.contextClearedAt) return@forEach
            when (msg.role) {
                "user" -> messages += ChatMsg("user", contentFor(msg.content, idsOf(msg)))
                "assistant" -> if (msg.content.isNotBlank()) {
                    messages += ChatMsg("assistant", JsonPrimitive(msg.content))
                }
            }
        }
        messages += ChatMsg("user", contentFor(input, attachmentIds))

        val chat = ChatRequest(
            messages = messages,
            model = provider.model,
            stream = stream,
            params = parseParams(session.paramsSnapshotJson),
        )
        val request = protocol.buildRequest(
            baseUrl = baseUrl,
            apiKey = apiKey,
            customHeaders = credentials.headersForRequest(provider.id),
            chat = chat,
        )
        PreparedChat(chat, request)
    }

    /** 非流式入口（UI 一期默认用流式，此处保留完整能力）。 */
    suspend fun complete(
        session: AiSession,
        input: String,
        history: List<AiMessage>,
        attachmentIds: List<String>,
    ): String = protocol.complete(prepareChat(session, input, history, attachmentIds, false).request)

    /** 流式入口：onCall 交出 Call 供停止生成；onDelta 每帧回调，不写库。 */
    suspend fun stream(
        session: AiSession,
        input: String,
        history: List<AiMessage>,
        attachmentIds: List<String>,
        onCall: (Call) -> Unit,
        onDelta: (String) -> Unit,
    ): StreamOutcome = protocol.stream(
        prepareChat(session, input, history, attachmentIds, true).request,
        onCall,
        onDelta,
    )

    /** 发送前给用户看的上下文范围，不发送任何请求。 */
    suspend fun contextPreview(
        session: AiSession,
        input: String,
        attachmentIds: List<String>,
    ): List<String> = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        if (session.rolePromptSnapshot.isNotBlank()) {
            lines += "系统角色快照：${session.rolePromptSnapshot.take(80).replace('\n', ' ')}"
        } else {
            lines += "系统角色快照：无"
        }
        val included = dao.messages(session.id).filter {
            it.includeInContext && it.createdAt > session.contextClearedAt && it.status == "done"
        }
        lines += "历史消息：${included.size} 条（已排除清除上下文前的消息与标记为不纳入的消息）"
        if (input.isNotBlank()) lines += "当前输入：${input.take(80).replace('\n', ' ')}"
        if (attachmentIds.isNotEmpty()) {
            val names = dao.attachments(attachmentIds).map { it.name }
            lines += "附件：${names.joinToString("、")}"
        }
        val provider = dao.provider(session.providerId)
        lines += "接收方：${provider?.name ?: "未知"} · ${provider?.baseUrl ?: "未配置"} · ${session.model}"
        lines += "不会自动包含安全笔记内容；附件仅在本次请求中发送。"
        lines
    }

    private suspend fun contentFor(text: String, attachmentIds: List<String>): JsonElement {
        if (attachmentIds.isEmpty()) return JsonPrimitive(text)
        val attachments = dao.attachments(attachmentIds)
        if (attachments.isEmpty()) return JsonPrimitive(text)
        val parts = mutableListOf<JsonElement>()
        if (text.isNotBlank()) parts += textPart(text)
        attachments.forEach { attachment ->
            when (attachment.type) {
                "image" -> {
                    val bytes = runCatching { File(attachment.localPath).readBytes() }.getOrDefault(byteArrayOf())
                    if (bytes.isNotEmpty()) {
                        val mime = attachment.mime.ifBlank { "image/jpeg" }
                        parts += imagePart("data:$mime;base64,${VaultCrypto.b64(bytes)}")
                    }
                }

                "txt", "markdown" -> parts += textPart(
                    "【附件：${attachment.name}】\n${attachment.extractedText.take(12_000)}",
                )

                "pdf" -> parts += textPart(
                    "【附件：${attachment.name}（PDF，共 ${attachment.pdfPages} 页；本地无法提取文本，需原生 PDF 能力，请勿假定已理解正文）】",
                )
            }
        }
        return JsonArray(parts)
    }

    private fun textPart(text: String): JsonElement = JsonObject(
        mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)),
    )

    private fun imagePart(dataUrl: String): JsonElement = JsonObject(
        mapOf(
            "type" to JsonPrimitive("image_url"),
            "image_url" to JsonObject(mapOf("url" to JsonPrimitive(dataUrl))),
        ),
    )

    private fun idsOf(message: AiMessage): List<String> = runCatching {
        ID_JSON.decodeFromString<List<String>>(message.attachmentIdsJson)
    }.getOrDefault(emptyList())

    private fun parseParams(raw: String): Map<String, JsonElement> = runCatching {
        val element = json.parseToJsonElement(raw.ifBlank { "{}" })
        (element as? JsonObject)?.toMap()?.filterKeys { key ->
            key !in setOf("model", "stream", "messages")
        } ?: emptyMap()
    }.getOrDefault(emptyMap())

    companion object {
        private val ID_JSON = Json { ignoreUnknownKeys = true }

        fun encodeIds(ids: List<String>): String = ID_JSON.encodeToString(ids)

        fun decodeIds(raw: String): List<String> =
            runCatching { ID_JSON.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }
}
