package com.handykit.app.feature.scale

import java.util.Locale

/**
 * 多协议体脂秤报文解析。纯 JVM 实现，不依赖 Android/Bluetooth 类，便于单元测试。
 *
 * 解析原则：宁可不认，也不误判。每条协议都先校验帧头/标志位/数值范围，
 * 广播侧额外用厂商 ID 黑名单排除 Apple / Samsung / Huawei 等非秤设备。
 */
object PacketParser {

    /** 一帧解析结果。impedanceOhm 为 null 表示设备没有提供阻抗。 */
    data class ParsedScaleData(
        val weightKg: Double,
        val isStable: Boolean,
        val impedanceOhm: Double?,
        val protocolName: String,
    )

    private const val MIN_WEIGHT_KG = 2.0
    private const val MAX_WEIGHT_KG = 400.0
    private const val XIAOMI_COMPANY_ID = 0x0157

    /** 已知非秤厂商 companyId：Apple / Samsung / Huawei / Google / Microsoft / Amazon 等。 */
    private val NON_SCALE_COMPANY_IDS = setOf(
        0x004C, // Apple
        0x0075, // Samsung
        0x027D, // Huawei
        0x0006, // Google
        0x001D, // Microsoft
        0x02FE, // Amazon
        0x0042, // Nokia
    )

    fun isBlacklistedCompanyId(companyId: Int): Boolean = companyId in NON_SCALE_COMPANY_IDS

