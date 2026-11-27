package com.handykit.app.feature.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** 一期只实现 OpenAI 兼容协议；其余分支明确标注未实现，绝不伪装成可用。 */
sealed interface AiProtocol {
    val id: String
    val implemented: Boolean

    data object OpenAICompatible : AiProtocol {
        override val id = "openai"
        override val implemented = true
    }

    /** 未实现：一期不构造 Anthropic 请求，调用会被前置校验拒绝。 */
    data object Anthropic : AiProtocol {
        override val id = "anthropic"
        override val implemented = false
    }

    /** 未实现：一期不构造 Gemini 请求，调用会被前置校验拒绝。 */
    data object Gemini : AiProtocol {
        override val id = "gemini"
        override val implemented = false
    }

    companion object {
        fun from(id: String?): AiProtocol = when (id?.trim()?.lowercase()) {
            OpenAICompatible.id -> OpenAICompatible
            Anthropic.id -> Anthropic
            Gemini.id -> Gemini
            else -> OpenAICompatible
        }
    }
}

enum class AiErrorKind(val label: String) {
    AUTH("认证失败：API Key 无效或已过期"),
    RATE_LIMIT("请求过于频繁或额度不足"),
    MODEL("模型或端点不可用"),
    NETWORK("网络错误，请检查连接与 Base URL"),
    TIMEOUT("请求超时"),
    FORMAT("服务商返回格式无法解析"),
    DISABLED("联网已关闭"),
    VALIDATION("配置不完整"),
    UNSUPPORTED("该协议一期未实现"),
    CANCELLED("已停止生成"),
    UNKNOWN("未知错误"),
    ;

    companion object {
        fun from(value: String?): AiErrorKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

class AiException(val kind: AiErrorKind, message: String) : Exception(message) {
    val userMessage: String get() = kind.label
}

data class ChatMsg(val role: String, val content: JsonElement)

data class ChatRequest(
    val messages: List<ChatMsg>,
    val model: String,
    val stream: Boolean,
    val params: Map<String, JsonElement> = emptyMap(),
)

data class StreamOutcome(val text: String, val interrupted: Boolean)

/** 只接受 https://；OkHttp 默认校验证书与主机名，不做任何信任绕过。 */
fun requireHttpsBaseUrl(raw: String): String {
    val base = raw.trim().trimEnd('/')
    if (!base.startsWith("https://", ignoreCase = true)) {
        throw AiException(AiErrorKind.VALIDATION, "仅支持 HTTPS 接口，已拒绝非 https:// 的 Base URL")
    }
    return base
}

/**
 * OpenAI 兼容 chat/completions 协议。
 *
 * - content 为纯字符串或 parts 数组（text + data:image/...;base64）。
 * - stream=true 时按 SSE 逐行解析 `data: {...}`，取 choices[0].delta.content，[DONE] 结束。
 */
class OpenAiCompatibleProtocol(
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun buildRequest(
        baseUrl: String,
        apiKey: String,
        customHeaders: Map<String, String>,
        chat: ChatRequest,
    ): Request {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["model"] = JsonPrimitive(chat.model)
        fields["stream"] = JsonPrimitive(chat.stream)
        fields["messages"] = JsonArray(
            chat.messages.map { msg ->
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive(msg.role),
                        "content" to msg.content,
                    ),
                )
            },
        )
        // 参数不能覆盖协议关键字段，避免把 stream 关掉或替换 messages。
        chat.params.forEach { (k, v) ->
            if (k !in RESERVED_FIELDS) fields[k] = v
        }
        val body = JsonObject(fields).toString()
            .toRequestBody(JSON_MEDIA.toMediaType())

        val builder = Request.Builder()
            .url("$baseUrl/chat/completions")
            .post(body)
            .header("Content-Type", "application/json")
            .header("Accept", if (chat.stream) "text/event-stream" else "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        customHeaders.forEach { (k, v) ->
            // 再次过滤，防止绕过 CredentialStore 的校验。
            if (k.isNotBlank() && k.lowercase() !in FORBIDDEN_HEADERS) builder.header(k, v)
        }
        return builder.build()
    }

