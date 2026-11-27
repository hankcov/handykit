package com.handykit.app.feature.fuel

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.handykit.app.core.db.FuelRecord
import com.handykit.app.core.db.Vehicle
import com.handykit.app.core.ui.ConfirmDialog
import com.handykit.app.core.ui.EmptyHint
import com.handykit.app.core.ui.InfoCard
import com.handykit.app.core.ui.LabeledField
import com.handykit.app.core.ui.LoadingRow
import com.handykit.app.core.ui.ScreenTitle
import com.handykit.app.core.ui.SectionHeader
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

private data class RecordDraftState(
    val id: Long = 0L,
    val vehicleId: Long = 0L,
    val date: String = LocalDate.now().toString(),
    val odometer: String = "",
    val liters: String = "",
    val amount: String = "",
    val price: String = "",
    val isFull: Boolean = true,
    val station: String = "",
    val note: String = "",
    val invalidSegment: Boolean = false,
) {
    fun priceDraft(): PriceDraft = PriceDraft(liters, amount, price)
}

private fun draftFrom(record: FuelRecord) = RecordDraftState(
    id = record.id,
    vehicleId = record.vehicleId,
    date = record.date.toString(),
    odometer = record.odometer.toString(),
    liters = record.liters.toString(),
    amount = record.amount.toString(),
    price = record.price.toString(),
    isFull = record.isFull,
    station = record.station.orEmpty(),
    note = record.note.orEmpty(),
    invalidSegment = record.invalidSegment,
)

private fun num(x: Double): String = String.format(Locale.ROOT, "%.2f", x)

