package com.handykit.app.feature.scale

/** 体脂秤支持型号。AUTO 走自动识别，其余型号决定握手方式与首选解析协议。 */
enum class ScaleModel(
    val id: String,
    val displayName: String,
    val protocolName: String,
    val wakeGuidance: String,
) {
    AUTO(
        id = "auto",
        displayName = "自动识别",
        protocolName = "自动判别协议",
        wakeGuidance = "先打开 App 再上秤，轻踩秤面唤醒；App 会按广播/GATT 特征自动判别协议。",
    ),
    XIAOMI_SCALE_2(
        id = "xiaomi_scale_2",
        displayName = "小米体脂秤 2",
        protocolName = "Xiaomi 0x181B/0x181D",
        wakeGuidance = "踩一下秤面唤醒，等屏幕亮起后双脚站立，保持静止直到体重稳定。",
    ),
    XIAOMI_SCALE_1(
        id = "xiaomi_scale_1",
        displayName = "小米体重秤 1",
        protocolName = "Xiaomi 0x181D",
        wakeGuidance = "踩一下秤面唤醒，双脚站立保持静止；该型号不提供阻抗数据。",
    ),
    BOOHEE_YOLANDA(
        id = "boohee_yolanda",
        displayName = "薄荷 Yolanda",
        protocolName = "Yolanda 0xFFE0",
        wakeGuidance = "先打开 App，赤脚站上秤面，等待阻抗测量完成提示。",
    ),
    OKOK_CHIPSEA(
        id = "okok_chipsea",
        displayName = "OKOK Chipsea",
        protocolName = "Chipsea 0xFFF0",
        wakeGuidance = "先打开 App 并连接，赤脚站上秤面，保持静止直到测量完成。",
    ),
    SENSSUN(
        id = "senssun",
        displayName = "SENSSUN 森森",
        protocolName = "SENSSUN 自定义",
        wakeGuidance = "踩秤唤醒后赤脚站立，等待体脂数据显示稳定。",
    ),
    AFU_PROTOCOL(
        id = "afu",
        displayName = "AFU 协议秤",
        protocolName = "AFU 0xFFB1",
        wakeGuidance = "连接后 App 会下发握手指令；随后赤脚上秤，保持静止。",
    ),
    SIG_STANDARD(
        id = "sig_standard",
        displayName = "SIG 标准体脂秤",
        protocolName = "SIG BCS/WSS",
        wakeGuidance = "上秤后保持静止，标准体重服务会推送稳定结果。",
    ),
    ;

    companion object {
        fun fromId(id: String?): ScaleModel = entries.firstOrNull { it.id == id } ?: AUTO
    }
}
