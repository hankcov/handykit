package com.handykit.app.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "vehicle", indices = [Index(value = ["plate"])])
data class Vehicle(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val plate: String = "",
    /** 油箱容积 L，0 表示未标注 */
    val tankCapacity: Double = 0.0,
    val fuelGrade: String = "92",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "fuel_record",
    foreignKeys = [ForeignKey(
        entity = Vehicle::class,
        parentColumns = ["id"],
        childColumns = ["vehicleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("vehicleId"), Index(value = ["vehicleId", "odometer"])],
)
data class FuelRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val date: LocalDate,
    /** 绝对里程 km */
    val odometer: Int,
    val liters: Double,
    val amount: Double,
    val price: Double,
    val isFull: Boolean = true,
    val station: String? = null,
    val note: String? = null,
    /** 升数由默认油价反推，非用户实填 */
    val estimated: Boolean = false,
    /** 用户标记漏记导致的无效区间 */
    val invalidSegment: Boolean = false,
)
