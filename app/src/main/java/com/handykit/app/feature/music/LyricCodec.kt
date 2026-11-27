package com.handykit.app.feature.music

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

data class LyricLine(
    /** LRC 时间戳（毫秒）；TXT 行为 null。 */
    val timeMs: Long?,
    val text: String,
)

sealed class LyricDecodeResult {
    data class Success(val lines: List<LyricLine>, val format: String) : LyricDecodeResult()
    object InvalidUtf8 : LyricDecodeResult()
    object Empty : LyricDecodeResult()
}

/** LRC / TXT 歌词解析。UTF-8 严格校验，非法字节直接拒绝，不做替换字符猜测。 */
object LyricCodec {

    private val timestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val metaTagRegex = Regex("""^\[(?:ar|ti|al|by|offset|length|re|ve):[^\]]*\]$""", RegexOption.IGNORE_CASE)

    fun decode(bytes: ByteArray): LyricDecodeResult {
        val text = strictUtf8(bytes) ?: return LyricDecodeResult.InvalidUtf8
        val normalized = text.removePrefix("\uFEFF")
        if (normalized.isBlank()) return LyricDecodeResult.Empty

        val rawLines = normalized.lineSequence()
            .map { it.trim('\r') }
            .filter { it.isNotBlank() && !metaTagRegex.matches(it.trim()) }
            .toList()
        if (rawLines.isEmpty()) return LyricDecodeResult.Empty

        val hasTimestamp = rawLines.any { timestampRegex.containsMatchIn(it) }
        if (!hasTimestamp) {
            return LyricDecodeResult.Success(
                lines = rawLines.map { LyricLine(null, it.trim()) }.filter { it.text.isNotBlank() },
                format = "txt",
            )
        }

        val offsetMs = Regex("""\[offset:([+-]?\d+)\]""", RegexOption.IGNORE_CASE)
            .find(normalized)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val parsed = mutableListOf<LyricLine>()
        for (line in rawLines) {
            val matches = timestampRegex.findAll(line).toList()
            if (matches.isEmpty()) continue
            // 一行多个时间戳时，文本取最后一个时间戳之后的剩余内容。
            val textPart = line.substring(matches.last().range.endInclusive + 1).trim()
            if (textPart.isBlank()) continue
            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toLongOrNull() ?: continue
                val fractionText = match.groupValues[3]
                val fractionMs = when {
                    fractionText.isEmpty() -> 0L
                    fractionText.length == 1 -> fractionText.toLong() * 100L
                    fractionText.length == 2 -> fractionText.toLong() * 10L
                    else -> fractionText.toLong()
                }
                val ms = minutes * 60_000L + seconds * 1000L + fractionMs + offsetMs
                parsed += LyricLine(ms, textPart)
            }
        }
        if (parsed.isEmpty()) return LyricDecodeResult.Empty
        return LyricDecodeResult.Success(parsed.sortedBy { it.timeMs }, "lrc")
    }

    /** 按播放进度返回应高亮的行下标；无时间戳或尚未到第一行时返回 -1。 */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var result = -1
        for (i in lines.indices) {
            val time = lines[i].timeMs ?: continue
            if (time <= positionMs) result = i else break
        }
        return result
    }

    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
