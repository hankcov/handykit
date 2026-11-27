package com.handykit.app.feature.fuel

import com.handykit.app.core.db.FuelRecord
import java.time.LocalDate

/** 续航估算依据。NONE 表示没有油量依据，UI 应显示“不可用”。 */
enum class RangeBasis { NONE, TANK, LAST_FUEL }

/** 趋势点：l100 为 null 表示断点（异常区间或用户标记无效区间），图表不得当作 0 绘制。 */
data class TrendPoint(
    val date: LocalDate,
    val l100: Double?,
    val toId: Long,
    val invalid: Boolean,
)

data class VehicleMetrics(
    val currentL100: Double?,
    val deltaFromPrev: Double?,
    val totalDistance: Int,
    val totalCost: Double,
    val avgL100: Double?,
    val rangeKm: Double?,
    val rangeBasis: RangeBasis,
    val expectedRefuelOdometer: Int?,
    val trend: List<TrendPoint>,
    val segments: List<Segment>,
    val trustedCount: Int,
    val anomalyCount: Int,
)

/**
 * 单车指标计算。调用方需保证 records 已按 vehicleId 隔离。
 * 标记为 invalidSegment 的记录仍留在时间线中（保持油量/里程连续），
 * 但它所挂的区间会被视为无效：不计入 currentL100 / avgL100 / trend。
 */
object MetricsCalculator {

    fun calculate(
        records: List<FuelRecord>,
        tankCapacity: Double,
        invalidIds: Set<Long> = emptySet(),
    ): VehicleMetrics? {
        if (records.isEmpty()) return null

        val entries = records.map { it.toEntry() }
        val segments = FuelMath.segments(entries)
        val trusted = segments.filter { it.isTrusted && it.toId !in invalidIds }
            .sortedWith(compareBy<Segment> { it.toDate }.thenBy { it.toId })

        val current = trusted.lastOrNull()
        val previous = if (trusted.size >= 2) trusted[trusted.size - 2] else null
        val currentL100 = current?.l100?.let(FuelMath::round2)
        val deltaFromPrev = if (current != null && previous != null) {
            FuelMath.round2(current.l100!! - previous.l100!!)
        } else {
            null
        }

        val totalDistance = (entries.maxOf { it.odometer } - entries.minOf { it.odometer }).coerceAtLeast(0)
        val totalCost = FuelMath.round2(records.sumOf { it.amount })

        val fuelSum = trusted.sumOf { it.fuel }
        val distanceSum = trusted.sumOf { it.distance }
        val avgL100 = if (distanceSum > 0 && fuelSum > 0.0) {
            FuelMath.round2(fuelSum / distanceSum * 100.0)
        } else {
            null
        }

        val last = entries.maxWith(compareBy<FuelEntry> { it.date }.thenBy { it.id })
        val effectiveL100 = current?.l100?.takeIf { it > 0.0 && it.isFinite() }
        val (rangeBasis, rawRange) = when {
            effectiveL100 != null && tankCapacity > 0.0 ->
                RangeBasis.TANK to (tankCapacity / effectiveL100 * 100.0)
            effectiveL100 != null && last.liters > 0.0 ->
                RangeBasis.LAST_FUEL to (last.liters / effectiveL100 * 100.0)
            else -> RangeBasis.NONE to null
        }
        val rangeKm = rawRange?.takeIf { it.isFinite() && it > 0.0 }?.let(FuelMath::round2)
        val expectedRefuelOdometer = rangeKm?.let { last.odometer + Math.round(it).toInt() }

        val trend = segments.sortedWith(compareBy<Segment> { it.toDate }.thenBy { it.toId })
            .map { s ->
                val invalid = s.toId in invalidIds
                TrendPoint(
                    date = s.toDate,
                    l100 = if (!invalid && s.isTrusted) s.l100 else null,
                    toId = s.toId,
                    invalid = invalid,
                )
            }

        return VehicleMetrics(
            currentL100 = currentL100,
            deltaFromPrev = deltaFromPrev,
            totalDistance = totalDistance,
            totalCost = totalCost,
            avgL100 = avgL100,
            rangeKm = rangeKm,
            rangeBasis = rangeBasis,
            expectedRefuelOdometer = expectedRefuelOdometer,
            trend = trend,
            segments = segments,
            trustedCount = trusted.size,
            anomalyCount = segments.size - trusted.size,
        )
    }
}
