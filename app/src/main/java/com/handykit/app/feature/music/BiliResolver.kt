package com.handykit.app.feature.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** B 站视频元数据一期只取公开 view 接口实际返回的字段，不补全不伪造。 */
data class BiliVideoInfo(
    val bvid: String,
    val title: String,
    val author: String,
    val coverUrl: String,
    val sourceUrl: String,
    /** view 接口返回的视频总时长（秒），0 表示接口未给。 */
    val durationSec: Int = 0,
)

data class BiliPage(
    val cid: String,
    val page: Int,
    val part: String,
    val durationSec: Int = 0,
)

/**
 * 解析结果。失败分支一律带上可展示的原因；拿不到音频直链时绝不返回 Success(url 非空)。
 * 失败分支也尽量带回已成功取到的元数据，方便结果页展示标题/封面并说明失败点。
 */
sealed class ResolveResult {
    abstract val reason: String
    open val info: BiliVideoInfo? get() = null
    open val pages: List<BiliPage> get() = emptyList()

    data class Success(
        override val info: BiliVideoInfo,
        override val pages: List<BiliPage>,
        /** 可能为空：接口没有返回可用的 durl。为空时 reason 会说明原因。 */
        val audioUrl: String?,
        /** 直链的能力边界说明，UI 必须展示。 */
        val note: String = "",
    ) : ResolveResult() {
        override val reason: String get() = if (audioUrl.isNullOrBlank()) "未获取到可用音频直链" else ""
    }

    data class NeedLogin(
        override val reason: String,
        override val info: BiliVideoInfo? = null,
        override val pages: List<BiliPage> = emptyList(),
    ) : ResolveResult()

    data class NoAudioTrack(
        override val reason: String,
        override val info: BiliVideoInfo? = null,
        override val pages: List<BiliPage> = emptyList(),
    ) : ResolveResult()

    data class Unsupported(override val reason: String) : ResolveResult()

    data class NetworkFail(
        override val reason: String,
        override val info: BiliVideoInfo? = null,
        override val pages: List<BiliPage> = emptyList(),
    ) : ResolveResult()

    data class ParseFail(override val reason: String) : ResolveResult()
}

private data class ParsedLink(val bvid: String, val page: Int)

/**
 * B 站链接解析。
 *
 * 一期明确不引入 yt-dlp：Android 进程内没有 JVM 运行时，也无法执行外部解释器。
 * 音频只尝试 B 站公开 playurl 的 durl[0].url（qn=16, fnval=0，音视频合流低清流，
 * 可能是 flv/m4a 等原始流）。它不是 MP3，不做转码；拿不到就返回失败原因。
 */
class BiliResolver {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun resolve(input: String): ResolveResult = withContext(Dispatchers.IO) {
        val raw = input.trim()
        if (raw.isBlank()) return@withContext ResolveResult.ParseFail("请输入 B 站视频链接或 BV 号")

        val parsed = try {
            parse(raw)
        } catch (e: Exception) {
            return@withContext ResolveResult.NetworkFail("短链解析失败：${e.message ?: "网络异常"}")
        }
        when (parsed) {
            is LinkParse.Unsupported -> ResolveResult.Unsupported(parsed.reason)
            is LinkParse.Failed -> ResolveResult.ParseFail(parsed.reason)
            is LinkParse.Ok -> resolveBvid(parsed.link)
        }
    }

    /** 下载阶段重新取直链：URL 可能过期，不能一直用解析时拿到的旧地址。 */
    suspend fun fetchAudioUrl(bvid: String, cid: String): ResolveResult = withContext(Dispatchers.IO) {
        fetchDurl(bvid, cid)
    }