private fun reasonOf(record: FuelRecord, segment: Segment?): String? = when {
    record.invalidSegment -> "已标记无效：该区间因漏记被排除"
    segment?.anomaly == SegmentAnomaly.ODOMETER -> "里程回退：区间油耗不可用"
    segment?.anomaly == SegmentAnomaly.OUT_OF_RANGE ->
        "油耗 ${segment.l100?.let(::num)} L/100km 超范围，未计入趋势"
    segment != null && !segment.isTrusted -> "区间异常，未计入趋势"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelScreen(
    onBack: (() -> Unit)? = null,
    vm: FuelViewModel = viewModel(factory = FuelViewModelFactory(LocalContext.current)),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vehicles by vm.vehicles.collectAsState()
    val vehicle by vm.currentVehicle.collectAsState()
    val (records, metrics) = vm.data.collectAsState().value
    val busy by vm.busy.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var draft by remember { mutableStateOf<RecordDraftState?>(null) }
    var vehicleEditor by remember { mutableStateOf<Vehicle?>(null) }
    var showVehicleManager by remember { mutableStateOf(false) }
    var vehicleToDelete by remember { mutableStateOf<Vehicle?>(null) }
    var recordToDelete by remember { mutableStateOf<FuelRecord?>(null) }
    var pendingConfirm by remember { mutableStateOf<Pair<FuelRecord, List<String>>?>(null) }

    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                is FuelEffect.Toast -> snackbar.showSnackbar(effect.message)
                is FuelEffect.ConfirmSave -> pendingConfirm = effect.record to effect.warnings
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = vm.buildCsv()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    }
                    snackbar.showSnackbar("CSV 已导出")
                } catch (e: Exception) {
                    Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = try {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    Toast.makeText(context, "读取失败：${e.message}", Toast.LENGTH_SHORT).show()
                    null
                }
                if (text != null) vm.importCsv(text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (vehicle != null) {
                FloatingActionButton(onClick = {
                    draft = RecordDraftState(vehicleId = vehicle!!.id)
                }) { Icon(Icons.Filled.Add, contentDescription = "记一笔") }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ScreenTitle(
                title = "油耗助手",
                subtitle = vehicle?.name?.let { "满箱法 · $it" } ?: "满箱法 · 尚未选择车辆",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { exportLauncher.launch("fuel_records_${LocalDate.now()}.csv") }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "导出 CSV")
                    }
                    IconButton(onClick = { importLauncher.launch("text/*") }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导入 CSV")
                    }
                    IconButton(onClick = { showVehicleManager = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "车辆管理")
                    }
                },
            )

            if (busy) LoadingRow("正在导入 CSV")

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = vehicle?.let { v ->
                        if (v.plate.isBlank()) v.name else "${v.name} · ${v.plate}"
                    } ?: "未选择车辆",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text("当前车辆") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    vehicles.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(if (v.plate.isBlank()) v.name else "${v.name} · ${v.plate}") },
                            onClick = { vm.selectVehicle(v.id); expanded = false },
                        )
                    }
                }
            }

            if (vehicle == null) {
                EmptyHint("还没有车辆，点右上角设置添加一辆车吧")
                return@Column
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    InfoCard(
                        title = "当前油耗",
                        value = metrics?.currentL100?.let { "${num(it)} L/100km" } ?: "暂无可信区间",
                        note = metrics?.deltaFromPrev?.let { d ->
                            val sign = if (d > 0.0) "↑" else if (d < 0.0) "↓" else "±"
                            "较上一区间 $sign ${num(kotlin.math.abs(d))}"
                        },
                        icon = Icons.Filled.LocalGasStation,
                    )
                }
                Box(Modifier.weight(1f)) {
                    InfoCard(
                        title = "累计里程",
                        value = "${metrics?.totalDistance ?: 0} km",
                        note = metrics?.avgL100?.let { "平均 ${num(it)} L/100km" },
                        icon = Icons.Filled.Speed,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    InfoCard(
                        title = "累计油费",
                        value = "¥${num(metrics?.totalCost ?: 0.0)}",
                        note = "${records.size} 条记录",
                        icon = Icons.Filled.AccountBalanceWallet,
                    )
                }
                Box(Modifier.weight(1f)) {
                    val lastLiters = records.lastOrNull()?.liters ?: 0.0
                    val basisNote = when (metrics?.rangeBasis ?: RangeBasis.NONE) {
                        RangeBasis.TANK -> "依据油箱容积 ${num(vehicle!!.tankCapacity)} L"
                        RangeBasis.LAST_FUEL -> "依据上次加油量 ${num(lastLiters)} L"
                        RangeBasis.NONE -> "无油量依据，不可用"
                    }
                    InfoCard(
                        title = "预估续航",
                        value = metrics?.rangeKm?.let { "${num(it)} km" } ?: "不可用",
                        note = metrics?.expectedRefuelOdometer?.let { "$basisNote · 预计 $it km 加油" }
                            ?: basisNote,
                        icon = Icons.Filled.DirectionsCar,
                    )
                }
            }

            SectionHeader(
                text = "油耗趋势",
                trailing = {
                    val anomalies = metrics?.anomalyCount ?: 0
                    if (anomalies > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("$anomalies 个异常区间", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
            )
            TrendChart(
                points = metrics?.trend.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .padding(horizontal = 12.dp),
            )
            Text(
                "断点为异常或标记无效的区间，不按 0 绘制，也不计入指标。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            SectionHeader(
                text = "加油记录",
                trailing = { Text("${records.size} 条", style = MaterialTheme.typography.labelSmall) },
            )
            if (records.isEmpty()) {
                EmptyHint("还没有记录，点右下角「记一笔」开始")
            } else {
                val segmentByToId = metrics?.segments?.associateBy { it.toId } ?: emptyMap()
                records.sortedWith(compareByDescending<FuelRecord> { it.date }.thenByDescending { it.id })
                    .forEach { record ->
                        RecordRow(
                            record = record,
                            segment = segmentByToId[record.id],
                            onEdit = { draft = draftFrom(record) },
                            onDelete = { recordToDelete = record },
                            onToggleInvalid = {
                                vm.commitRecord(
                                    record.copy(
                                        invalidSegment = !record.invalidSegment,
                                        isFull = if (!record.invalidSegment) true else record.isFull,
                                    ),
                                    force = true,
                                )
                            },
                        )
                    }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    draft?.let { current ->
        RecordSheet(
            draft = current,
            onDraft = { draft = it },
            onDismiss = { draft = null },
            onSave = {
                val priceResult = current.priceDraft().resolve()
                val date = runCatching { LocalDate.parse(current.date.trim()) }.getOrNull()
                val odometer = current.odometer.toIntOrNull()
                if (date == null) {
                    Toast.makeText(context, "日期格式应为 yyyy-MM-dd", Toast.LENGTH_SHORT).show()
                    return@RecordSheet
                }
                if (odometer == null || odometer < 0) {
                    Toast.makeText(context, "请填写有效的里程", Toast.LENGTH_SHORT).show()
                    return@RecordSheet
                }
                if (priceResult.insufficient) {
                    Toast.makeText(context, "升数、金额、单价至少填写两项", Toast.LENGTH_SHORT).show()
                    return@RecordSheet
                }
                val liters = priceResult.liters ?: 0.0
                val amount = priceResult.amount ?: 0.0
                val price = priceResult.price ?: 0.0
                if (liters <= 0.0) {
                    Toast.makeText(context, "升数必须大于 0", Toast.LENGTH_SHORT).show()
                    return@RecordSheet
                }
                vm.commitRecord(
                    FuelRecord(
                        id = current.id,
                        vehicleId = current.vehicleId,
                        date = date,
                        odometer = odometer,
                        liters = liters,
                        amount = amount,
                        price = price,
                        isFull = current.isFull,
                        station = current.station.ifBlank { null },
                        note = current.note.ifBlank { null },
                        estimated = priceResult.derived == PriceFields.DerivedField.LITERS,
                        invalidSegment = current.invalidSegment,
                    )
                )
                draft = null
            },
        )
    }

    pendingConfirm?.let { (record, warnings) ->
        ConfirmDialog(
            title = "发现异常，仍要保存吗？",
            message = warnings.joinToString("\n") { "· $it" },
            confirmText = "仍然保存",
            danger = true,
            onConfirm = { vm.commitRecord(record, force = true); pendingConfirm = null },
            onDismiss = { pendingConfirm = null },
        )
    }

    recordToDelete?.let { record ->
        ConfirmDialog(
            title = "删除这笔记录？",
            message = "删除后油耗指标会自动重算。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteRecord(record.id); recordToDelete = null },
            onDismiss = { recordToDelete = null },
        )
    }

    if (showVehicleManager) {
        VehicleManagerDialog(
            vehicles = vehicles,
            onEdit = { vehicleEditor = it },
            onDelete = { vehicleToDelete = it },
            onCreate = { vehicleEditor = Vehicle(name = "", plate = "") },
            onDismiss = { showVehicleManager = false },
        )
    }

    vehicleEditor?.let { editor ->
        VehicleEditorDialog(
            vehicle = editor,
            onDismiss = { vehicleEditor = null },
            onSave = { name, plate, tank, grade ->
                if (name.isBlank()) {
                    Toast.makeText(context, "请填写车辆名称", Toast.LENGTH_SHORT).show()
                    return@VehicleEditorDialog
                }
                vm.saveVehicle(
                    editor.copy(
                        name = name.trim(),
                        plate = plate.trim(),
                        tankCapacity = tank ?: 0.0,
                        fuelGrade = grade.ifBlank { "92" },
                    )
                )
                vehicleEditor = null
            },
        )
    }

    vehicleToDelete?.let { target ->
        ConfirmDialog(
            title = "删除车辆？",
            message = "「${target.name}」及其全部加油记录都会被删除，无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteVehicle(target.id); vehicleToDelete = null },
            onDismiss = { vehicleToDelete = null },
        )
    }
}

@Composable
private fun RecordRow(
    record: FuelRecord,
    segment: Segment?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleInvalid: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("${record.date} · ${record.odometer} km", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${num(record.liters)} L · ¥${num(record.amount)} · ${num(record.price)} 元/L",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    buildString {
                        append(if (record.isFull) "加满" else "未加满")
                        if (record.estimated) append(" · 升数推算")
                        if (!record.station.isNullOrBlank()) append(" · ${record.station}")
                        if (!record.note.isNullOrBlank()) append(" · ${record.note}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (segment != null && segment.isTrusted && !record.invalidSegment) {
                    Text(
                        "区间 ${segment.distance} km / ${num(segment.fuel)} L → ${num(segment.l100!!)} L/100km",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                val reason = reasonOf(record, segment)
                if (reason != null) {
                    Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onToggleInvalid) {
                    Text(
                        if (record.invalidSegment) "恢复有效" else "标记漏记（区间无效）",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

/** Canvas 自绘折线图：可信点连线，异常/无效点留断点且绝不按 0 绘制。 */
@Composable
private fun TrendChart(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        EmptyHint("至少两个区间才能绘制趋势", modifier)
        return
    }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val error = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val top = 12.dp.toPx()
        val bottom = 18.dp.toPx()
        val side = 12.dp.toPx()
        val plotWidth = (size.width - side * 2).coerceAtLeast(1f)
        val plotHeight = (size.height - top - bottom).coerceAtLeast(1f)

        for (k in 0..3) {
            val y = top + plotHeight * k / 3f
            drawLine(muted, Offset(side, y), Offset(side + plotWidth, y), strokeWidth = 1.dp.toPx())
        }

        val values = points.mapNotNull { it.l100?.takeIf { v -> v.isFinite() } }
        if (values.size >= 2) {
            val min = values.minOrNull()!!
            val max = values.maxOrNull()!!
            val span = (max - min).coerceAtLeast(1.0).toFloat()
            val pad = span * 0.15f
            val floor = min.toFloat() - pad
            val ceil = max.toFloat() + pad

            fun xAt(index: Int) = side + plotWidth * index / (points.size - 1).toFloat()
            fun yAt(value: Double): Float =
                top + plotHeight * (1f - (value.toFloat() - floor) / (ceil - floor))

            val path = Path()
            var drawing = false
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
            points.forEachIndexed { index, point ->
                val x = xAt(index)
                val value = point.l100?.takeIf { it.isFinite() }
                if (value == null) {
                    drawing = false
                    drawLine(error, Offset(x, top), Offset(x, top + plotHeight), 1.dp.toPx(), pathEffect = dash)
                    drawCircle(error, radius = 3.dp.toPx(), center = Offset(x, top + plotHeight / 2f))
                } else {
                    val y = yAt(value)
                    if (drawing) path.lineTo(x, y) else {
                        path.moveTo(x, y)
                        drawing = true
                    }
                    drawCircle(primary, radius = 3.dp.toPx(), center = Offset(x, y))
                }
            }
            drawPath(path, primary, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordSheet(
    draft: RecordDraftState,
    onDraft: (RecordDraftState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (draft.id == 0L) "记一笔" else "编辑记录", style = MaterialTheme.typography.titleLarge)
            LabeledField("日期 yyyy-MM-dd", draft.date, { onDraft(draft.copy(date = it)) })
            LabeledField("里程 km", draft.odometer, { onDraft(draft.copy(odometer = it)) }, numeric = true)
            LabeledField("升数 L", draft.liters, { onDraft(draft.copy(liters = it)) }, numeric = true)
            LabeledField("金额 元", draft.amount, { onDraft(draft.copy(amount = it)) }, numeric = true)
            LabeledField("单价 元/L", draft.price, { onDraft(draft.copy(price = it)) }, numeric = true)

            val price = draft.priceDraft().resolve()
            when (price.derived) {
                PriceFields.DerivedField.LITERS -> Text(
                    "升数由金额和单价推算：${num(price.liters ?: 0.0)} L（标记为推算值）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PriceFields.DerivedField.AMOUNT -> Text(
                    "金额由升数和单价推算：¥${num(price.amount ?: 0.0)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PriceFields.DerivedField.PRICE -> Text(
                    "单价由升数和金额推算：${num(price.price ?: 0.0)} 元/L",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                PriceFields.DerivedField.NONE -> Unit
            }
            if (price.conflict) {
                Text(
                    "三项手填不一致：金额与「升数×单价」不符，请核对后保存。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (price.insufficient) {
                Text(
                    "升数、金额、单价至少填写两项，才能推算第三项。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = draft.isFull, onCheckedChange = { onDraft(draft.copy(isFull = it)) })
                Spacer(Modifier.width(8.dp))
                Text("本次加满（满箱法锚点）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = draft.invalidSegment,
                    onCheckedChange = {
                        onDraft(draft.copy(invalidSegment = it, isFull = if (it) true else draft.isFull))
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text("标记区间无效（漏记，仍保留记录）")
            }
            LabeledField("加油站（可选）", draft.station, { onDraft(draft.copy(station = it)) })
            LabeledField("备注（可选）", draft.note, { onDraft(draft.copy(note = it)) })

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave) { Text("保存") }
            }
        }
    }
}

@Composable
private fun VehicleManagerDialog(
    vehicles: List<Vehicle>,
    onEdit: (Vehicle) -> Unit,
    onDelete: (Vehicle) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("车辆管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (vehicles.isEmpty()) Text("暂无车辆")
                vehicles.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOf(v.plate, "${num(v.tankCapacity)} L", "${v.fuelGrade}号")
                                    .filter { it.isNotBlank() && it != "0.00 L" }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEdit(v) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑车辆")
                        }
                        IconButton(onClick = { onDelete(v) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除车辆")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text("新增车辆") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun VehicleEditorDialog(
    vehicle: Vehicle,
    onSave: (name: String, plate: String, tank: Double?, grade: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(vehicle.name) }
    var plate by remember { mutableStateOf(vehicle.plate) }
    var tank by remember { mutableStateOf(if (vehicle.tankCapacity > 0.0) vehicle.tankCapacity.toString() else "") }
    var grade by remember { mutableStateOf(vehicle.fuelGrade) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (vehicle.id == 0L) "新增车辆" else "编辑车辆") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledField("车辆名称", name, { name = it })
                LabeledField("车牌（导入 CSV 时的归并依据）", plate, { plate = it })
                LabeledField("油箱容积 L（可选）", tank, { tank = it }, numeric = true)
                LabeledField("油品标号", grade, { grade = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, plate, tank.toDoubleOrNull(), grade) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
