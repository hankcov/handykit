package com.handykit.app.feature.scale

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * BLE 体脂秤客户端。权限由 UI 层申请后调用本类；所有蓝牙 API 调用都标注 MissingPermission 抑制。
 *
 * 生命周期：startScan -> connect -> discoverServices -> 订阅通知 -> （AFU）握手 -> 解析推送。
 * 扫描有超时；断链最多自动重连 [MAX_RECONNECT] 次；离开模块调用 [close] 后不再后台重连。
 */
@SuppressLint("MissingPermission")
class BleScaleClient(context: Context) {

    enum class ConnectionState {
        IDLE, SCANNING, CONNECTING, CONNECTED, MEASURING, DISCONNECTED, FAILED,
    }

    data class DiscoveredDevice(val address: String, val name: String?, val rssi: Int)

    /** 一次稳定读数，供 ViewModel 落库。 */
    data class StableReading(
        val weightKg: Double,
        val impedanceOhm: Double?,
        val protocolName: String,
        val measuredAtEpochMs: Long,
    )

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _weight = MutableStateFlow<Double?>(null)
    val weight: StateFlow<Double?> = _weight.asStateFlow()

    private val _isStable = MutableStateFlow(false)
    val isStable: StateFlow<Boolean> = _isStable.asStateFlow()

    private val _impedance = MutableStateFlow<Double?>(null)
    val impedance: StateFlow<Double?> = _impedance.asStateFlow()

    private val _protocol = MutableStateFlow("")
    val protocol: StateFlow<String> = _protocol.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _stableReadings = MutableSharedFlow<StableReading>(extraBufferCapacity = 1)
    val stableReadings: SharedFlow<StableReading> = _stableReadings.asSharedFlow()

    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanJob: Job? = null
    private var gatt: BluetoothGatt? = null
    private var preferredModel: ScaleModel = ScaleModel.AUTO
    private var autoReconnect = false
    private var reconnectAttempts = 0
    private var stableConsumed = false
    /** sessionId -> 是否已收录；同一轮广播（设备+秒级时间+体重）只保留一条。 */
    private val seenSessionKeys = LinkedHashMap<String, Boolean>()

    // ------------------------------------------------------------------
    // 对外操作
    // ------------------------------------------------------------------

