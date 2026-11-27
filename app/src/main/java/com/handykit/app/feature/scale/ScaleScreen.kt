package com.handykit.app.feature.scale

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.handykit.app.core.db.Measurement
import com.handykit.app.core.ui.ConfirmDialog
import com.handykit.app.core.ui.EmptyHint
import com.handykit.app.core.ui.InfoCard
import com.handykit.app.core.ui.LabeledField
import com.handykit.app.core.ui.ScreenTitle
import com.handykit.app.core.ui.SectionHeader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 体脂秤入口页。依赖在 remember 的 ViewModel factory 中手动构造（applicationContext）。
 * 权限在此申请；被拒绝时历史/趋势仍可正常使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val vm: ScaleViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ScaleViewModel(ScaleRepository(appContext), BleScaleClient(appContext))
            }
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> vm.onPermissionResult(results.values.all { it }) }

    fun granted(): Boolean = permissions.all {
        ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
    }

    var tab by rememberSaveable { mutableStateOf(0) }

    Column(modifier.fillMaxSize()) {
        ScreenTitle(
            title = "体脂秤",
            subtitle = "BLE 体成分测量 · 八项指标逐项标注来源",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.openMemberEditor(null) }) {
                    Icon(Icons.Filled.Add, contentDescription = "新增成员")
                }
            },
        )
        if (state.message.isNotBlank()) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("测量") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("历史") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("趋势") })
        }

        when (tab) {
            0 -> MeasurementTab(state, vm, appContext, permissions, permissionLauncher, { granted() })
            1 -> HistoryTab(state, vm)
            2 -> TrendTab(state)
        }
    }

    // 成员编辑
    if (state.memberEditorOpen) {
        MemberEditorDialog(state, vm)
    }
    // 多候选 / 超阈值归属确认
    if (state.pendingReading != null) {
        MemberChoiceDialog(state, vm)
    }
    // 删除确认
    state.pendingDeleteMemberId?.let {
        ConfirmDialog(
            title = "删除成员",
            message = "将同时删除该成员的全部测量记录，此操作不可撤销。",
            danger = true,
            onConfirm = { vm.confirmDeleteMember() },
            onDismiss = { vm.dismissDeleteMember() },
        )
    }
    state.pendingDeleteMeasurementId?.let {
        ConfirmDialog(
            title = "删除记录",
            message = "确定删除这条测量记录吗？",
            danger = true,
            onConfirm = { vm.confirmDeleteMeasurement() },
            onDismiss = { vm.dismissDeleteMeasurement() },
        )
    }
    // 历史详情
    state.measurements.firstOrNull { it.id == state.selectedMeasurementId }?.let { detail ->
        DetailDialog(state, vm, detail)
    }
}

