package com.handykit.app.feature.scale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.handykit.app.core.db.Measurement
import com.handykit.app.core.db.ScaleMember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 体脂秤页面状态与交互。依赖由外部手动构造，ViewModel 本身不持有 Android Context。 */
class ScaleViewModel(
    private val repository: ScaleRepository,
    private val ble: BleScaleClient,
) : ViewModel() {

    data class UiState(
        val members: List<ScaleMember> = emptyList(),
        val activeMemberId: String? = null,
        val measurements: List<Measurement> = emptyList(),
        val preferredModel: ScaleModel = ScaleModel.AUTO,
        val bleState: BleScaleClient.ConnectionState = BleScaleClient.ConnectionState.IDLE,
        val bleMessage: String = "",
        val liveWeight: Double? = null,
        val liveStable: Boolean = false,
        val liveImpedance: Double? = null,
        val liveProtocol: String = "",
        val devices: List<BleScaleClient.DiscoveredDevice> = emptyList(),
        /** 稳定读数等待用户选择归属（多候选/超阈值）。 */
        val pendingReading: Measurement? = null,
        val pendingCandidates: List<ScaleMember> = emptyList(),
        val memberEditorOpen: Boolean = false,
        val editingMember: ScaleMember? = null,
        val selectedMeasurementId: String? = null,
        val pendingDeleteMeasurementId: String? = null,
        val pendingDeleteMemberId: String? = null,
        val message: String = "",
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.members.collect { list ->
                _uiState.update { state ->
                    val stillValid = list.any { it.id == state.activeMemberId }
                    state.copy(
                        members = list,
                        activeMemberId = if (stillValid) state.activeMemberId else list.firstOrNull()?.id,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.measurements.collect { list -> _uiState.update { it.copy(measurements = list) } }
        }
        viewModelScope.launch {
            ble.connectionState.collect { value -> _uiState.update { it.copy(bleState = value) } }
        }
        viewModelScope.launch {
            ble.status.collect { value -> _uiState.update { it.copy(bleMessage = value) } }
        }
        viewModelScope.launch {
            ble.weight.collect { value -> _uiState.update { it.copy(liveWeight = value) } }
        }
        viewModelScope.launch {
            ble.isStable.collect { value -> _uiState.update { it.copy(liveStable = value) } }
        }
        viewModelScope.launch {
            ble.impedance.collect { value -> _uiState.update { it.copy(liveImpedance = value) } }
        }
        viewModelScope.launch {
            ble.protocol.collect { value -> _uiState.update { it.copy(liveProtocol = value) } }
        }
        viewModelScope.launch {
            ble.discoveredDevices.collect { list -> _uiState.update { it.copy(devices = list) } }
        }
        viewModelScope.launch {
            ble.stableReadings.collect { handleStableReading(it) }
        }
    }

    // ------------------------------------------------------------------
    // 成员
    // ------------------------------------------------------------------

    fun setActiveMember(id: String) {
        _uiState.update { it.copy(activeMemberId = id) }
    }

    fun openMemberEditor(member: ScaleMember?) {
        _uiState.update { it.copy(memberEditorOpen = true, editingMember = member) }
    }

    fun closeMemberEditor() {
        _uiState.update { it.copy(memberEditorOpen = false, editingMember = null) }
    }

    fun saveMember(
        id: String?,
        name: String,
        sex: String,
        heightCm: Double,
        birthDateEpochMs: Long,
        referenceWeightKg: Double,
    ) {
        if (name.isBlank() || heightCm <= 0.0) {
            _uiState.update { it.copy(message = "姓名与身高不能为空") }
            return
        }
        val member = ScaleMember(
            id = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = name.trim(),
            sex = if (sex.equals("FEMALE", ignoreCase = true)) "FEMALE" else "MALE",
            heightCm = heightCm.coerceIn(50.0, 250.0),
            birthDateEpochMs = birthDateEpochMs,
            referenceWeightKg = referenceWeightKg.coerceIn(0.0, 500.0),
        )
        viewModelScope.launch {
            repository.upsertMember(member)
            // 成员参数变化后，历史记录的可重算项同步刷新。
            repository.recalculateMember(member.id)
            _uiState.update {
                it.copy(
                    memberEditorOpen = false,
                    editingMember = null,
                    activeMemberId = member.id,
                    message = "成员已保存：${member.name}",
                )
            }
        }
    }

    fun requestDeleteMember(id: String) {
        _uiState.update { it.copy(pendingDeleteMemberId = id) }
    }

    fun confirmDeleteMember() {
        val id = _uiState.value.pendingDeleteMemberId ?: return
        viewModelScope.launch {
            repository.deleteMemberCascade(id)
            _uiState.update {
                it.copy(
                    pendingDeleteMemberId = null,
                    activeMemberId = if (it.activeMemberId == id) null else it.activeMemberId,
                    message = "成员及其测量记录已删除",
                )
            }
        }
    }

    fun dismissDeleteMember() {
        _uiState.update { it.copy(pendingDeleteMemberId = null) }
    }

    // ------------------------------------------------------------------
    // 扫描与测量
    // ------------------------------------------------------------------

    fun setPreferredModel(model: ScaleModel) {
        _uiState.update { it.copy(preferredModel = model) }
    }

    fun onPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(message = if (granted) "蓝牙权限已授予" else "蓝牙权限被拒绝，仍可查看历史记录")
        }
    }

    fun startScan() {
        val model = _uiState.value.preferredModel
        _uiState.update { it.copy(message = "") }
        ble.startScan(model)
    }

    fun connect(device: BleScaleClient.DiscoveredDevice) {
        ble.connect(device, _uiState.value.preferredModel)
    }

    fun cancelBle() {
        ble.cancel()
        _uiState.update { it.copy(devices = emptyList()) }
    }

    private suspend fun handleStableReading(reading: BleScaleClient.StableReading) {
        val raw = BodyAlgorithm.calculate(
            weightKg = reading.weightKg,
            impedanceOhm = reading.impedanceOhm ?: 0.0,
            sex = "MALE",
            heightCm = DEFAULT_HEIGHT_CM,
            birthDateEpochMs = 0L,
        ).copy(
            id = UUID.randomUUID().toString(),
            measuredAtEpochMs = reading.measuredAtEpochMs,
            source = reading.protocolName,
            algorithmVersion = "bia-v1",
        )

        when (val resolution = repository.resolveMemberByWeight(reading.weightKg, repository.allMembers())) {
            is ScaleRepository.MemberResolution.Bound -> {
                repository.saveMeasurement(raw, resolution.member.id)
                ble.cancel()
                _uiState.update {
                    it.copy(message = "已保存并绑定成员：${resolution.member.name}")
                }
            }
            is ScaleRepository.MemberResolution.NeedChoice -> {
                ble.cancel()
                _uiState.update {
                    it.copy(
                        pendingReading = raw,
                        pendingCandidates = resolution.candidates,
                        message = if (resolution.candidates.isEmpty()) {
                            "请先添加成员，或选择不绑定保存"
                        } else {
                            "检测到多个可能成员或超出 10kg 阈值，请选择归属"
                        },
                    )
                }
            }
        }
    }

    /** 用户确认归属后保存；memberId 为 null 表示不绑定。 */
    fun confirmPendingMember(memberId: String?) {
        val pending = _uiState.value.pendingReading ?: return
        viewModelScope.launch {
            repository.saveMeasurement(pending, memberId)
            _uiState.update {
                it.copy(
                    pendingReading = null,
                    pendingCandidates = emptyList(),
                    message = if (memberId == null) "已保存（未绑定成员）" else "已保存并绑定所选成员",
                )
            }
        }
    }

    fun discardPending() {
        _uiState.update { it.copy(pendingReading = null, pendingCandidates = emptyList()) }
    }

    // ------------------------------------------------------------------
    // 历史与详情
    // ------------------------------------------------------------------

    fun openDetail(id: String) {
        _uiState.update { it.copy(selectedMeasurementId = id) }
    }

    fun closeDetail() {
        _uiState.update { it.copy(selectedMeasurementId = null) }
    }

    fun reassignMeasurement(measurementId: String, memberId: String?) {
        viewModelScope.launch {
            repository.reassignMeasurement(measurementId, memberId)
            _uiState.update {
                it.copy(message = if (memberId == null) "已改为未绑定成员" else "归属已修正并重算")
            }
        }
    }

    fun requestDeleteMeasurement(id: String) {
        _uiState.update { it.copy(pendingDeleteMeasurementId = id) }
    }

    fun confirmDeleteMeasurement() {
        val id = _uiState.value.pendingDeleteMeasurementId ?: return
        viewModelScope.launch {
            repository.deleteMeasurement(id)
            _uiState.update {
                it.copy(
                    pendingDeleteMeasurementId = null,
                    selectedMeasurementId = if (it.selectedMeasurementId == id) null else it.selectedMeasurementId,
                    message = "记录已删除",
                )
            }
        }
    }

    fun dismissDeleteMeasurement() {
        _uiState.update { it.copy(pendingDeleteMeasurementId = null) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = "") }
    }

    override fun onCleared() {
        ble.close()
        super.onCleared()
    }

    companion object {
        /** 未绑定成员时构造原始记录使用的参考身高，仅用于先落库 BMI，绑定后会被重算覆盖。 */
        private const val DEFAULT_HEIGHT_CM = 170.0
    }
}
