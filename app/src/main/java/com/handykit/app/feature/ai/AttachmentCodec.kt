package com.handykit.app.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object AttachmentLimits {
    const val MAX_FILE_BYTES = 20L * 1024 * 1024
    const val MAX_COUNT = 6
    const val MAX_PDF_PAGES = 40
    const val MAX_IMAGE_EDGE = 4096
}

class AttachmentException(message: String) : Exception(message)

data class PreparedAttachment(
    val name: String,
    val type: String,
    val mime: String,
    val sizeBytes: Long,
    val localPath: String,
    val extractedText: String = "",
    val pdfPages: Int = 0,
    /** 必须如实展示给用户的限制说明，例如 PDF 无法提取文本。 */
    val note: String = "",
)

private enum class Detected { JPEG, PNG, WEBP, PDF, TEXT, UNKNOWN }

/**
 * 附件落盘与格式校验。所有文件复制到应用私有 filesDir/ai_attachments/<sessionId>/，
 * URI 只用于一次性读取，不长期持有。
 */
object AttachmentCodec {

    private val TEXT_EXTENSIONS = setOf("txt", "md", "markdown")

    /**
     * 用系统 ExifInterface 去掉 GPS 定位标签。字符串标签名是 EXIF 标准键，
     * 避免依赖未引入的 androidx.exifinterface。
     */
    private val GPS_TAGS = arrayOf(
        "GPSLatitude", "GPSLongitude", "GPSAltitude", "GPSTimeStamp", "GPSDateStamp",
        "GPSProcessingMethod", "GPSSatellites", "GPSSpeed", "GPSTrack", "GPSImgDirection",
        "GPSMapDatum", "GPSVersionID", "GPSLatitudeRef", "GPSLongitudeRef", "GPSAltitudeRef",
        "GPSDist", "GPSBearing", "GPSDestLatitude", "GPSDestLongitude",
    )

    suspend fun prepare(
        uri: Uri,
        context: Context,
        sessionId: String,
        existingCount: Int,
    ): PreparedAttachment = withContext(Dispatchers.IO) {
        if (existingCount >= AttachmentLimits.MAX_COUNT) {
            throw AttachmentException("附件最多 ${AttachmentLimits.MAX_COUNT} 个")
        }

        val appContext = context.applicationContext
        val name = displayName(appContext, uri)
        val bytes = readLimited(appContext.contentResolver.openInputStream(uri))
        val detected = detect(bytes)

        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val actualExt = when (detected) {
            Detected.JPEG -> "jpg"
            Detected.PNG -> "png"
            Detected.WEBP -> "webp"
            Detected.PDF -> "pdf"
            Detected.TEXT -> if (extension in TEXT_EXTENSIONS) extension else "txt"
            Detected.UNKNOWN -> throw AttachmentException("无法识别文件格式，仅支持 JPG/PNG/WebP/PDF/TXT/MD")
        }

        // 扩展名与实际内容不符必须拒绝，不能靠 ContentResolver 的 MIME 猜测。
        val extensionMatches = when (detected) {
            Detected.JPEG -> extension in setOf("jpg", "jpeg")
            Detected.PNG -> extension == "png"
            Detected.WEBP -> extension == "webp"
            Detected.PDF -> extension == "pdf"
            Detected.TEXT -> extension.isEmpty() || extension in TEXT_EXTENSIONS
            Detected.UNKNOWN -> false
        }
        if (!extensionMatches) {
            throw AttachmentException("扩展名与文件内容不符（$name 实际为 ${detected.label}），已拒绝")
        }

        val dir = File(appContext.filesDir, "ai_attachments/$sessionId")
        if (!dir.exists() && !dir.mkdirs()) throw AttachmentException("无法创建附件目录")

        when (detected) {
            Detected.JPEG, Detected.PNG, Detected.WEBP -> {
                val original = File(dir, "${UUID.randomUUID()}.$actualExt")
                FileOutputStream(original).use { it.write(bytes) }
                val cleaned = stripGpsAndResize(original, dir, actualExt, detected)
                PreparedAttachment(
                    name = name,
                    type = "image",
                    mime = "image/${if (detected == Detected.JPEG) "jpeg" else actualExt}",
                    sizeBytes = cleaned.length(),
                    localPath = cleaned.absolutePath,
                )
            }

            Detected.PDF -> {
                val file = File(dir, "${UUID.randomUUID()}.pdf")
                FileOutputStream(file).use { it.write(bytes) }
                val pages = inspectPdf(file)
                PreparedAttachment(
                    name = name,
                    type = "pdf",
                    mime = "application/pdf",
                    sizeBytes = file.length(),
                    localPath = file.absolutePath,
                    extractedText = "",
                    pdfPages = pages,
                    note = "PDF：本地无法提取文本，需原生 PDF 能力；已统计 $pages 页并逐页校验渲染，请勿假定模型理解了 PDF 正文。",
                )
            }

            Detected.TEXT -> {
                val file = File(dir, "${UUID.randomUUID()}.$actualExt")
                FileOutputStream(file).use { it.write(bytes) }
                val text = decodeUtf8(bytes)
                PreparedAttachment(
                    name = name,
                    type = if (extension == "md" || extension == "markdown") "markdown" else "txt",
                    mime = if (extension == "md" || extension == "markdown") "text/markdown" else "text/plain",
                    sizeBytes = file.length(),
                    localPath = file.absolutePath,
                    extractedText = text,
                )
            }

            Detected.UNKNOWN -> throw AttachmentException("无法识别文件格式")
        }
    }