    /** 非流式：一次取回 choices[0].message.content。 */
    suspend fun complete(request: Request): String = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) throw httpFailure(resp.code)
                val raw = resp.body?.string() ?: throw AiException(AiErrorKind.FORMAT, "响应为空")
                val root = parseJson(raw)
                val element = root["error"]?.let { throw errorFailure(it) }
                    ?: extractContent(root, deltaMode = false)
                element
            }
        } catch (e: AiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiException(AiErrorKind.TIMEOUT, "请求超时")
        } catch (e: IOException) {
            if (call.isCanceled()) throw AiException(AiErrorKind.CANCELLED, "请求已取消")
            throw AiException(AiErrorKind.NETWORK, "网络请求失败")
        } catch (e: SerializationException) {
            throw AiException(AiErrorKind.FORMAT, "服务商返回不是合法 JSON")
        }
    }

    /**
     * 流式：OkHttp execute() 阻塞读取 source，逐行解析 SSE。
     * 需要通过 [onCall] 把 Call 交给上层，停止生成时调用 Call.cancel()；
     * 取消导致的 IOException 转换成 interrupted=true，不视为失败。
     */
    suspend fun stream(
        request: Request,
        onCall: (Call) -> Unit,
        onDelta: (String) -> Unit,
    ): StreamOutcome = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        onCall(call)
        val sb = StringBuilder()
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) throw httpFailure(resp.code)
                val source = resp.body?.source()
                    ?: throw AiException(AiErrorKind.FORMAT, "流式响应为空")
                var finished = false
                while (!finished && !source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        finished = true
                        break
                    }
                    val frame = try {
                        parseJson(payload)
                    } catch (_: SerializationException) {
                        // 单帧坏数据（心跳/注释等）跳过，不打断整条流。
                        continue
                    }
                    frame["error"]?.let { throw errorFailure(it) }
                    val delta = extractContent(frame, deltaMode = true)
                    if (delta.isNotEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
            }
            StreamOutcome(sb.toString(), false)
        } catch (e: AiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiException(AiErrorKind.TIMEOUT, "请求超时")
        } catch (e: IOException) {
            if (call.isCanceled()) {
                StreamOutcome(sb.toString(), true)
            } else {
                throw AiException(AiErrorKind.NETWORK, "网络请求失败")
            }
        } catch (e: SerializationException) {
            throw AiException(AiErrorKind.FORMAT, "服务商返回不是合法 JSON")
        }
    }

    private fun parseJson(raw: String): JsonObject =
        json.parseToJsonElement(raw) as? JsonObject
            ?: throw AiException(AiErrorKind.FORMAT, "服务商返回结构不符合预期")

    private fun extractContent(root: JsonObject, deltaMode: Boolean): String {
        val choices = root["choices"] as? JsonArray ?: return ""
        val first = choices.firstOrNull() as? JsonObject ?: return ""
        val container = if (deltaMode) first["delta"] else first["message"]
        return textOf(container as? JsonObject ?: return "")
    }

    private fun textOf(obj: JsonObject): String = when (val content = obj["content"]) {
        is JsonPrimitive -> content.content
        is JsonArray -> content.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> part.content
                is JsonObject -> part["text"]?.let { textOf(JsonObject(mapOf("content" to it))) } ?: ""
                else -> ""
            }
        }
        else -> ""
    }

    private fun httpFailure(code: Int): AiException = when {
        code == 401 || code == 403 -> AiException(AiErrorKind.AUTH, "认证失败")
        code == 429 -> AiException(AiErrorKind.RATE_LIMIT, "请求过于频繁")
        code == 404 || code == 400 -> AiException(AiErrorKind.MODEL, "模型名或接口路径不正确")
        code in 500..599 -> AiException(AiErrorKind.NETWORK, "服务商服务端错误")
        else -> AiException(AiErrorKind.FORMAT, "服务商返回 HTTP $code")
    }

    /** 顶层 error 对象：不把服务商原文回显给 UI，只按关键字段粗分类。 */
    private fun errorFailure(error: JsonElement): AiException {
        val obj = error as? JsonObject ?: return AiException(AiErrorKind.FORMAT, "服务商返回错误")
        val raw = obj.values.joinToString(" ") {
            if (it is JsonPrimitive) it.content else ""
        }.lowercase()
        val kind = when {
            "auth" in raw || "api key" in raw || "unauthorized" in raw -> AiErrorKind.AUTH
            "rate" in raw || "quota" in raw -> AiErrorKind.RATE_LIMIT
            "model" in raw -> AiErrorKind.MODEL
            else -> AiErrorKind.FORMAT
        }
        return AiException(kind, kind.label)
    }

    companion object {
        private const val JSON_MEDIA = "application/json; charset=utf-8"
        private val RESERVED_FIELDS = setOf("model", "stream", "messages")
        private val FORBIDDEN_HEADERS = setOf(
            "authorization", "content-type", "host", "content-length", "connection",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 流式响应间隔可能较长，读超时给足；整体不限时，由 Call.cancel() 控制。
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