    fun startScan(preferred: ScaleModel) {
        val a = adapter
        if (a == null) {
            postState(ConnectionState.FAILED, "本机蓝牙不可用")
            return
        }
        autoReconnect = true
        reconnectAttempts = 0
        stableConsumed = false
        preferredModel = preferred
        _discoveredDevices.value = emptyList()
        seenSessionKeys.clear()
        resetLiveValues()
        stopScanInternal()
        closeGattInternal()

        val leScanner = a.bluetoothLeScanner
        if (leScanner == null) {
            postState(ConnectionState.FAILED, "扫描器不可用")
            return
        }
        scanner = leScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()
        leScanner.startScan(emptyList(), settings, scanCallback)
        _connectionState.value = ConnectionState.SCANNING
        _status.value = "正在扫描体脂秤…（${preferred.displayName}）"

        scanJob?.cancel()
        scanJob = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_connectionState.value == ConnectionState.SCANNING) {
                stopScanInternal()
                postState(ConnectionState.FAILED, "扫描超时，未发现体脂秤")
            }
        }
    }

    fun connect(device: DiscoveredDevice, preferred: ScaleModel) {
        val a = adapter
        if (a == null) {
            postState(ConnectionState.FAILED, "本机蓝牙不可用")
            return
        }
        autoReconnect = true
        reconnectAttempts = 0
        stableConsumed = false
        preferredModel = preferred
        stopScanInternal()
        closeGattInternal()
        resetLiveValues()
        _connectionState.value = ConnectionState.CONNECTING
        _status.value = "正在连接 ${device.name ?: device.address}…"
        val remote = a.getRemoteDevice(device.address)
        gatt = remote.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) postState(ConnectionState.FAILED, "连接失败，请重试")
    }

    /** 取消当前扫描/连接/重连。 */
    fun cancel() {
        autoReconnect = false
        stopScanInternal()
        closeGattInternal()
        resetLiveValues()
        postState(ConnectionState.IDLE, "已取消")
    }

    /** 离开模块时调用：取消一切并释放协程。 */
    fun close() {
        autoReconnect = false
        stopScanInternal()
        closeGattInternal()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            stopScanInternal()
            postState(ConnectionState.FAILED, "扫描失败（错误码 $errorCode）")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val address = result.device.address
        val name = record.deviceName
        val serviceUuids = record.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        val manufacturerIds = mutableListOf<Int>()
        val manufacturerData = mutableMapOf<Int, ByteArray>()
        record.manufacturerSpecificData?.let { sparse ->
            for (i in 0 until sparse.size()) {
                val id = sparse.keyAt(i)
                val payload = sparse.valueAt(i)
                manufacturerIds.add(id)
                if (payload != null) manufacturerData[id] = payload
            }
        }
        val serviceData = record.serviceData?.mapKeys { it.key.uuid.toString() } ?: emptyMap()

        if (!PacketParser.isScaleCandidate(serviceUuids, manufacturerIds, name, preferredModel)) return
        val parsed = PacketParser.parseAdvertisementBytes(serviceData, manufacturerData, preferredModel)

        // 同一 sessionId（设备 + 秒级时间 + 体重）重复广播只保存一条。
        val weightPart = parsed?.weightKg?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
        // ScanResult 只有纳秒时间戳（API 26+），按秒分桶做 sessionId 去重。
        val sessionKey = "$address|${result.timestampNanos / 1_000_000_000L}|$weightPart"
        if (seenSessionKeys.containsKey(sessionKey)) return
        if (seenSessionKeys.size > 200) seenSessionKeys.clear()
        seenSessionKeys[sessionKey] = true

        val entry = DiscoveredDevice(address, name, result.rssi)
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.address == address }
        if (index >= 0) current[index] = entry else current.add(entry)
        _discoveredDevices.value = current.sortedByDescending { it.rssi }.take(10)

        if (parsed != null) {
            _weight.value = parsed.weightKg
            _impedance.value = parsed.impedanceOhm
            _protocol.value = parsed.protocolName
            _status.value = "发现 ${name ?: "体脂秤"}：${String.format(Locale.US, "%.2f", parsed.weightKg)} kg，点击连接"
        } else {
            _status.value = "发现候选设备 ${name ?: address}，点击连接"
        }
    }

    private fun stopScanInternal() {
        scanner?.stopScan(scanCallback)
        scanner = null
        scanJob?.cancel()
        scanJob = null
    }

    // ------------------------------------------------------------------
    // GATT
    // ------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                gatt.close()
                if (gatt == this@BleScaleClient.gatt) this@BleScaleClient.gatt = null
                scheduleReconnect(gatt.device.address)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _status.value = "已连接，正在发现服务…"
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    gatt.close()
                    if (gatt == this@BleScaleClient.gatt) this@BleScaleClient.gatt = null
                    scheduleReconnect(gatt.device.address)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postState(ConnectionState.FAILED, "服务发现失败（$status）")
                return
            }
            val notifyChars = mutableListOf<BluetoothGattCharacteristic>()
            val writeChars = mutableListOf<BluetoothGattCharacteristic>()
            for (service in gatt.services) {
                for (c in service.characteristics) {
                    if ((c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) notifyChars.add(c)
                    if ((c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) writeChars.add(c)
                }
            }
            if (notifyChars.isEmpty()) {
                postState(ConnectionState.FAILED, "未找到可订阅的通知特征")
                return
            }
            val notifyChar = pickNotifyCharacteristic(notifyChars, preferredModel)
            subscribe(gatt, notifyChar)

            // AFU 协议握手：0xFFB1 写 FD 37 00...37。
            if (preferredModel == ScaleModel.AFU_PROTOCOL || PacketParser.shortUuid(notifyChar.uuid.toString()) == "FFB1") {
                val writeChar = writeChars.firstOrNull { PacketParser.shortUuid(it.uuid.toString()) == "FFB1" }
                    ?: writeChars.firstOrNull()
                if (writeChar != null) writeHandshake(gatt, writeChar)
            }

            _connectionState.value = ConnectionState.CONNECTED
            _status.value = "连接成功，请赤脚上秤并保持静止"
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic.uuid.toString(), value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: byteArrayOf()
            handleNotification(characteristic.uuid.toString(), value)
        }
    }

    private fun pickNotifyCharacteristic(
        chars: List<BluetoothGattCharacteristic>,
        model: ScaleModel,
    ): BluetoothGattCharacteristic {
        val preferredUuids = when (model) {
            ScaleModel.AFU_PROTOCOL -> setOf("FFB1", "FFB2")
            ScaleModel.BOOHEE_YOLANDA -> setOf("FFE1", "FFE4")
            ScaleModel.OKOK_CHIPSEA -> setOf("FFF1", "FFF2")
            ScaleModel.SENSSUN -> setOf("FF00", "FF01")
            ScaleModel.SIG_STANDARD -> setOf("2A9C", "2A9D", "2A9B")
            ScaleModel.XIAOMI_SCALE_2, ScaleModel.XIAOMI_SCALE_1 -> setOf("2A9D", "2A9C")
            ScaleModel.AUTO -> emptySet()
        }
        return chars.firstOrNull { PacketParser.shortUuid(it.uuid.toString()) in preferredUuids } ?: chars.first()
    }

    @Suppress("DEPRECATION")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeHandshake(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val command = byteArrayOf(
            0xFD.toByte(), 0x37, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x37,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.value = command
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun handleNotification(uuid: String, value: ByteArray) {
        val parsed = PacketParser.parseNotification(value, uuid, preferredModel) ?: return
        _weight.value = parsed.weightKg
        _isStable.value = parsed.isStable
        _impedance.value = parsed.impedanceOhm
        _protocol.value = parsed.protocolName
        if (parsed.isStable) {
            _connectionState.value = ConnectionState.MEASURING
            _status.value = if (parsed.impedanceOhm != null) "测量完成（含阻抗）" else "测量完成（无阻抗）"
            if (!stableConsumed) {
                stableConsumed = true
                // 读数已拿到，主动停止重连，避免模块内反复后台连接。
                autoReconnect = false
                _stableReadings.tryEmit(
                    StableReading(
                        weightKg = parsed.weightKg,
                        impedanceOhm = parsed.impedanceOhm,
                        protocolName = parsed.protocolName,
                        measuredAtEpochMs = System.currentTimeMillis(),
                    )
                )
            }
        } else {
            _connectionState.value = ConnectionState.CONNECTED
            _status.value = "测量中… ${String.format(Locale.US, "%.2f", parsed.weightKg)} kg"
        }
    }

    private fun scheduleReconnect(address: String) {
        if (!autoReconnect) return
        if (reconnectAttempts >= MAX_RECONNECT) {
            postState(ConnectionState.FAILED, "连接失败，已停止重试（最多 $MAX_RECONNECT 次）")
            return
        }
        reconnectAttempts++
        val attempt = reconnectAttempts
        _status.value = "连接中断，正在重连（$attempt/$MAX_RECONNECT）…"
        scope.launch {
            delay(400L * attempt)
            if (!autoReconnect) return@launch
            val a = adapter ?: return@launch
            _connectionState.value = ConnectionState.CONNECTING
            gatt = a.getRemoteDevice(address).connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun closeGattInternal() {
        gatt?.close()
        gatt = null
    }

    private fun resetLiveValues() {
        _weight.value = null
        _isStable.value = false
        _impedance.value = null
        _protocol.value = ""
        stableConsumed = false
    }

    private fun postState(state: ConnectionState, message: String) {
        _connectionState.value = state
        _status.value = message
    }

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val MAX_RECONNECT = 3
    }
}
