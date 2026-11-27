package com.handykit.app.feature.fuel

import com.handykit.app.core.db.FuelRecord
import java.time.LocalDate

/** 区间异常原因：里程非严格递增，或算出的百公里油耗超出合理范围。 */
enum class SegmentAnomaly { ODOMETER, OUT_OF_RANGE }

/** 满箱法的最小输入：一条加油记录。 */
data class FuelEntry(
    val id: Long,
    val date: LocalDate,
    val odometer: Int,
    val liters: Double,
    val amount: Double,
    val isFull: Boolean,
)

/**
 * 两个加满事件之间的完整区间。
 * fuel 含终点记录、不含起点满箱记录；区间挂在终点记录上（toId）。
 */
data class Segment(
    val fromId: Long,
    val toId: Long,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val fromOdometer: Int,
    val toOdometer: Int,
    val distance: Int,
    val fuel: Double,
    val l100: Double?,
    val anomaly: SegmentAnomaly?,
) {
    val isTrusted: Boolean get() = anomaly == null && l100 != null
}

/**
 * 纯 JVM 满箱法算法，不依赖 Android / Room。
 *
 * 注意：调用方必须先按 vehicleId 分组，再把单车记录转成 List<FuelEntry> 传入。
 */
object FuelMath {
    const val MIN_PLAUSIBLE_L100 = 1.0
    const val MAX_PLAUSIBLE_L100 = 25.0

    fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0

    /**
     * 按 (date, id) 时间线切分区间。
     * - 只有加满事件成段；中间未加满的油量累加进区间，自身不产出油耗点。
     * - 里程必须严格递增，否则该事件产出 ODOMETER 异常段且不推进锚点。
     * - 百公里油耗超 [MIN_PLAUSIBLE_L100, MAX_PLAUSIBLE_L100] 产出 OUT_OF_RANGE 异常段。
     * 里程异常时 l100 为 null，不返回 0。
     */
    fun segments(entries: List<FuelEntry>): List<Segment> {
        val timeline = entries.sortedWith(compareBy<FuelEntry> { it.date }.thenBy { it.id })
        if (timeline.isEmpty()) return emptyList()

        // 标记里程回退：odo <= 历史最大值即异常；异常记录不参与推进最大值。
        val rejected = BooleanArray(timeline.size)
        var maxOdo = Int.MIN_VALUE
        for (i in timeline.indices) {
            val odo = timeline[i].odometer
            if (odo <= maxOdo) {
                rejected[i] = true
            } else {
                maxOdo = odo
            }
        }

        fun sumLiters(fromExclusive: Int, toInclusive: Int): Double {
            var sum = 0.0
            for (k in fromExclusive + 1..toInclusive) sum += timeline[k].liters
            return sum
        }

        val result = mutableListOf<Segment>()
        var anchor: Int? = null

        for (i in timeline.indices) {
            val current = timeline[i]
            if (rejected[i]) {
                val a = anchor?.let { timeline[it] }
                if (a != null) {
                    result.add(
                        Segment(
                            fromId = a.id,
                            toId = current.id,
                            fromDate = a.date,
                            toDate = current.date,
                            fromOdometer = a.odometer,
                            toOdometer = current.odometer,
                            distance = current.odometer - a.odometer,
                            fuel = sumLiters(anchor!!, i),
                            l100 = null,
                            anomaly = SegmentAnomaly.ODOMETER,
                        )
                    )
                }
                // 不推进锚点：后续正常加满仍从原锚点计算。
                continue
            }

            if (!current.isFull) continue
            val anchorIndex = anchor
            if (anchorIndex == null) {
                anchor = i
                continue
            }

            val a = timeline[anchorIndex]
            val distance = current.odometer - a.odometer
            val fuel = sumLiters(anchorIndex, i)
            val l100 = if (distance > 0 && fuel > 0.0) fuel / distance * 100.0 else null
            val anomaly = when {
                l100 == null -> null
                l100 < MIN_PLAUSIBLE_L100 || l100 > MAX_PLAUSIBLE_L100 -> SegmentAnomaly.OUT_OF_RANGE
                else -> null
            }
            result.add(
                Segment(
                    fromId = a.id,
                    toId = current.id,
                    fromDate = a.date,
                    toDate = current.date,
                    fromOdometer = a.odometer,
                    toOdometer = current.odometer,
                    distance = distance,
                    fuel = fuel,
                    l100 = l100,
                    anomaly = anomaly,
                )
            )
            anchor = i
        }
        return result
    }

    /** 显式按车辆分组后再成段，供多车场景或测试使用。 */
    fun segmentsByVehicle(records: List<FuelRecord>): Map<Long, List<Segment>> =
        records.groupBy { it.vehicleId }.mapValues { (_, rs) -> segments(rs.map { it.toEntry() }) }
}

fun FuelRecord.toEntry(): FuelEntry = FuelEntry(id, date, odometer, liters, amount, isFull)

/**
 * 升数 / 金额 / 单价三项：填任意两项推算第三项；三项都填但不一致时提示冲突。
 * 纯 JVM，便于单测。
 */
object PriceFields {
    enum class DerivedField { NONE, LITERS, AMOUNT, PRICE }

    data class Result(
        val liters: Double?,
        val amount: Double?,
        val price: Double?,
        val derived: DerivedField,
        val conflict: Boolean,
        /** 填了不足两项，无法推算。 */
        val insufficient: Boolean,
    )

    private fun positive(x: Double?): Double? = x?.takeIf { it > 0.0 && it.isFinite() }

    /**
     * @param tolerance 三项手填时允许的金额误差（元），默认 0.02。
     */
    fun resolve(liters: Double?, amount: Double?, price: Double?, tolerance: Double = 0.02): Result {
        val l = positive(liters)
        val a = positive(amount)
        val p = positive(price)
        val count = listOf(l, a, p).count { it != null }

        if (count < 2) return Result(l, a, p, DerivedField.NONE, false, true)
        if (count == 3) {
            val conflict = kotlin.math.abs(l!! * p!! - a!!) > tolerance
            return Result(l, a, p, DerivedField.NONE, conflict, false)
        }
        return when {
            l != null && a != null -> Result(l, a, a / l, DerivedField.PRICE, false, false)
            l != null && p != null -> Result(l, l * p, p, DerivedField.AMOUNT, false, false)
            else -> Result(a!! / p!!, a, p, DerivedField.LITERS, false, false)
        }
    }
}
