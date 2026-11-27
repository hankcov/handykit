package com.handykit.app.feature.fuel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.handykit.app.core.db.FuelRecord
import com.handykit.app.core.db.Vehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface FuelEffect {
    data class Toast(val message: String) : FuelEffect
    data class ConfirmSave(val record: FuelRecord, val warnings: List<String>) : FuelEffect
}

class FuelViewModel(private val repo: FuelRepository) : ViewModel() {

    private val selectedId = MutableStateFlow(0L)

    val vehicles: StateFlow<List<Vehicle>> = repo.observeVehicles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentVehicle: StateFlow<Vehicle?> = combine(vehicles, selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前车辆的全部记录 + 单车指标；空车时指标为 null。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val data: StateFlow<Pair<List<FuelRecord>, VehicleMetrics?>> =
        combine(vehicles, selectedId) { list, id ->
            val vehicle = list.firstOrNull { it.id == id } ?: list.firstOrNull()
            Pair(vehicle?.id ?: 0L, vehicle?.tankCapacity ?: 0.0)
        }.flatMapLatest { (id, tank) ->
            if (id <= 0L) {
                flowOf(Pair(emptyList<FuelRecord>(), null))
            } else {
                repo.observeVehicleData(id, tank)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            Pair(emptyList<FuelRecord>(), null),
        )

    private val _effects = MutableSharedFlow<FuelEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<FuelEffect> = _effects.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun selectVehicle(id: Long) {
        selectedId.value = id
    }

    /**
     * 显式校验：重复、里程回退、超阈值。用户确认后仍可保留异常记录。
     * 被标记 invalidSegment 的记录本身就是异常区间的声明，不再重复提示超阈值。
     */
    fun validate(record: FuelRecord): List<String> {
        val warnings = mutableListOf<String>()
        if (record.vehicleId <= 0L) warnings += "请先选择车辆。"
        if (record.liters <= 0.0) warnings += "升数必须大于 0。"
        if (record.amount < 0.0 || record.price < 0.0) warnings += "金额和单价不能为负数。"

        val others = data.value.first.filter { it.id != record.id }
        if (others.any { it.date == record.date && it.odometer == record.odometer }) {
            warnings += "同车同日期同里程已存在记录，确认不是重复录入吗？"
        }
        val maxOdo = others.maxOfOrNull { it.odometer }
        if (maxOdo != null && record.odometer <= maxOdo) {
            warnings += "里程回退：${record.odometer} km 不高于已有最大里程 $maxOdo km。"
        }
        if (!record.invalidSegment) {
            val probe = (others + record).map { it.toEntry() }
            val segment = FuelMath.segments(probe).lastOrNull { it.toId == record.id }
            val value = segment?.l100
            if (value != null && (value < FuelMath.MIN_PLAUSIBLE_L100 || value > FuelMath.MAX_PLAUSIBLE_L100)) {
                warnings += "本次区间油耗 ${FuelMath.round2(value)} L/100km 超出合理范围（1–25），可能漏记加油，继续将保留异常。"
            }
        }
        return warnings
    }

    fun commitRecord(record: FuelRecord, force: Boolean = false) {
        if (!force) {
            val warnings = validate(record)
            if (warnings.isNotEmpty()) {
                _effects.tryEmit(FuelEffect.ConfirmSave(record, warnings))
                return
            }
        }
        viewModelScope.launch {
            repo.saveRecord(record)
            _effects.emit(FuelEffect.Toast(if (record.id == 0L) "已记一笔" else "记录已更新，指标已重算"))
        }
    }

    fun deleteRecord(id: Long) {
        viewModelScope.launch {
            repo.deleteRecord(id)
            _effects.emit(FuelEffect.Toast("已删除，指标已重算"))
        }
    }

    fun saveVehicle(vehicle: Vehicle) {
        viewModelScope.launch {
            val id = repo.saveVehicle(vehicle)
            if (vehicle.id == 0L) selectedId.value = id
            _effects.emit(FuelEffect.Toast("车辆已保存"))
        }
    }

    fun deleteVehicle(id: Long) {
        viewModelScope.launch {
            repo.deleteVehicle(id)
            if (selectedId.value == id) selectedId.value = 0L
            _effects.emit(FuelEffect.Toast("车辆及其记录已删除"))
        }
    }

    suspend fun buildCsv(): String = repo.exportCsv()

    fun importCsv(text: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val summary = repo.importCsv(text)
                val message = buildString {
                    append("导入 ${summary.imported} 条")
                    if (summary.newVehicles > 0) append("，新增车辆 ${summary.newVehicles} 辆")
                    if (summary.duplicates > 0) append("，跳过重复 ${summary.duplicates} 条")
                    if (summary.badRows > 0) append("，坏行 ${summary.badRows} 条")
                }
                _effects.emit(FuelEffect.Toast(message))
            } catch (e: Exception) {
                _effects.emit(FuelEffect.Toast("导入失败，已回滚：${e.message ?: "未知错误"}"))
            } finally {
                _busy.value = false
            }
        }
    }
}

class FuelViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FuelViewModel::class.java)) {
            return FuelViewModel(FuelRepository(context.applicationContext)) as T
        }
        throw IllegalArgumentException("未支持的 ViewModel: ${modelClass.name}")
    }
}

/** 录入表单的价格推算结果，供 UI 展示来源与冲突提示。 */
data class PriceDraft(
    val litersText: String = "",
    val amountText: String = "",
    val priceText: String = "",
)

fun PriceDraft.resolve(): PriceFields.Result = PriceFields.resolve(
    liters = litersText.toDoubleOrNull(),
    amount = amountText.toDoubleOrNull(),
    price = priceText.toDoubleOrNull(),
)
