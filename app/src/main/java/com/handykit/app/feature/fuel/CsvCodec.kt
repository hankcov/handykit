package com.handykit.app.feature.fuel

import com.handykit.app.core.db.FuelRecord
import com.handykit.app.core.db.Vehicle
import java.time.LocalDate
import java.util.Locale

/**
 * 加油记录 CSV。手写 RFC4180 子集：双引号包裹含逗号/引号/换行的字段，内部引号写成 ""。
 * 导入按列名定位，允许缺列（缺列取默认值），列顺序可变。
 */
object CsvCodec {
    const val HEADER =
        "vehicle,vehicle_name,date,odometer,liters,amount,price,is_full,station,estimated,tank"

    data class Row(
        val plate: String,
        val vehicleName: String,
        val date: LocalDate,
        val odometer: Int,
        val liters: Double,
        val amount: Double,
        val price: Double,
        val isFull: Boolean,
        val station: String?,
        val estimated: Boolean,
        val tank: Double,
    )

    data class Parsed(val rows: List<Row>, val badRows: Int)

    private val ALIASES = mapOf(
        "plate" to "vehicle",
        "car" to "vehicle",
        "car_plate" to "vehicle",
        "name" to "vehicle_name",
        "car_name" to "vehicle_name",
        "odo" to "odometer",
        "mileage" to "odometer",
        "volume" to "liters",
        "fuel" to "liters",
        "total" to "amount",
        "unit_price" to "price",
        "full" to "is_full",
        "station_name" to "station",
        "tank_capacity" to "tank",
    )

    /** 解析为二维表；不依赖第三方 CSV 库。 */
    fun parseTable(text: String): List<List<String>> {
        val source = text.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < source.length) {
            val c = source[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < source.length && source[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                }
                c == '"' && field.isEmpty() -> inQuotes = true
                c == ',' -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                c == '\n' || c == '\r' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row.toList())
                    row.clear()
                    if (c == '\r' && i + 1 < source.length && source[i + 1] == '\n') i++
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (row.any { it.isNotBlank() }) rows.add(row.toList())
        }
        return rows
    }

    fun parse(text: String): Parsed {
        val table = parseTable(text)
        if (table.isEmpty()) return Parsed(emptyList(), 0)

        val header = table.first().map { normalize(it) }
        val known = setOf(
            "vehicle", "vehicle_name", "date", "odometer", "liters", "amount",
            "price", "is_full", "station", "estimated", "tank",
        )
        val columns = header.map { ALIASES[it] ?: it }
        if (columns.none { it in known }) {
            // 没有可识别的表头，整表按坏行处理，不写入数据库。
            return Parsed(emptyList(), table.size)
        }
        val index = columns.mapIndexed { i, name -> name to i }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, positions) -> positions.first() }

        fun cell(cells: List<String>, key: String): String {
            val at = index[key] ?: return ""
            return cells.getOrNull(at)?.trim().orEmpty()
        }

        val rows = mutableListOf<Row>()
        var badRows = 0
        for (cells in table.drop(1)) {
            if (cells.all { it.isBlank() }) continue
            val dateText = cell(cells, "date")
            val odoText = cell(cells, "odometer")
            val litersText = cell(cells, "liters")

            val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
            val odometer = odoText.toIntOrNull()
            val liters = litersText.toDoubleOrNull()
            if (date == null || odometer == null || odometer < 0 || liters == null || liters <= 0.0) {
                badRows++
                continue
            }
            var amount = cell(cells, "amount").toDoubleOrNull() ?: 0.0
            var price = cell(cells, "price").toDoubleOrNull() ?: 0.0
            if (amount <= 0.0 && price > 0.0) amount = liters * price
            if (price <= 0.0 && amount > 0.0) price = amount / liters
            if (amount < 0.0) amount = 0.0
            if (price < 0.0) price = 0.0
            if (liters <= 0.0 || !liters.isFinite() || !amount.isFinite() || !price.isFinite()) {
                badRows++
                continue
            }

            rows.add(
                Row(
                    plate = cell(cells, "vehicle"),
                    vehicleName = cell(cells, "vehicle_name"),
                    date = date,
                    odometer = odometer,
                    liters = liters,
                    amount = FuelMath.round2(amount),
                    price = FuelMath.round2(price),
                    isFull = parseBool(cell(cells, "is_full"), default = true),
                    station = cell(cells, "station").ifBlank { null },
                    estimated = parseBool(cell(cells, "estimated"), default = false),
                    tank = (cell(cells, "tank").toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                )
            )
        }
        return Parsed(rows, badRows)
    }

    fun encode(data: List<ExportRow>): String = buildString {
        appendLine(HEADER)
        data.forEach { (vehicle, record) ->
            append(escape(vehicle.plate)).append(',')
            append(escape(vehicle.name)).append(',')
            append(escape(record.date.toString())).append(',')
            append(record.odometer).append(',')
            append(format(record.liters)).append(',')
            append(format(record.amount)).append(',')
            append(format(record.price)).append(',')
            append(record.isFull).append(',')
            append(escape(record.station.orEmpty())).append(',')
            append(record.estimated).append(',')
            append(format(vehicle.tankCapacity)).appendLine()
        }
    }

    /** 导出用：一条记录 + 其所属车辆。 */
    data class ExportRow(val vehicle: Vehicle, val record: FuelRecord)

    private fun normalize(s: String): String =
        s.trim().lowercase(Locale.ROOT).removePrefix("\uFEFF")

    private fun parseBool(s: String, default: Boolean): Boolean = when {
        s.isEmpty() -> default
        s.equals("true", ignoreCase = true) || s == "1" ||
            s.equals("yes", ignoreCase = true) || s == "是" -> true
        s.equals("false", ignoreCase = true) || s == "0" ||
            s.equals("no", ignoreCase = true) || s == "否" -> false
        else -> default
    }

    private fun format(x: Double): String {
        if (!x.isFinite()) return "0"
        return if (x == Math.floor(x) && kotlin.math.abs(x) < 1e12) x.toLong().toString() else x.toString()
    }

    private fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return value
        val doubled = value.replace("\"", "\"\"")
        return "\"$doubled\""
    }
}
