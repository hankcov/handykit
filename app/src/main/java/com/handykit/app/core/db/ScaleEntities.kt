package com.handykit.app.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 体脂成员。性别存字符串 "MALE"/"FEMALE"，避免 Room 枚举转换器。 */
@Entity(tableName = "scale_member")
data class ScaleMember(
    @PrimaryKey val id: String,
    val name: String,
    val sex: String,
    val heightCm: Double,
    val birthDateEpochMs: Long,
    val referenceWeightKg: Double,
)

/**
 * 测量记录。无阻抗时体成分字段为 0 并用 source 标注来源；
 * algorithmVersion / sourceDetail 保留原始测量与算法上下文，供事后核对。
 */
@Entity(tableName = "measurement")
data class Measurement(
    @PrimaryKey val id: String,
    val measuredAtEpochMs: Long,
    val weightKg: Double,
    val impedanceOhm: Double,
    val bmi: Double,
    val bodyFatPct: Double,
    val muscleKg: Double,
    val waterPct: Double,
    val proteinPct: Double,
    val boneMassKg: Double,
    val basalMetabolic: Double = 0.0,
    val memberId: String?,
    val memberNameSnapshot: String?,
    /** 协议名，如 Xiaomi Mi Scale 2 / AFU，标注设备实测来源 */
    val source: String = "",
    val algorithmVersion: String = "bia-v1",
)