// ----------------------------------------------------------------------
// 测量页
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementTab(
    state: ScaleViewModel.UiState,
    vm: ScaleViewModel,
    appContext: Context,
    permissions: Array<String>,
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    granted: () -> Boolean,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
    ) {
        SectionHeader(text = "成员切换与管理")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.members.isEmpty()) {
                Text("暂无成员，点击右上角 + 添加", style = MaterialTheme.typography.bodySmall)
            }
            state.members.forEach { member ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = member.id == state.activeMemberId,
                        onClick = { vm.setActiveMember(member.id) },
                        label = { Text(member.name) },
                    )
                    IconButton(onClick = { vm.openMemberEditor(member) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑 ${member.name}")
                    }
                    IconButton(onClick = { vm.requestDeleteMember(member.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除 ${member.name}")
                    }
                }
            }
        }

        SectionHeader(text = "扫描与连接")
        Column(Modifier.padding(horizontal = 16.dp)) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text("型号：${state.preferredModel.displayName}")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ScaleModel.entries.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(model.displayName)
                                    Text(
                                        model.protocolName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = { vm.setPreferredModel(model); expanded = false },
                        )
                    }
                }
            }
            Text(
                state.preferredModel.wakeGuidance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (granted()) {
                            vm.startScan()
                        } else {
                            permissionLauncher.launch(permissions)
                        }
                    },
                    enabled = state.bleState != BleScaleClient.ConnectionState.SCANNING &&
                        state.bleState != BleScaleClient.ConnectionState.CONNECTING,
                ) {
                    Text(if (granted()) "扫描体脂秤" else "申请蓝牙权限并扫描")
                }
                if (state.bleState == BleScaleClient.ConnectionState.SCANNING ||
                    state.bleState == BleScaleClient.ConnectionState.CONNECTING
                ) {
                    OutlinedButton(onClick = { vm.cancelBle() }) { Text("取消") }
                }
            }
            Text(
                "状态：${state.bleState.name} · ${state.bleMessage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (state.bleState == BleScaleClient.ConnectionState.SCANNING) {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        if (state.devices.isNotEmpty()) {
            SectionHeader(text = "发现的设备（点击连接）")
            state.devices.forEach { device ->
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    onClick = { vm.connect(device) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(device.name ?: "未知设备", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${device.address} · ${device.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionHeader(text = "实时测量（${state.liveProtocol.ifBlank { "等待设备" }}）")
        if (state.liveWeight == null) {
            EmptyHint("扫描并连接体脂秤后，站上秤面即可看到读数。拒绝权限不影响下方历史与趋势。")
        } else {
            LiveMetrics(state)
        }
    }
}

@Composable
private fun LiveMetrics(state: ScaleViewModel.UiState) {
    val weight = state.liveWeight ?: return
    val activeMember = state.members.firstOrNull { it.id == state.activeMemberId }
    val bmi = BodyAlgorithm.calculate(weight, 0.0, "MALE", activeMember?.heightCm ?: 170.0, 0L).bmi
    val hasImpedance = BodyAlgorithm.hasImpedance(state.liveImpedance ?: 0.0, weight)
    val unavailable = "不可用"
    val biaNote = if (hasImpedance) "基于阻抗计算 · BIA 参考值" else "不可用 · 无阻抗不估算"
    val bmr = activeMember?.let {
        BodyAlgorithm.calculate(weight, 0.0, it.sex, it.heightCm, it.birthDateEpochMs).basalMetabolic
    }

    val metrics = listOf(
        Triple("体重", "${fmt(weight, 2)} kg", "设备实测"),
        Triple("BMI", fmt(bmi), if (activeMember != null) "按成员身高计算" else "按参考身高估算"),
        Triple("体脂率", if (hasImpedance) "${fmt(computeForLive(weight, state.liveImpedance, activeMember).bodyFatPct)} %" else unavailable, biaNote),
        Triple("肌肉量", if (hasImpedance) "${fmt(computeForLive(weight, state.liveImpedance, activeMember).muscleKg)} kg" else unavailable, biaNote),
        Triple("水分率", if (hasImpedance) "${fmt(computeForLive(weight, state.liveImpedance, activeMember).waterPct)} %" else unavailable, biaNote),
        Triple("蛋白质率", if (hasImpedance) "${fmt(computeForLive(weight, state.liveImpedance, activeMember).proteinPct)} %" else unavailable, biaNote),
        Triple("骨量", if (hasImpedance) "${fmt(computeForLive(weight, state.liveImpedance, activeMember).boneMassKg)} kg" else unavailable, biaNote),
        Triple(
            "基础代谢",
            bmr?.let { "${fmt(it, 0)} kcal" } ?: unavailable,
            if (bmr != null) "估算参考 · Mifflin-St Jeor" else "不可用 · 需选择成员",
        ),
    )

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (state.liveStable) "测量稳定，归属确认后自动保存" else "测量中，请保持静止",
            style = MaterialTheme.typography.labelMedium,
            color = if (state.liveStable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "阻抗：${state.liveImpedance?.let { "${fmt(it, 1)} Ω" } ?: "无阻抗（BIA 指标不可用）"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (title, value, note) ->
                    MetricTile(title, value, note, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 实时 BIA 值用成员参数计算；未选成员时用参考参数，仅用于展示参考趋势。 */
private fun computeForLive(
    weight: Double,
    impedance: Double?,
    member: com.handykit.app.core.db.ScaleMember?,
) = BodyAlgorithm.calculate(
    weightKg = weight,
    impedanceOhm = impedance ?: 0.0,
    sex = member?.sex ?: "MALE",
    heightCm = member?.heightCm ?: 170.0,
    birthDateEpochMs = member?.birthDateEpochMs ?: 0L,
)

@Composable
private fun MetricTile(title: String, value: String, note: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ----------------------------------------------------------------------
// 历史页
// ----------------------------------------------------------------------

@Composable
private fun HistoryTab(state: ScaleViewModel.UiState, vm: ScaleViewModel) {
    if (state.measurements.isEmpty()) {
        EmptyHint("暂无测量记录。完成一次测量后会自动保存在这里。")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.measurements, key = { it.id }) { m ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                onClick = { vm.openDetail(m.id) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmt(m.weightKg, 2)} kg", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${formatDateTime(m.measuredAtEpochMs)} · ${m.memberNameSnapshot ?: "未绑定成员"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "来源：${m.source.ifBlank { "—" }} · 算法：${m.algorithmVersion}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (m.impedanceOhm > 0.0) "阻抗 ${fmt(m.impedanceOhm, 1)} Ω" else "无阻抗",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailDialog(state: ScaleViewModel.UiState, vm: ScaleViewModel, m: Measurement) {
    AlertDialog(
        onDismissRequest = { vm.closeDetail() },
        title = { Text("测量详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                InfoCard("体重", "${fmt(m.weightKg, 2)} kg", "设备实测")
                InfoCard("BMI", fmt(m.bmi), "按成员身高计算")
                val hasImp = BodyAlgorithm.hasImpedance(m.impedanceOhm, m.weightKg)
                InfoCard("体脂率", if (hasImp) "${fmt(m.bodyFatPct)} %" else "不可用", if (hasImp) "基于阻抗计算 · BIA 参考值" else "不可用 · 无阻抗不估算")
                InfoCard("肌肉量", if (hasImp) "${fmt(m.muscleKg)} kg" else "不可用", if (hasImp) "基于阻抗计算 · BIA 参考值" else "不可用 · 无阻抗不估算")
                InfoCard("水分率", if (hasImp) "${fmt(m.waterPct)} %" else "不可用", if (hasImp) "基于阻抗计算 · BIA 参考值" else "不可用 · 无阻抗不估算")
                InfoCard("蛋白质率", if (hasImp) "${fmt(m.proteinPct)} %" else "不可用", if (hasImp) "基于阻抗计算 · BIA 参考值" else "不可用 · 无阻抗不估算")
                InfoCard("骨量", if (hasImp) "${fmt(m.boneMassKg)} kg" else "不可用", if (hasImp) "基于阻抗计算 · BIA 参考值" else "不可用 · 无阻抗不估算")
                InfoCard(
                    "基础代谢",
                    if (m.basalMetabolic > 0.0) "${fmt(m.basalMetabolic, 0)} kcal" else "不可用",
                    "估算参考 · Mifflin-St Jeor",
                )
                Spacer(Modifier.height(8.dp))
                Text("原始测量", style = MaterialTheme.typography.titleSmall)
                Text("时间：${formatDateTime(m.measuredAtEpochMs)}", style = MaterialTheme.typography.bodySmall)
                Text("成员：${m.memberNameSnapshot ?: "未绑定"}（${m.memberId ?: "—"}）", style = MaterialTheme.typography.bodySmall)
                Text("来源：${m.source.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                Text("阻抗原始值：${if (m.impedanceOhm > 0.0) "${fmt(m.impedanceOhm, 2)} Ω" else "无"}", style = MaterialTheme.typography.bodySmall)
                Text("算法版本：${m.algorithmVersion}", style = MaterialTheme.typography.bodySmall)
                Text("记录 ID：${m.id}", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("修正归属（会自动重算）", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = m.memberId == null,
                        onClick = { vm.reassignMeasurement(m.id, null) },
                        label = { Text("未绑定") },
                    )
                    Spacer(Modifier.width(6.dp))
                    state.members.forEach { member ->
                        FilterChip(
                            selected = m.memberId == member.id,
                            onClick = { vm.reassignMeasurement(m.id, member.id) },
                            label = { Text(member.name) },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.closeDetail() }) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = {
                vm.closeDetail()
                vm.requestDeleteMeasurement(m.id)
            }) { Text("删除记录") }
        },
    )
}

// ----------------------------------------------------------------------
// 成员编辑 / 归属确认
// ----------------------------------------------------------------------

@Composable
private fun MemberEditorDialog(state: ScaleViewModel.UiState, vm: ScaleViewModel) {
    var name by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("MALE") }
    var height by remember { mutableStateOf("170") }
    var birth by remember { mutableStateOf("1990-01-01") }
    var refWeight by remember { mutableStateOf("60") }

    LaunchedEffect(state.memberEditorOpen, state.editingMember?.id) {
        name = state.editingMember?.name ?: ""
        sex = state.editingMember?.sex ?: "MALE"
        height = (state.editingMember?.heightCm ?: 170.0).toString()
        birth = state.editingMember?.birthDateEpochMs?.let { formatDate(it) } ?: "1990-01-01"
        refWeight = (state.editingMember?.referenceWeightKg ?: 60.0).toString()
    }

    AlertDialog(
        onDismissRequest = { vm.closeMemberEditor() },
        title = { Text(if (state.editingMember == null) "新增成员" else "编辑成员") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabeledField("姓名", name, { name = it })
                Spacer(Modifier.height(8.dp))
                Text("性别", style = MaterialTheme.typography.labelMedium)
                Row {
                    FilterChip(selected = sex == "MALE", onClick = { sex = "MALE" }, label = { Text("男") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = sex == "FEMALE", onClick = { sex = "FEMALE" }, label = { Text("女") })
                }
                Spacer(Modifier.height(8.dp))
                LabeledField("身高（cm）", height, { height = it }, numeric = true)
                Spacer(Modifier.height(8.dp))
                LabeledField("生日（yyyy-MM-dd）", birth, { birth = it })
                Spacer(Modifier.height(8.dp))
                LabeledField("参考体重（kg）", refWeight, { refWeight = it }, numeric = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    vm.saveMember(
                        id = state.editingMember?.id,
                        name = name,
                        sex = sex,
                        heightCm = height.toDoubleOrNull() ?: 170.0,
                        birthDateEpochMs = parseDate(birth),
                        referenceWeightKg = refWeight.toDoubleOrNull() ?: 60.0,
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { vm.closeMemberEditor() }) { Text("取消") } },
    )
}

@Composable
private fun MemberChoiceDialog(state: ScaleViewModel.UiState, vm: ScaleViewModel) {
    val reading = state.pendingReading ?: return
    AlertDialog(
        onDismissRequest = { vm.discardPending() },
        title = { Text("选择测量归属") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "本次体重 ${fmt(reading.weightKg, 2)} kg。检测到多个候选成员，或体重超出所有成员参考体重 ±10kg，必须明确选择，不会自动绑定。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                state.pendingCandidates.forEach { member ->
                    TextButton(onClick = { vm.confirmPendingMember(member.id) }) {
                        Text("${member.name}（参考 ${fmt(member.referenceWeightKg)} kg）")
                    }
                }
                if (state.pendingCandidates.isEmpty()) {
                    Text("尚未添加成员，可直接保存为未绑定记录。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.confirmPendingMember(null) }) { Text("不绑定保存") } },
        dismissButton = { TextButton(onClick = { vm.discardPending() }) { Text("放弃本次测量") } },
    )
}

// ----------------------------------------------------------------------
// 体重趋势（Canvas 自绘）
// ----------------------------------------------------------------------

@Composable
private fun TrendTab(state: ScaleViewModel.UiState) {
    val activeId = state.activeMemberId
    val values = state.measurements
        .filter { activeId == null || it.memberId == activeId }
        .sortedBy { it.measuredAtEpochMs }
    val memberName = state.members.firstOrNull { it.id == activeId }?.name ?: "全部成员"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader(text = "体重趋势 · $memberName（${values.size} 条）")
        WeightTrendChart(values, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if (values.isEmpty()) {
            EmptyHint("暂无数据：选择成员并完成测量后显示体重趋势。")
        }
    }
}

@Composable
private fun WeightTrendChart(values: List<Measurement>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.height(260.dp)) {
        if (values.size < 2) {
            drawText(
                textMeasurer = measurer,
                text = "至少需要两条记录才能绘制趋势",
                topLeft = Offset(16f, 16f),
                style = TextStyle(fontSize = 13.sp, color = Color.Gray),
            )
            return@Canvas
        }
        val width = size.width
        val height = size.height
        val left = 48f
        val right = 12f
        val top = 20f
        val bottom = 36f
        val plotWidth = width - left - right
        val plotHeight = height - top - bottom
        val minWeight = values.minOf { it.weightKg }.toFloat() - 1f
        val maxWeight = values.maxOf { it.weightKg }.toFloat() + 1f
        val span = (maxWeight - minWeight).coerceAtLeast(1f)

        for (i in 0..3) {
            val y = top + plotHeight * i / 3f
            drawLine(
                color = Color.LightGray,
                start = Offset(left, y),
                end = Offset(width - right, y),
                strokeWidth = 1f,
            )
            val label = String.format(Locale.getDefault(), "%.1f", maxWeight - span * i / 3f)
            drawText(
                textMeasurer = measurer,
                text = label,
                topLeft = Offset(4f, y - 18f),
                style = TextStyle(fontSize = 11.sp, color = Color.Gray),
            )
        }

        val points = values.mapIndexed { index, m ->
            val x = left + plotWidth * index.toFloat() / (values.size - 1).toFloat()
            val y = top + plotHeight * (1f - (m.weightKg.toFloat() - minWeight) / span)
            Offset(x, y)
        }
        for (i in 1 until points.size) {
            drawLine(color = lineColor, start = points[i - 1], end = points[i], strokeWidth = 3f)
        }
        points.forEach { point ->
            drawCircle(color = lineColor, radius = 5f, center = point)
        }
        drawText(
            textMeasurer = measurer,
            text = formatDate(values.first().measuredAtEpochMs),
            topLeft = Offset(left, height - 20f),
            style = TextStyle(fontSize = 11.sp, color = Color.Gray),
        )
        drawText(
            textMeasurer = measurer,
            text = formatDate(values.last().measuredAtEpochMs),
            topLeft = Offset(width - right - 72f, height - 20f),
            style = TextStyle(fontSize = 11.sp, color = Color.Gray),
        )
    }
}

// ----------------------------------------------------------------------
// 工具
// ----------------------------------------------------------------------

private fun fmt(value: Double, digits: Int = 1): String =
    String.format(Locale.getDefault(), "%.${digits}f", value)

private fun formatDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return try {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    } catch (e: Exception) {
        "—"
    }
}

private fun formatDate(epochMs: Long): String {
    if (epochMs <= 0L) return "—"
    return try {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    } catch (e: Exception) {
        "—"
    }
}

private fun parseDate(text: String): Long = try {
    LocalDate.parse(text.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
} catch (e: Exception) {
    0L
}