    /** 完整 UUID / "0x2A9D" / "2A9D" 统一取末 4 位大写。 */
    fun shortUuid(uuid: String?): String {
        if (uuid.isNullOrBlank()) return ""
        val hex = uuid.trim().replace("-", "").replace("0x", "", ignoreCase = true)
        return hex.takeLast(4).uppercase(Locale.US)
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /**
     * 解析 GATT 通知。先按帧头/UUID 强特征匹配，再按首选型号，最后做形状兜底。
     */
    fun parseNotification(data: ByteArray, charUuid: String?, preferred: ScaleModel): ParsedScaleData? {
        if (data.isEmpty()) return null
        val uuid = shortUuid(charUuid)

        // 1) AFU 帧头固定，最不易误判，优先识别。
        parseAfu(data)?.let { return it }

        // 2) SIG 标准特征 UUID 直接指定协议。
        when (uuid) {
            "2A9C" -> parseSigBcs(data)?.let { return it }
            "2A9D" -> parseSigWss(data)?.let { return it }
        }

        // 3) 用户指定型号优先。
        when (preferred) {
            ScaleModel.XIAOMI_SCALE_2, ScaleModel.XIAOMI_SCALE_1 -> parseXiaomi(data)?.let { return it }
            ScaleModel.BOOHEE_YOLANDA -> parseYolanda(data)?.let { return it }
            ScaleModel.OKOK_CHIPSEA -> parseChipsea(data)?.let { return it }
            ScaleModel.SENSSUN -> parseSenssun(data)?.let { return it }
            ScaleModel.AFU_PROTOCOL, ScaleModel.SIG_STANDARD, ScaleModel.AUTO -> Unit
        }

        // 4) 常见私有服务 UUID 兜底。
        when (uuid) {
            "FFB1", "FFB2" -> parseAfu(data)?.let { return it }
            "FFE1", "FFE4" -> parseYolanda(data)?.let { return it }
            "FFF1", "FFF2" -> parseChipsea(data)?.let { return it }
            "FF00", "FF01" -> parseSenssun(data)?.let { return it }
        }

        // 5) 无 UUID 线索时按形状逐个尝试（各解析器均有严格范围校验）。
        parseXiaomi(data)?.let { return it }
        parseSigBcs(data)?.let { return it }
        parseSigWss(data)?.let { return it }
        parseYolanda(data)?.let { return it }
        parseChipsea(data)?.let { return it }
        parseSenssun(data)?.let { return it }
        return null
    }

    /**
     * 解析免配对广播（小米 0x181B / 0x181D 服务数据、0x0157 厂商数据等）。
     * 广播包常带协议前缀，解析器会尝试多个体重字段偏移。
     */
    fun parseAdvertisementBytes(
        serviceData: Map<String, ByteArray>,
        manufacturerData: Map<Int, ByteArray>,
        preferred: ScaleModel = ScaleModel.AUTO,
    ): ParsedScaleData? {
        // 1) 服务数据：小米体重/体脂服务 + SIG 体重服务。
        for ((uuid, payload) in serviceData) {
            val su = shortUuid(uuid)
            when (su) {
                "181B", "181D" -> parseXiaomi(payload)?.let {
                    return it.copy(protocolName = "Xiaomi Broadcast $su")
                }
                "2A9C" -> parseSigBcs(payload)?.let { return it }
                "2A9D" -> parseSigWss(payload)?.let { return it }
            }
        }

        // 2) 厂商数据：先过黑名单，再按厂商/首选型号解析。
        for ((companyId, payload) in manufacturerData) {
            if (isBlacklistedCompanyId(companyId)) continue
            if (companyId == XIAOMI_COMPANY_ID) {
                parseXiaomi(payload)?.let { return it }
                // 小米厂商数据常以 0x57 0x01（或类似标志）开头，剥掉前缀再试一次。
                if (payload.size > 2) {
                    parseXiaomi(payload.copyOfRange(2, payload.size))?.let { return it }
                }
            }
            when (preferred) {
                ScaleModel.BOOHEE_YOLANDA -> parseYolanda(payload)?.let { return it }
                ScaleModel.OKOK_CHIPSEA -> parseChipsea(payload)?.let { return it }
                ScaleModel.SENSSUN -> parseSenssun(payload)?.let { return it }
                ScaleModel.AFU_PROTOCOL -> parseAfu(payload)?.let { return it }
                else -> Unit
            }
        }

        // 3) 服务数据兜底：按用户指定型号再试一次。
        for ((_, payload) in serviceData) {
            when (preferred) {
                ScaleModel.BOOHEE_YOLANDA -> parseYolanda(payload)?.let { return it }
                ScaleModel.OKOK_CHIPSEA -> parseChipsea(payload)?.let { return it }
                ScaleModel.SENSSUN -> parseSenssun(payload)?.let { return it }
                ScaleModel.AFU_PROTOCOL -> parseAfu(payload)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    /** 扫描候选过滤：服务 UUID / 厂商 ID / 设备名任一命中即视为候选秤。 */
    fun isScaleCandidate(
        serviceUuids: List<String>,
        manufacturerIds: List<Int>,
        deviceName: String?,
        preferred: ScaleModel = ScaleModel.AUTO,
    ): Boolean {
        val serviceHit = serviceUuids.any { isScaleServiceUuid(it) }
        // 黑名单设备即使名字听起来像秤也不收（除非确实广播了已知秤服务）。
        if (!serviceHit && manufacturerIds.any { isBlacklistedCompanyId(it) }) return false

        val name = deviceName.orEmpty()
        val nameHit = listOf("scale", "体重", "体脂", "yolanda", "okok", "senssun", "mi scale", "body comp")
            .any { name.contains(it, ignoreCase = true) }
        val vendorHit = manufacturerIds.any { it == XIAOMI_COMPANY_ID }

        if (preferred == ScaleModel.BOOHEE_YOLANDA && shortUuidMatch(serviceUuids, "FFE0")) return true
        if (preferred == ScaleModel.OKOK_CHIPSEA && shortUuidMatch(serviceUuids, "FFF0")) return true
        if (preferred == ScaleModel.AFU_PROTOCOL && shortUuidMatch(serviceUuids, "FFB0")) return true

        return serviceHit || vendorHit || nameHit
    }

    private fun shortUuidMatch(uuids: List<String>, short: String): Boolean =
        uuids.any { shortUuid(it) == short }

    private fun isScaleServiceUuid(uuid: String): Boolean =
        shortUuid(uuid) in setOf(
            "181B", "181D", "2A9B", "2A9C", "2A9D",
            "FFB0", "FFB1", "FFE0", "FFE1", "FFF0", "FFF1", "FF00", "FF01",
        )

    // ------------------------------------------------------------------
    // AFU：0xAC 帧头，体重 big-endian，单位 g
    // ------------------------------------------------------------------

    private fun parseAfu(data: ByteArray): ParsedScaleData? {
        if (data.size < 6 || u8(data, 0) != 0xAC) return null
        val w3 = u8(data, 3)
        if (w3 !in 0x68..0x6E) return null
        val status = u8(data, 2)
        if (status > 0x05) return null
        val rawGrams = (w3 - 0x68) * 65536 + u8(data, 4) * 256 + u8(data, 5)
        val weightKg = rawGrams / 1000.0
        if (weightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) return null
        // status 0x00/0x05 视为测量完成，其余为过程值。
        val stable = status == 0x00 || status == 0x05
        var impedance: Double? = null
        if (data.size >= 12 && canRead(data, 9, 2)) {
            val rawImp = u16be(data, 9) / 10.0
            if (rawImp in 1.0..10000.0) impedance = rawImp
        }
        return ParsedScaleData(weightKg, stable, impedance, "AFU")
    }

    // ------------------------------------------------------------------
    // SIG Weight Scale Service 0x2A9D：flags + UInt16 LE，SI 分辨率 0.005 kg
    // ------------------------------------------------------------------

    private fun parseSigWss(data: ByteArray): ParsedScaleData? {
        if (data.size !in 3..20) return null
        val flags = u8(data, 0)
        if ((flags and 0xF8) != 0) return null // 仅接受已知低 3 位标志
        var off = 1
        val raw = u16le(data, off)
        off += 2
        val imperial = (flags and 0x01) != 0
        val weightKg = if (imperial) raw * 0.01 * 0.45359237 else raw * 0.005
        if (weightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) return null
        if ((flags and 0x02) != 0) off += 7 // timestamp
        if ((flags and 0x04) != 0) off += 2 // user id
        if (off > data.size) return null
        return ParsedScaleData(weightKg, isStable = true, impedanceOhm = null, protocolName = "SIG WSS")
    }

    // ------------------------------------------------------------------
    // SIG Body Composition Service 0x2A9C：16 位 flags 按位跳字段
    // bit0 单位, bit1 时间戳, bit2 用户ID, bit3 基础代谢, bit4 体脂率(0.1%),
    // bit5 水分, bit6 去脂体重, bit7 软瘦体重, bit8 体水重, bit9 阻抗(0.1Ω),
    // bit10 体重(0.005kg), bit11 身高
    // ------------------------------------------------------------------

    private fun parseSigBcs(data: ByteArray): ParsedScaleData? {
        if (data.size < 4) return null
        if ((u8(data, 1) and 0xF0) != 0) return null // byte1 只使用 bit8..bit11
        val flags = u16le(data, 0)
        val imperial = (flags and 0x01) != 0
        var off = 2

        // bit3 基础代谢（kcal/kJ），占 2 字节。
        if ((flags and (1 shl 3)) != 0) {
            if (!canRead(data, off, 2)) return null
            off += 2
        }
        // bit4 体脂率，0.1%。
        if ((flags and (1 shl 4)) != 0) {
            if (!canRead(data, off, 2)) return null
            off += 2
        }
        // bit5 水分率，0.1%。
        if ((flags and (1 shl 5)) != 0) {
            if (!canRead(data, off, 2)) return null
            off += 2
        }
        // bit6 去脂体重，bit7 软瘦体重，bit8 体水重，各 2 字节。
        if ((flags and (1 shl 6)) != 0) {
            if (!canRead(data, off, 2)) return null
            off += 2
        }
        if ((flags and (1 shl 7)) != 0) {
            if (!canRead(data, off, 2)) return null
            off += 2
        }
        if ((flags and (1 shl 8)) != 0) {
            if (!canRead(data, off, 2)) return null
            off += 2
        }
        // bit9 阻抗，0.1Ω。
        var impedance: Double? = null
        if ((flags and (1 shl 9)) != 0) {
            if (!canRead(data, off, 2)) return null
            val ohms = u16le(data, off) / 10.0
            off += 2
            if (ohms > 0.0 && ohms < 10000.0) impedance = ohms
        }
        // bit10 体重，SI 0.005kg；imperial 0.01lb。
        var weightKg: Double? = null
        if ((flags and (1 shl 10)) != 0) {
            if (!canRead(data, off, 2)) return null
            val raw = u16le(data, off)
            off += 2
            weightKg = if (imperial) raw * 0.01 * 0.45359237 else raw * 0.005
        }
        // bit11 身高，2 字节。
        if ((flags and (1 shl 11)) != 0) off += 2
        if ((flags and 0x02) != 0) off += 7 // timestamp
        if ((flags and 0x04) != 0) off += 2 // user id
        if (off > data.size) return null

        val weight = weightKg ?: return null
        if (weight !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) return null
        return ParsedScaleData(weight, isStable = true, impedanceOhm = impedance, protocolName = "SIG BCS")
    }

    // ------------------------------------------------------------------
    // 小米 13/10 字节：flags + UInt16 BE 体重(/200 kg, /100 lb)
    // bit0 单位，bit4 有阻抗，bit5 稳定
    // ------------------------------------------------------------------

    private fun parseXiaomi(data: ByteArray): ParsedScaleData? {
        if (data.size !in 10..24) return null
        val flag = u8(data, 0)
        if ((flag and 0xC0) != 0) return null
        val imperial = (flag and 0x01) != 0

        // 广播/通知可能带 1~2 字节前缀，体重字段尝试 offset 1 与 2。
        val candidates = listOf(1, 2).mapNotNull { off ->
            if (!canRead(data, off, 2)) return@mapNotNull null
            val raw = u16be(data, off)
            val weight = if (imperial) raw / 100.0 * 0.45359237 else raw / 200.0
            if (weight in MIN_WEIGHT_KG..MAX_WEIGHT_KG) weight to off else null
        }
        val (weightKg, weightOff) = candidates.firstOrNull() ?: return null

        var impedance: Double? = null
        if ((flag and 0x10) != 0 && canRead(data, weightOff + 2, 2)) {
            val rawImp = u16be(data, weightOff + 2).toDouble()
            if (rawImp in 1.0..10000.0) impedance = rawImp
        }
        val stable = (flag and 0x20) != 0 || data.size == 10
        return ParsedScaleData(weightKg, stable, impedance, "Xiaomi Scale")
    }

    // ------------------------------------------------------------------
    // 薄荷 Yolanda：常见 0xAA/0xA5/0x01/0x03 帧头，体重 UInt16 BE /100 kg
    // ------------------------------------------------------------------

    private fun parseYolanda(data: ByteArray): ParsedScaleData? {
        if (data.size < 6) return null
        val header = u8(data, 0)
        if (header !in setOf(0xAA, 0xA5, 0x01, 0x03)) return null
        for (off in intArrayOf(2, 1, 4)) {
            if (!canRead(data, off, 2)) continue
            val weightKg = u16be(data, off) / 100.0
            if (weightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) continue
            var impedance: Double? = null
            if (canRead(data, off + 2, 2)) {
                val rawImp = u16be(data, off + 2).toDouble()
                if (rawImp in 1.0..10000.0) impedance = rawImp
            }
            // 末字节低位为完成标志的常见约定。
            val stable = (u8(data, data.lastIndex) and 0x01) == 0
            return ParsedScaleData(weightKg, stable, impedance, "Yolanda")
        }
        return null
    }

    // ------------------------------------------------------------------
    // OKOK Chipsea：0xAA55 / 0x55AA / 0x0300 帧头，体重 UInt16 BE /100 kg
    // ------------------------------------------------------------------

    private fun parseChipsea(data: ByteArray): ParsedScaleData? {
        if (data.size < 7) return null
        val a = u8(data, 0)
        val b = u8(data, 1)
        val validHeader = (a == 0xAA && b == 0x55) || (a == 0x55 && b == 0xAA) || (a == 0x03 && b == 0x00)
        if (!validHeader) return null
        for (off in intArrayOf(3, 2, 4)) {
            if (!canRead(data, off, 2)) continue
            val weightKg = u16be(data, off) / 100.0
            if (weightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) continue
            var impedance: Double? = null
            if (canRead(data, off + 2, 2)) {
                val rawImp = u16be(data, off + 2).toDouble()
                if (rawImp in 1.0..10000.0) impedance = rawImp
            }
            return ParsedScaleData(weightKg, isStable = true, impedanceOhm = impedance, protocolName = "OKOK Chipsea")
        }
        return null
    }

    // ------------------------------------------------------------------
    // SENSSUN：0x03/0xAA/0xA5 帧头，体重在 byte2..3（/100 kg），阻抗 byte4..5
    // ------------------------------------------------------------------

    private fun parseSenssun(data: ByteArray): ParsedScaleData? {
        if (data.size < 6) return null
        if (u8(data, 0) !in setOf(0x03, 0xAA, 0xA5)) return null
        val weightKg = u16be(data, 2) / 100.0
        if (weightKg !in MIN_WEIGHT_KG..MAX_WEIGHT_KG) return null
        var impedance: Double? = null
        if (canRead(data, 4, 2)) {
            val rawImp = u16be(data, 4).toDouble()
            if (rawImp in 1.0..10000.0) impedance = rawImp
        }
        return ParsedScaleData(weightKg, isStable = true, impedanceOhm = impedance, protocolName = "SENSSUN")
    }

    // ------------------------------------------------------------------
    // 字节工具
    // ------------------------------------------------------------------

    private fun canRead(data: ByteArray, off: Int, len: Int): Boolean = off >= 0 && off + len <= data.size

    private fun u8(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF

    private fun u16le(data: ByteArray, index: Int): Int = u8(data, index) or (u8(data, index + 1) shl 8)

    private fun u16be(data: ByteArray, index: Int): Int = (u8(data, index) shl 8) or u8(data, index + 1)
}
