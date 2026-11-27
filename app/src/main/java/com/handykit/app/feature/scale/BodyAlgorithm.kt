package com.handykit.app.feature.scale

import com.handykit.app.core.db.Measurement
import com.handykit.app.core.db.ScaleMember
import java.time.Instant
import java.time.Period
import java.time.ZoneId

/**
 * 体成分算法：从旧项目原样移植，公式不得改动。
 *
 * 口径约定（S2）：
 * - BMI 只依赖体重与身高，weight > 0 时始终可算；
 * - BIA 相关指标仅在 [hasImpedance] 为真时给出，无阻抗一律返回 0.0，
 *   调用方（UI）必须按「不可用」展示，不得用 0 冒充实测值，也不做无阻抗估算；
 * - 基础代谢用 Mifflin-St Jeor 公式，属于「标注参考」的估算值。
 */
object BodyAlgorithm {
    /** 有效阻抗判定：无阻抗或体重过低（站上秤但重量不可信）时不计算 BIA。 */
    fun hasImpedance(impedanceOhm: Double, weightKg: Double): Boolean =
        impedanceOhm > 0.0 && weightKg >= 3.0

    /**
     * 按体重/阻抗/性别/身高/生日计算一条测量。
     * 返回的 id / measuredAt / memberId 均为空，由仓库与 ViewModel 补齐。
     */
    fun calculate(
        weightKg: Double,
        impedanceOhm: Double,
        sex: String,
        heightCm: Double,
        birthDateEpochMs: Long,
    ): Measurement {
        val safeHeightCm = heightCm.coerceIn(50.0, 250.0)
        val heightM = safeHeightCm / 100.0
        val bmi = if (weightKg > 0.0) {
            (weightKg / (heightM * heightM)).coerceIn(1.0, 100.0)
        } else {
            0.0
        }
        val age = resolveAge(birthDateEpochMs)
        val hasImp = hasImpedance(impedanceOhm, weightKg)
        val male = sex.equals("MALE", ignoreCase = true)

        val fat = if (hasImp) {
            val raw = if (male) {
                0.18 * bmi + 0.012 * age + 0.018 * impedanceOhm - 3.2
            } else {
                0.26 * bmi + 0.011 * age + 0.020 * impedanceOhm - 2.5
            }
            raw.coerceIn(5.0, 55.0)
        } else {
            0.0
        }

        val water = if (hasImp && fat > 0.0) {
            (69.7 - fat * 0.55).coerceIn(35.0, 75.0)
        } else {
            0.0
        }
        val bone = if (hasImp && fat > 0.0) {
            (weightKg * if (male) 0.047 else 0.040).coerceIn(1.5, 5.5)
        } else {
            0.0
        }
        val protein = if (hasImp && fat > 0.0) {
            (16.0 + (water - 50.0) * 0.12).coerceIn(10.0, 24.0)
        } else {
            0.0
        }
        val muscle = if (hasImp && fat > 0.0) {
            maxOf(weightKg * (1.0 - fat / 100.0) - bone, 0.0)
        } else {
            0.0
        }

        // Mifflin-St Jeor：不依赖阻抗，属于标注参考的估算值。
        val basalMetabolic = if (weightKg > 0.0 && age in 1..120) {
            (10.0 * weightKg + 6.25 * safeHeightCm - 5.0 * age + if (male) 5.0 else -161.0)
                .coerceIn(0.0, 10000.0)
        } else {
            0.0
        }

        return Measurement(
            id = "",
            measuredAtEpochMs = System.currentTimeMillis(),
            weightKg = weightKg,
            impedanceOhm = impedanceOhm,
            bmi = bmi,
            bodyFatPct = fat,
            muscleKg = muscle,
            waterPct = water,
            proteinPct = protein,
            boneMassKg = bone,
            basalMetabolic = basalMetabolic,
            memberId = null,
            memberNameSnapshot = null,
            source = "",
            algorithmVersion = "bia-v1",
        )
    }

    /**
     * 绑定成员（或修正归属）后按成员参数重算所有可重算项。
     * 保留原始测量的 id、时间、体重、阻抗、协议来源，只刷新派生指标。
     */
    fun recalculateForMember(m: Measurement, member: ScaleMember): Measurement {
        val computed = calculate(
            weightKg = m.weightKg,
            impedanceOhm = m.impedanceOhm,
            sex = member.sex,
            heightCm = member.heightCm,
            birthDateEpochMs = member.birthDateEpochMs,
        )
        return m.copy(
            bmi = computed.bmi,
            bodyFatPct = computed.bodyFatPct,
            muscleKg = computed.muscleKg,
            waterPct = computed.waterPct,
            proteinPct = computed.proteinPct,
            boneMassKg = computed.boneMassKg,
            basalMetabolic = computed.basalMetabolic,
            memberId = member.id,
            memberNameSnapshot = member.name,
            algorithmVersion = computed.algorithmVersion,
        )
    }

    /** 生日无效或位于未来时按 30 岁处理；否则按年月日算并按 1..120 收敛。 */
    private fun resolveAge(birthDateEpochMs: Long, nowEpochMs: Long = System.currentTimeMillis()): Int {
        if (birthDateEpochMs <= 0L) return 30
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
        val birth = Instant.ofEpochMilli(birthDateEpochMs).atZone(zone).toLocalDate()
        if (birth.isAfter(today)) return 30
        return Period.between(birth, today).years.coerceIn(1, 120)
    }
}