    /** 删除会话时一并清理私有附件目录。 */
    fun deleteSessionFiles(context: Context, sessionId: String) {
        val dir = File(context.applicationContext.filesDir, "ai_attachments/$sessionId")
        if (!dir.exists()) return
        dir.listFiles()?.forEach { runCatching { if (it.isFile) it.delete() } }
        runCatching { dir.delete() }
    }

    /** 删除单个附件文件。 */
    fun deleteFile(localPath: String) {
        runCatching {
            val file = File(localPath)
            val parent = file.parentFile
            if (file.isFile) file.delete()
            if (parent != null && parent.listFiles().isNullOrEmpty()) parent.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun stripGpsAndResize(
        original: File,
        dir: File,
        ext: String,
        detected: Detected,
    ): File {
        // 1) ExifInterface 直接去 GPS 属性（JPEG 可写；其它格式写失败由重编码兜底）。
        runCatching {
            val exif = ExifInterface(original.absolutePath)
            GPS_TAGS.forEach { tag -> runCatching { exif.setAttribute(tag, null) } }
            exif.saveAttributes()
        }

        // 2) 解码 + 重编码：彻底去掉残留 EXIF，并强制最大边长。
        val decoded = BitmapFactory.decodeFile(original.absolutePath)
            ?: throw AttachmentException("图片无法解码，可能已损坏")
        val width = decoded.width
        val height = decoded.height
        if (width <= 0 || height <= 0) throw AttachmentException("图片尺寸非法")
        val scale = minOf(1f, AttachmentLimits.MAX_IMAGE_EDGE.toFloat() / max(width, height))
        val outBitmap = if (scale < 1f) {
            val w = (width * scale).roundToInt().coerceAtLeast(1)
            val h = (height * scale).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true)
        } else {
            decoded
        }

        val format = when (detected) {
            Detected.PNG -> Bitmap.CompressFormat.PNG
            Detected.WEBP ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            else -> Bitmap.CompressFormat.JPEG
        }
        val cleaned = File(dir, "${UUID.randomUUID()}.$ext")
        FileOutputStream(cleaned).use { stream ->
            if (!outBitmap.compress(format, 92, stream)) {
                throw AttachmentException("图片重编码失败")
            }
        }
        if (cleaned.absolutePath != original.absolutePath) runCatching { original.delete() }
        return cleaned
    }

    /** PdfRenderer 只能渲染/计数，不含文本提取；逐页低分辨率渲染用于验证页面可打开。 */
    private fun inspectPdf(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw AttachmentException("PDF 无法打开，可能已损坏或加密")
        }
        var currentPage = -1
        try {
            val count = renderer.pageCount
            if (count <= 0) throw AttachmentException("PDF 没有任何页面")
            if (count > AttachmentLimits.MAX_PDF_PAGES) {
                throw AttachmentException("PDF 超过 ${AttachmentLimits.MAX_PDF_PAGES} 页上限（实际 $count 页）")
            }
            for (i in 0 until count) {
                currentPage = i
                val page = renderer.openPage(i)
                try {
                    val scale = minOf(1f, 720f / max(page.width, page.height))
                    val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
            }
            return count
        } catch (e: AttachmentException) {
            throw e
        } catch (e: Exception) {
            throw AttachmentException("PDF 第 ${currentPage + 1} 页渲染失败，请确认文件未加密")
        } finally {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw AttachmentException("文本不是合法 UTF-8 编码，已拒绝")
    }

    private fun readLimited(stream: java.io.InputStream?): ByteArray {
        if (stream == null) throw AttachmentException("无法读取该文件")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                total += n
                if (total > AttachmentLimits.MAX_FILE_BYTES) {
                    throw AttachmentException("单文件超过 ${AttachmentLimits.MAX_FILE_BYTES / 1024 / 1024}MB 上限")
                }
                out.write(buffer, 0, n)
            }
            if (out.size() == 0) throw AttachmentException("文件为空")
            return out.toByteArray()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        val cursor = try {
            context.contentResolver.query(uri, projection, null, null, null)
        } catch (_: Exception) {
            null
        }
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    val value = it.getString(index)
                    if (!value.isNullOrBlank()) return value
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    }

    private fun detect(bytes: ByteArray): Detected {
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xFF &&
            (bytes[1].toInt() and 0xFF) == 0xD8 &&
            (bytes[2].toInt() and 0xFF) == 0xFF
        ) return Detected.JPEG

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size >= png.size && bytes.copyOfRange(0, png.size).contentEquals(png)) return Detected.PNG

        if (bytes.size >= 12 &&
            ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP"
        ) return Detected.WEBP

        if (bytes.size >= 4 && ascii(bytes, 0, 4) == "%PDF") return Detected.PDF

        // UTF-8 文本：严格解码成功，且不含控制字符（换行/制表除外）。
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return Detected.UNKNOWN
        }
        if (text.any { c -> c.isISOControl() && c !in listOf('\n', '\r', '\t', '\u000C') }) {
            return Detected.UNKNOWN
        }
        return Detected.TEXT
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private val Detected.label: String
        get() = when (this) {
            Detected.JPEG -> "JPEG 图片"
            Detected.PNG -> "PNG 图片"
            Detected.WEBP -> "WebP 图片"
            Detected.PDF -> "PDF"
            Detected.TEXT -> "UTF-8 文本"
            Detected.UNKNOWN -> "未知格式"
        }
}