    private suspend fun resolveBvid(link: ParsedLink): ResolveResult {
        val view = fetchView(link.bvid)
        val info: BiliVideoInfo
        val pages: List<BiliPage>
        when (view) {
            is ViewResult.Fail -> return view.result
            is ViewResult.Ok -> {
                info = view.info
                pages = view.pages
            }
        }
        val page = pages.firstOrNull { it.page == link.page } ?: pages.first()
        val audio = fetchDurl(link.bvid, page.cid)
        return when (audio) {
            is ResolveResult.Success -> audio.copy(info = info, pages = pages, note = AUDIO_NOTE)
            is ResolveResult.NeedLogin -> audio.copy(info = info, pages = pages)
            is ResolveResult.NoAudioTrack -> audio.copy(info = info, pages = pages)
            is ResolveResult.NetworkFail -> audio.copy(info = info, pages = pages)
            else -> audio
        }
    }

    // ------------------------------------------------------------------
    // 链接解析
    // ------------------------------------------------------------------

    private sealed class LinkParse {
        data class Ok(val link: ParsedLink) : LinkParse()
        data class Unsupported(val reason: String) : LinkParse()
        data class Failed(val reason: String) : LinkParse()
    }

    private fun parse(input: String): LinkParse {
        val bvRegex = Regex("(BV[0-9A-Za-z]{8,12})")
        // BV 号直接粘贴也支持。
        if (bvRegex.matches(input)) {
            return LinkParse.Ok(ParsedLink(input, 1))
        }

        var url = input
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }

        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        if (host.equals("b23.tv", ignoreCase = true)) {
            url = expandShortLink(url)
        }

        val finalBv = bvRegex.find(url)?.value
            ?: queryParam(url, "bvid")?.takeIf { bvRegex.matches(it) }
        if (finalBv == null) {
            if (Regex("(?:^|[/?&])av\\d+", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                return LinkParse.Unsupported("暂不支持 av 号，请转换为 BV 链接后重试")
            }
            return LinkParse.Failed("无法从链接中提取 BV 号")
        }
        val p = queryParam(url, "p")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return LinkParse.Ok(ParsedLink(finalBv, p))
    }

    private fun expandShortLink(shortUrl: String): String {
        val request = Request.Builder().url(shortUrl).get().headers(COMMON_HEADERS).build()
        client.newCall(request).execute().use { response ->
            val final = response.request.url.toString()
            if (!final.contains("b23.tv", ignoreCase = true)) return final
            // 少数短链返回 JS 跳转页而非 3xx，尝试从页面里找真实地址。
            val body = response.body?.string().orEmpty()
            val match = Regex("https?://(?:www\\.)?bilibili\\.com/video/BV[0-9A-Za-z]{8,12}[^\"'\\s<]*")
                .find(body)
            return match?.value ?: shortUrl
        }
    }

    private fun queryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        return query.split('&').firstOrNull {
            it.substringBefore('=').equals(name, ignoreCase = true)
        }?.substringAfter('=', "")?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // view / playurl
    // ------------------------------------------------------------------

    private sealed class ViewResult {
        data class Ok(val info: BiliVideoInfo, val pages: List<BiliPage>) : ViewResult()
        data class Fail(val result: ResolveResult) : ViewResult()
    }

    private suspend fun fetchView(bvid: String): ViewResult {
        val url = "https://api.bilibili.com/x/web-interface/view?bvid=$bvid"
        val root = try {
            getJson(url)
        } catch (e: Exception) {
            return ViewResult.Fail(ResolveResult.NetworkFail("获取视频信息失败：${e.message ?: "网络异常"}"))
        }
        val code = primitiveInt(root, "code") ?: -1
        if (code != 0) {
            return ViewResult.Fail(classifyApi(code, primitiveString(root, "message").orEmpty()))
        }
        val obj = runCatching { root.jsonObject["data"]?.jsonObject }.getOrNull() ?: return ViewResult.Fail(
            ResolveResult.ParseFail("视频信息响应缺少 data 字段")
        )
        val title = primitiveString(obj, "title") ?: return ViewResult.Fail(
            ResolveResult.ParseFail("视频信息缺少标题")
        )
        val pages = runCatching { obj["pages"]?.jsonArray }.getOrNull()?.mapNotNull { element ->
            val po = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val cid = primitiveString(po, "cid") ?: return@mapNotNull null
            BiliPage(
                cid = cid,
                page = primitiveInt(po, "page") ?: 0,
                part = primitiveString(po, "part").orEmpty(),
                durationSec = primitiveInt(po, "duration") ?: 0,
            )
        }?.sortedBy { it.page } ?: emptyList()
        if (pages.isEmpty()) {
            return ViewResult.Fail(ResolveResult.NoAudioTrack("接口未返回任何分 P（cid），无法获取音频"))
        }
        // owner 在 view 接口里是对象 { name, ... }，不是字符串。
        val ownerName = runCatching { obj["owner"]?.jsonObject?.let { primitiveString(it, "name") } }
            .getOrNull()
            ?: primitiveString(obj, "author").orEmpty()
        val info = BiliVideoInfo(
            bvid = bvid,
            title = title,
            author = ownerName,
            coverUrl = primitiveString(obj, "pic").orEmpty(),
            sourceUrl = "https://www.bilibili.com/video/$bvid/",
            durationSec = primitiveInt(obj, "duration") ?: 0,
        )
        return ViewResult.Ok(info, pages)
    }

