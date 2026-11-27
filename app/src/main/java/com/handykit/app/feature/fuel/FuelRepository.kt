package com.handykit.app.feature.fuel

import android.content.Context
import androidx.room.withTransaction
import com.handykit.app.core.db.AppDatabase
import com.handykit.app.core.db.FuelRecord
import com.handykit.app.core.db.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class ImportSummary(
    val imported: Int,
    val duplicates: Int,
    val badRows: Int,
    val newVehicles: Int,
)

/** 封装 Room DAO。所有依赖由外部传入 applicationContext 构造，不使用 Hilt。 */
class FuelRepository(private val db: AppDatabase) {

    constructor(context: Context) : this(AppDatabase.get(context.applicationContext))

    private val vehicleDao = db.vehicleDao()
    private val fuelDao = db.fuelDao()

    fun observeVehicles(): Flow<List<Vehicle>> = vehicleDao.observeAll()

    suspend fun vehicles(): List<Vehicle> = vehicleDao.all()

    suspend fun vehicle(id: Long): Vehicle? = vehicleDao.get(id)

    /**
     * 单车数据 + 指标。使用 observeAll 而非 observeValid：
     * 被标记 invalidSegment 的记录必须留在时间线中以保证油量/里程连续，
     * 但它挂的区间在 MetricsCalculator 中被判为无效，不参与指标与趋势。
     */
    fun observeVehicleData(
        vehicleId: Long,
        tankCapacity: Double,
    ): Flow<Pair<List<FuelRecord>, VehicleMetrics?>> =
        fuelDao.observeAll(vehicleId).map { records ->
            val invalidIds = records.filter { it.invalidSegment }.map { it.id }.toSet()
            records to MetricsCalculator.calculate(records, tankCapacity, invalidIds)
        }

    suspend fun records(vehicleId: Long): List<FuelRecord> = fuelDao.byVehicle(vehicleId)

    /** 同车 + 同日期 + 同里程视为重复；编辑时排除自身。 */
    suspend fun duplicateCount(
        vehicleId: Long,
        date: LocalDate,
        odometer: Int,
        excludeId: Long = 0L,
    ): Int = fuelDao.byVehicle(vehicleId).count {
        it.id != excludeId && it.date == date && it.odometer == odometer
    }

    suspend fun saveRecord(record: FuelRecord): Long = withContext(Dispatchers.IO) {
        if (record.id == 0L) {
            fuelDao.insert(record.copy(id = 0L))
        } else {
            fuelDao.update(record)
            record.id
        }
    }

    suspend fun deleteRecord(id: Long) = withContext(Dispatchers.IO) {
        fuelDao.deleteById(id)
    }

    suspend fun saveVehicle(vehicle: Vehicle): Long = withContext(Dispatchers.IO) {
        if (vehicle.id == 0L) vehicleDao.insert(vehicle.copy(id = 0L)) else {
            vehicleDao.update(vehicle)
            vehicle.id
        }
    }

    suspend fun deleteVehicle(id: Long) = withContext(Dispatchers.IO) {
        vehicleDao.deleteById(id)
    }

    suspend fun exportCsv(): String = withContext(Dispatchers.IO) {
        val vehicles = vehicleDao.all().associateBy { it.id }
        val rows = fuelDao.all().mapNotNull { record ->
            vehicles[record.vehicleId]?.let { CsvCodec.ExportRow(it, record) }
        }
        CsvCodec.encode(rows)
    }

    /** 原子导入：整批写入同一个事务，任何异常都回滚。 */
    suspend fun importCsv(text: String): ImportSummary = withContext(Dispatchers.IO) {
        db.withTransaction {
            val parsed = CsvCodec.parse(text)
            val byPlate = linkedMapOf<String, Vehicle>()
            vehicleDao.all().forEach { byPlate.putIfAbsent(it.plate.trim(), it) }

            val occupied = fuelDao.all()
                .map { Triple(it.vehicleId, it.date, it.odometer) }
                .toHashSet()

            val pending = mutableListOf<FuelRecord>()
            var duplicates = 0
            var newVehicles = 0

            for (row in parsed.rows) {
                val plate = row.plate.trim()
                val vehicle = byPlate[plate] ?: run {
                    val id = vehicleDao.insert(
                        Vehicle(
                            name = row.vehicleName.ifBlank { plate.ifBlank { "导入车辆" } },
                            plate = plate,
                            tankCapacity = row.tank,
                        )
                    )
                    val created = Vehicle(
                        id = id,
                        name = row.vehicleName.ifBlank { plate.ifBlank { "导入车辆" } },
                        plate = plate,
                        tankCapacity = row.tank,
                    )
                    byPlate[plate] = created
                    newVehicles++
                    created
                }

                if (!occupied.add(Triple(vehicle.id, row.date, row.odometer))) {
                    duplicates++
                    continue
                }
                pending.add(
                    FuelRecord(
                        vehicleId = vehicle.id,
                        date = row.date,
                        odometer = row.odometer,
                        liters = row.liters,
                        amount = row.amount,
                        price = row.price,
                        isFull = row.isFull,
                        station = row.station,
                        estimated = row.estimated,
                    )
                )
            }
            if (pending.isNotEmpty()) fuelDao.insertAll(pending)

            ImportSummary(
                imported = pending.size,
                duplicates = duplicates,
                badRows = parsed.badRows,
                newVehicles = newVehicles,
            )
        }
    }
}
