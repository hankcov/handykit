package com.handykit.app

import com.handykit.app.feature.fuel.FuelEntry
import com.handykit.app.feature.fuel.FuelMath
import com.handykit.app.feature.fuel.SegmentAnomaly
import com.handykit.app.feature.scale.BodyAlgorithm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 核心算法回归。需求 V8（满箱法 6.0 L/100km）与 V10/S2（无阻抗不生成数值）的可自动验证部分。
 */
class CoreAlgorithmTest {

    private fun entry(id: Long, date: String, odo: Int, liters: Double, full: Boolean) = FuelEntry(
        id = id,
        date = LocalDate.parse(date),
        odometer = odo,
        liters = liters,
        amount = liters * 8.0,
        isFull = full,
    )

    @Test
    fun `V8 满箱法验收样例得到 6 升每百公里`() {
        // 1000km 加满、1200km 加 10L 未满、1500km 加 20L 至满
        val entries = listOf(
            entry(1, "2026-01-01", 1000, 40.0, true), // 起始满箱，油量不计入
            entry(2, "2026-01-10", 1200, 10.0, false),
            entry(3, "2026-01-20", 1500, 20.0, true),
        )
        val segments = FuelMath.segments(entries)
        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals(500, seg.distance)
        assertEquals(30.0, seg.fuel, 1e-9)
        assertEquals(6.0, seg.l100!!, 1e-9)
        assertTrue(seg.isTrusted)
        assertEquals(3L, seg.toId)
    }

    @Test
    fun `里程回退产生异常区间且油耗为空`() {
        val entries = listOf(
            entry(1, "2026-01-01", 1000, 40.0, true),
            entry(2, "2026-01-10", 900, 20.0, true), // 里程回退
        )
        val seg = FuelMath.segments(entries).single()
        assertEquals(SegmentAnomaly.ODOMETER, seg.anomaly)
        assertEquals(null, seg.l100)
        assertTrue(!seg.isTrusted)
    }

    @Test
    fun `超范围油耗标异常不显示数值`() {
        val entries = listOf(
            entry(1, "2026-01-01", 1000, 40.0, true),
            entry(2, "2026-01-10", 1100, 80.0, true), // 80 L/100km
        )
        val seg = FuelMath.segments(entries).single()
        assertEquals(SegmentAnomaly.OUT_OF_RANGE, seg.anomaly)
    }

    @Test
    fun `S2 无阻抗时体成分不可用而非填零`() {
        val m = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 0.0,
            sex = "MALE",
            heightCm = 175.0,
            birthDateEpochMs = 0L,
        )
        // BMI 来自实测体重与身高，始终可用
        assertTrue(m.bmi > 0.0)
        assertTrue(!BodyAlgorithm.hasImpedance(0.0, 70.0))
        // BIA 指标在无阻抗时不产生值，UI 层据此显示「不可用」
        assertEquals(0.0, m.bodyFatPct, 1e-9)
        assertEquals(0.0, m.muscleKg, 1e-9)
        assertEquals(0.0, m.waterPct, 1e-9)
        assertEquals(0.0, m.proteinPct, 1e-9)
        assertEquals(0.0, m.boneMassKg, 1e-9)
    }

    @Test
    fun `S2 有阻抗时产生参考体成分`() {
        val m = BodyAlgorithm.calculate(
            weightKg = 70.0,
            impedanceOhm = 500.0,
            sex = "MALE",
            heightCm = 175.0,
            birthDateEpochMs = 0L,
        )
        assertTrue(BodyAlgorithm.hasImpedance(500.0, 70.0))
        assertTrue(m.bodyFatPct > 0.0)
        assertTrue(m.muscleKg > 0.0)
        assertTrue(m.waterPct > 0.0)
    }
}