    private suspend fun fetchDurl(bvid: String, cid: String): ResolveResult {
        if (cid.isBlank()) {
            return ResolveResult.NoAudioTrack("未解析到分 P 的 cid，无法请求音频")
        }
        val url = "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=16&fnval=0"
        val root = try {
            getJson(url)
        } catch (e: Exception) {
            return ResolveResult.NetworkFail("获取音频直链失败：${e.message ?: "网络异常"}")
        }
        val code = primitiveInt(root, "code") ?: -1
        val message = primitiveString(root, "message").orEmpty()
        if (code != 0) {
            return classifyApi(code, message)
        }
        val data = runCatching { root.jsonObject["data"]?.jsonObject }.getOrNull()
        val audioUrl = runCatching { data?.get("durl")?.jsonArray }.getOrNull()
            ?.firstOrNull()
            ?.let { runCatching { it.jsonObject }.getOrNull() }
            ?.let { primitiveString(it, "url") }
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        if (audioUrl.isNullOrBlank()) {
            return ResolveResult.NoAudioTrack(
                "playurl 未返回可用 durl 直链。匿名请求常被限制为需要登录/大会员，或该分 P 无独立可下载音频流。"
            )
        }
        return ResolveResult.Success(
            info = BiliVideoInfo(bvid, "", "", "", ""),
            pages = emptyList(),
            audioUrl = audioUrl,
            note = AUDIO_NOTE,
        )
    }

    /** 分类登录/会员/地区限制；其它非 0 码按解析失败处理，但保留原始 message。 */
    private fun classifyApi(code: Int, message: String): ResolveResult {
        val text = message.ifBlank { "接口返回码 $code" }
        val restricted = code == -403 || code == -412 || code == -101 || code == 403 ||
            listOf("登录", "会员", "大会员", "地区", "版权", "权限").any { text.contains(it) }
        return if (restricted) {
            ResolveResult.NeedLogin("B 站接口拒绝匿名获取音频：$text（code=$code）")
        } else {
            ResolveResult.ParseFail("B 站接口返回错误：code=$code，$text")
        }
    }

    private fun getJson(url: String): kotlinx.serialization.json.JsonObject {
        val request = Request.Builder().url(url).get().headers(COMMON_HEADERS).build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            response.body?.string() ?: throw java.io.IOException("响应体为空")
        }
        return json.parseToJsonElement(body).jsonObject
    }

    private fun primitiveString(obj: kotlinx.serialization.json.JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun primitiveInt(obj: kotlinx.serialization.json.JsonObject, key: String): Int? =
        (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()

    companion object {
        private val COMMON_HEADERS = okhttp3.Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            .add("Referer", "https://www.bilibili.com/")
            .add("Accept", "application/json, text/plain, */*")
            .build()

        const val AUDIO_NOTE =
            "音频直链来自 playurl qn=16&fnval=0 的低清音视频合流（flv/m4a 等原始流），不是 MP3；本应用不转码、不下载高清完整视频。"
    }
}
