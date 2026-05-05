package com.example.gpspressurelogger.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.sensor.MovementDetector
import com.example.gpspressurelogger.ui.viewmodel.HomeUiState
import com.example.gpspressurelogger.ui.viewmodel.HomeViewModel
import com.example.gpspressurelogger.util.ExportUtil
import com.example.gpspressurelogger.util.GraphUtil
import com.example.gpspressurelogger.util.GpsUtil
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

// 系列カラー
private val colorAlt   = Color(GraphUtil.COLOR_ALT)
private val colorPres  = Color(GraphUtil.COLOR_PRES)
private val colorQnh   = Color(GraphUtil.COLOR_QNH)
private val colorSteps = Color(GraphUtil.COLOR_STEPS)
private val modeColorDeviceStill = Color(GpsUtil.modeColor(MovementDetector.Mode.DEVICE_STILL))
private val modeColorStopped = Color(GpsUtil.modeColor(MovementDetector.Mode.STOPPED))
private val modeColorWalking = Color(GpsUtil.modeColor(MovementDetector.Mode.WALKING))
private val modeColorVehicle = Color(GpsUtil.modeColor(MovementDetector.Mode.VEHICLE))
private const val GRAPH_DRAG_SENSITIVITY = 4f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToMap: () -> Unit,
    onNavigateToSettings: () -> Unit,
    graphResetRequestId: Long = 0L,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(graphResetRequestId) {
        if (graphResetRequestId > 0L) {
            viewModel.resetGraphWindowToLatest()
        }
    }
    LaunchedEffect(
        uiState.isGraphLoaded,
        uiState.history.size,
        uiState.latestTimestampMs,
        uiState.graphWindowEndMs,
        uiState.graphLoadError
    ) {
        if (
            uiState.isGraphLoaded &&
            uiState.graphLoadError == null &&
            uiState.history.size < 2 &&
            uiState.latestTimestampMs != null
        ) {
            viewModel.recoverGraphWindowIfEmpty(
                historySize = uiState.history.size,
                latestTimestampMs = uiState.latestTimestampMs,
                graphWindowEndMs = uiState.graphWindowEndMs
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPS 気圧ロガー") },
                actions = {
                    IconButton(onClick = onNavigateToMap) { Icon(Icons.Default.Place, contentDescription = "地図") }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, contentDescription = "設定") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CurrentValuesCard(uiState)
            if (uiState.history.size >= 2) {
                Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    CombinedChart(
                        entries = uiState.history,
                        motionSamples = uiState.motionHistory,
                        lookbackMin = uiState.lookbackMin,
                        windowEndMs = uiState.graphWindowEndMs,
                        onVisibleLookbackChanged = viewModel::updateGraphVisibleLookback,
                        onWindowShift = viewModel::shiftGraphWindowBy,
                        onResetWindow = viewModel::resetGraphWindowToLatest,
                        modifier = Modifier.fillMaxSize().padding(8.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    EmptyGraphMessage(
                        uiState = uiState,
                        onResetWindow = viewModel::resetGraphWindowToLatest
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyGraphMessage(
    uiState: HomeUiState,
    onResetWindow: () -> Unit
) {
    val message = when {
        !uiState.isGraphLoaded -> "履歴を読み込み中..."
        uiState.graphLoadError != null -> "履歴の読込に失敗しました"
        uiState.latestTimestampMs == null -> "データを記録中...\n屋外で使用してください"
        uiState.history.isEmpty() -> "表示範囲にデータがありません"
        else -> "表示範囲のデータが不足しています (${uiState.history.size}件)"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (uiState.latestTimestampMs != null) {
            Button(onClick = onResetWindow) {
                Text("最新へ戻す")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CombinedChart(
    entries: List<LogEntry>,
    motionSamples: List<MotionSample>,
    lookbackMin: Int,
    windowEndMs: Long,
    onVisibleLookbackChanged: (Long) -> Unit,
    onWindowShift: (Long, Long) -> Unit,
    onResetWindow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val baseLookbackMs = lookbackMin * 60_000L.toLong()
    val entriesAsc = entries
    var zoomX by remember { mutableFloatStateOf(1f) }
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }
    var canvasHeightPx by remember { mutableFloatStateOf(1f) }
    var lastGestureLogMs by remember { mutableLongStateOf(0L) }
    var transientWindowEndMs by remember(windowEndMs) { mutableLongStateOf(windowEndMs) }
    var gestureActive by remember { mutableStateOf(false) }
    val visibleLookbackMs = remember(lookbackMin, zoomX) {
        (baseLookbackMs / zoomX).toLong().coerceAtLeast(30 * 60_000L)
    }
    val graphStartMs = (transientWindowEndMs - visibleLookbackMs).coerceAtLeast(0L)
    val latestVisibleLookbackMs by rememberUpdatedState(visibleLookbackMs)
    val latestWindowEndMs by rememberUpdatedState(transientWindowEndMs)
    val latestCanvasWidthPx by rememberUpdatedState(canvasWidthPx)
    val latestLoadedNewestMs by rememberUpdatedState(entriesAsc.lastOrNull()?.timestamp ?: windowEndMs)

    LaunchedEffect(windowEndMs) {
        if (!gestureActive) {
            transientWindowEndMs = windowEndMs
        }
    }
    LaunchedEffect(visibleLookbackMs) {
        onVisibleLookbackChanged(visibleLookbackMs)
    }

    val dragState = rememberDraggableState { delta ->
        val widthPx = latestCanvasWidthPx
        if (widthPx <= 1f) return@rememberDraggableState
        val lookbackMs = latestVisibleLookbackMs
        val shiftMs = (-(delta / widthPx) * lookbackMs * GRAPH_DRAG_SENSITIVITY).toLong()
        if (shiftMs == 0L) return@rememberDraggableState
        val maxWindowEnd = maxOf(latestLoadedNewestMs, latestWindowEndMs)
        transientWindowEndMs = (latestWindowEndMs + shiftMs).coerceIn(0L, maxWindowEnd)
        val now = System.currentTimeMillis()
        if (now - lastGestureLogMs >= 250L) {
            lastGestureLogMs = now
            ExportUtil.writeVerboseDebugLog(
                context,
                "GRAPH_DRAG: deltaPx=$delta shiftMs=$shiftMs widthPx=$widthPx visibleLookbackMs=$lookbackMs windowEndMs=$latestWindowEndMs"
            )
        }
    }
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        if (!zoomChange.isFinite() || zoomChange == 1f) return@rememberTransformableState
        val minZoomX = 1f / GraphUtil.MAX_ZOOM_OUT_FACTOR.toFloat()
        val candidateZoomX = (zoomX * zoomChange).coerceIn(minZoomX, 20f)
        zoomX = candidateZoomX
        val now = System.currentTimeMillis()
        if (now - lastGestureLogMs >= 250L) {
            lastGestureLogMs = now
            ExportUtil.writeVerboseDebugLog(
                context,
                "GRAPH_TRANSFORM: zoomChange=$zoomChange zoomX=$zoomX widthPx=$latestCanvasWidthPx visibleLookbackMs=$latestVisibleLookbackMs windowEndMs=$latestWindowEndMs"
            )
        }
    }

    var lastStableSeries by remember { mutableStateOf<GraphUtil.ProcessedSeries?>(null) }
    val computedSeries by produceState<GraphUtil.ProcessedSeries?>(
        initialValue = null,
        entriesAsc,
        motionSamples
    ) {
        snapshotFlow { graphStartMs to transientWindowEndMs }
            .conflate()
            .collect { (windowStartMs, windowEndMsForSeries) ->
                value = withContext(Dispatchers.Default) {
                    GraphUtil.getProcessedSeriesForWindow(
                        entries = entriesAsc,
                        motionSamples = motionSamples,
                        intervalMs = 30_000L,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMsForSeries
                    )
                }
            }
    }
    LaunchedEffect(computedSeries) {
        if (computedSeries != null) {
            lastStableSeries = computedSeries
        }
    }
    val d = computedSeries ?: lastStableSeries
    if (d == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "グラフ計算中...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val stepSeriesByMode = remember(d) {
        listOf(
            MovementDetector.Mode.DEVICE_STILL to modeColorDeviceStill,
            MovementDetector.Mode.STOPPED to modeColorStopped,
            MovementDetector.Mode.WALKING to modeColorWalking,
            MovementDetector.Mode.VEHICLE to modeColorVehicle
        ).map { (mode, color) ->
            Triple(
                mode,
                color,
                d.steps.mapIndexed { index, value ->
                    if (d.stepModes[index] == mode) value else null
                }
            )
        }
    }

    val axisStartMs = graphStartMs
    val axisRange = (transientWindowEndMs - axisStartMs).coerceAtLeast(1L)
    val fmtTime = remember(axisRange) {
        if (axisRange > 12 * 3600_000L) SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)
        else SimpleDateFormat("HH:mm", Locale.JAPAN)
    }
    val chartPaths = remember(
        d,
        stepSeriesByMode,
        axisStartMs,
        axisRange,
        canvasWidthPx,
        canvasHeightPx,
        density
    ) {
        val padL = with(density) { 20.dp.toPx() }
        val padR = with(density) { 20.dp.toPx() }
        val padBot = with(density) { 20.dp.toPx() }
        val plotW = (canvasWidthPx - padL - padR).coerceAtLeast(1f)
        val plotH = (canvasHeightPx - padBot).coerceAtLeast(1f)
        fun xOf(timestamp: Long) = padL + (timestamp - axisStartMs).toFloat() / axisRange * plotW
        fun yOfP(v: Float) = GraphUtil.valueToYRatio(v, d.pMin, d.pMax, GraphUtil.PRES_TOP, GraphUtil.PRES_BOT) * canvasHeightPx
        fun yOfA(v: Float) = GraphUtil.valueToYRatio(v, d.aMin, d.aMax, GraphUtil.ALT_TOP, GraphUtil.ALT_BOT) * canvasHeightPx
        fun yOfS(v: Float) = GraphUtil.stepsToYRatio(v, d.sMax) * canvasHeightPx
        ChartPaths(
            stepPaths = stepSeriesByMode.map { (_, color, values) ->
                color to buildStepPath(values, d.times, ::xOf, ::yOfS)
            },
            altitudePath = buildLinePath(d.alt, d.times, ::xOf, ::yOfA),
            pressurePath = buildLinePath(d.pres, d.times, ::xOf, ::yOfP),
            qnhPath = buildLinePath(d.qnh, d.times, ::xOf, ::yOfP),
            plotTop = 0f,
            plotLeft = padL,
            plotRight = padL + plotW,
            plotBottom = plotH
        )
    }

    Box(
        modifier = modifier
            .onSizeChanged {
                canvasWidthPx = it.width.toFloat().coerceAtLeast(1f)
                canvasHeightPx = it.height.toFloat().coerceAtLeast(1f)
                ExportUtil.writeVerboseDebugLog(
                    context,
                    "GRAPH_SIZE: widthPx=${it.width} heightPx=${it.height} lookbackMin=$lookbackMin windowEndMs=$windowEndMs"
                )
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = dragState,
                onDragStarted = {
                    gestureActive = true
                },
                onDragStopped = {
                    gestureActive = false
                    val totalShiftMs = transientWindowEndMs - windowEndMs
                    if (totalShiftMs != 0L) {
                        onWindowShift(totalShiftMs, visibleLookbackMs)
                    }
                }
            )
            .transformable(
                state = transformState,
                canPan = { false }
            )
    ) {
        Text(
            text = "${fmtTime.format(Date(axisStartMs))} - ${fmtTime.format(Date(transientWindowEndMs))}",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(
            onClick = onResetWindow,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .height(30.dp)
        ) {
            Text("最新", fontSize = 11.sp)
        }
        // Paint オブジェクトを Canvas 描画ごとに生成しないよう remember でキャッシュ
        val labelSz = with(density) { 9.sp.toPx() }
        val paintL = remember(labelSz) { android.graphics.Paint().apply { isAntiAlias = true; textSize = labelSz; color = GraphUtil.COLOR_PRES; textAlign = android.graphics.Paint.Align.RIGHT } }
        val paintR = remember(labelSz) { android.graphics.Paint().apply { isAntiAlias = true; textSize = labelSz; color = GraphUtil.COLOR_ALT; textAlign = android.graphics.Paint.Align.LEFT } }
        val paintS = remember(labelSz) { android.graphics.Paint().apply { isAntiAlias = true; textSize = labelSz; color = GraphUtil.COLOR_STEPS; textAlign = android.graphics.Paint.Align.LEFT } }
        val paintX = remember(labelSz) { android.graphics.Paint().apply { isAntiAlias = true; textSize = labelSz; color = 0xB4B4B4B4.toInt(); textAlign = android.graphics.Paint.Align.CENTER } }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val padL = 20.dp.toPx(); val padR = 20.dp.toPx(); val padBot = 20.dp.toPx()
            val plotW = w - padL - padR; val plotH = h - padBot

            val vTRange = axisRange
            val vTMin = axisStartMs

            fun yOfP(v: Float) = GraphUtil.valueToYRatio(v, d.pMin, d.pMax, GraphUtil.PRES_TOP, GraphUtil.PRES_BOT) * h
            fun yOfA(v: Float) = GraphUtil.valueToYRatio(v, d.aMin, d.aMax, GraphUtil.ALT_TOP, GraphUtil.ALT_BOT) * h
            fun yOfS(v: Float) = (GraphUtil.stepsToYRatio(v, d.sMax) * h)

            val labelGap = 2.dp.toPx()

            // 左軸（気圧）
            listOf(d.pMax, d.pMin).forEach { v ->
                val y = yOfP(v)
                if (y in 0f..plotH) {
                    drawLine(Color(0x15FFFFFF), androidx.compose.ui.geometry.Offset(padL, y), androidx.compose.ui.geometry.Offset(padL + plotW, y), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText("%.1f".format(v), padL - labelGap, y + labelSz/3, paintL)
                }
            }
            // 右軸（高度・歩数）
            val aTicks = listOf(d.aMax, d.aMin)
            val altYs = aTicks.map { yOfA(it) }
            aTicks.forEachIndexed { i, v ->
                val y = altYs[i]
                if (y in 0f..plotH) {
                    drawLine(Color(0x15FFFFFF), androidx.compose.ui.geometry.Offset(padL, y), androidx.compose.ui.geometry.Offset(padL + plotW, y), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText("%.0f".format(v), padL + plotW + labelGap, y + labelSz/3, paintR)
                }
            }
            GraphUtil.getStepTicks(d.sMax).forEach { sv ->
                val yS = yOfS(sv)
                if (yS in 0f..plotH) {
                    val nearestAltY = altYs.minByOrNull { abs(it - yS) } ?: -1000f
                    val (offsetS, _) = GraphUtil.calculateVerticalOffset(yS, nearestAltY)
                    drawLine(Color(0x08FFFFFF), androidx.compose.ui.geometry.Offset(padL, yS), androidx.compose.ui.geometry.Offset(padL + plotW, yS), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText("%.0f".format(sv), padL + plotW + labelGap, yS + labelSz/3 + offsetS, paintS)
                }
            }

            GraphUtil.midnightTicks(axisStartMs, transientWindowEndMs).forEach { ts ->
                val x = padL + (ts - vTMin).toFloat() / vTRange * plotW
                if (x in padL..(padL + plotW)) {
                    drawLine(
                        Color(GraphUtil.COLOR_MIDNIGHT_LINE),
                        androidx.compose.ui.geometry.Offset(x, 0f),
                        androidx.compose.ui.geometry.Offset(x, plotH),
                        strokeWidth = 1f
                    )
                }
            }

            for (k in 0..4) {
                val ts = vTMin + vTRange * k / 4; val x = padL + (ts - vTMin).toFloat() / vTRange * plotW
                if (x in padL..(padL + plotW)) {
                    drawContext.canvas.nativeCanvas.drawText(fmtTime.format(Date(ts)), x, h - 4.dp.toPx(), paintX)
                }
            }

            clipRect(chartPaths.plotLeft, chartPaths.plotTop, chartPaths.plotRight, chartPaths.plotBottom) {
                chartPaths.stepPaths.forEach { (color, path) ->
                    drawPath(path, color, style = Stroke(2.5f))
                }
                drawPath(chartPaths.altitudePath, colorAlt, style = Stroke(2.5f))
                drawPath(chartPaths.pressurePath, colorPres, style = Stroke(2.5f))
                drawPath(chartPaths.qnhPath, colorQnh, style = Stroke(2.5f))
            }
        }
    }
}

private data class ChartPaths(
    val stepPaths: List<Pair<Color, Path>>,
    val altitudePath: Path,
    val pressurePath: Path,
    val qnhPath: Path,
    val plotTop: Float,
    val plotLeft: Float,
    val plotRight: Float,
    val plotBottom: Float
)

private fun buildLinePath(
    vals: List<Float>,
    times: LongArray,
    xOf: (Long) -> Float,
    yOf: (Float) -> Float
): Path {
    val path = Path()
    var drawing = false
    vals.forEachIndexed { index, value ->
        if (!value.isFinite()) {
            drawing = false
            return@forEachIndexed
        }
        val x = xOf(times[index])
        val y = yOf(value)
        if (!drawing) {
            path.moveTo(x, y)
            drawing = true
        } else {
            path.lineTo(x, y)
        }
    }
    return path
}

private fun buildStepPath(
    vals: List<Float?>,
    times: LongArray,
    xOf: (Long) -> Float,
    yOf: (Float) -> Float
): Path {
    val path = Path()
    var drawing = false
    var previousValue = Float.NaN
    var previousY = Float.NaN
    vals.forEachIndexed { index, rawValue ->
        val value = rawValue
        if (value == null || !value.isFinite()) {
            drawing = false
            previousValue = Float.NaN
            previousY = Float.NaN
            return@forEachIndexed
        }
        val x = xOf(times[index])
        val y = yOf(value)
        if (!drawing || (previousValue.isFinite() && value < previousValue)) {
            path.moveTo(x, y)
            drawing = true
        } else {
            // 歩数は累積離散値なので、斜め補間せず水平線 + 垂直線で増分を表す。
            path.lineTo(x, previousY)
            path.lineTo(x, y)
        }
        previousValue = value
        previousY = y
    }
    return path
}

@Composable
private fun CurrentValuesCard(uiState: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("現在値", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth()) {
                ValueItem(Modifier.weight(1f), "歩数", "${uiState.stepsToday}", "歩", colorSteps)
                ValueItem(Modifier.weight(1f), "標高", "%.0f".format(uiState.altitudeM), "m", colorAlt)
                ValueItem(Modifier.weight(1f), "気圧", "%.1f".format(uiState.pressureRaw), "hPa", colorPres)
                ValueItem(Modifier.weight(1f), "補正", "%.1f".format(uiState.pressureQnh), "hPa", colorQnh)
            }
        }
    }
}

@Composable
private fun ValueItem(modifier: Modifier, label: String, value: String, unit: String, valueColor: Color) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = valueColor.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = valueColor.copy(alpha = 0.7f), fontSize = 9.sp)
        }
    }
}
