package com.example.gpspressurelogger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.content.res.Resources
import com.example.gpspressurelogger.ui.viewmodel.MapViewModel
import com.example.gpspressurelogger.util.GpsUtil
import kotlinx.coroutines.delay
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample

// パン・ズームの最後のイベントから何 ms 静音だったら「操作終了」とみなすか。
// 短すぎるとジェスチャ最中のわずかな間隙で再描画が走り、長すぎると操作後の更新が遅れる。
private const val MAP_INTERACTION_QUIET_MS: Long = 600L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    viewModel: MapViewModel = viewModel()
) {
    val mapUiState by viewModel.mapUiState.collectAsState()
    val entries = mapUiState.entries
    val motionSamples = mapUiState.motionSamples
    val targetDateStart = mapUiState.targetDateStart
    val dateFormatter = remember { SimpleDateFormat("yyyy年M月d日", Locale.JAPAN) }
    var lastAutoFitTarget by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var lastEmptyResetTarget by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var lastRenderSignature by remember { mutableStateOf<MapRenderSignature?>(null) }
    var showStateLabels by remember { mutableStateOf(false) }
    var showTrkLabels by remember { mutableStateOf(false) }
    // パン・ズーム最終時刻と「操作終了後の再描画キック」用カウンタ。
    // MapListener から lastInteractionMs を更新し、LaunchedEffect が静音期間後に
    // postInteractionTick をインクリメントして AndroidView の update を再起動させる。
    var lastInteractionMs by remember { mutableLongStateOf(0L) }
    var postInteractionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastInteractionMs) {
        if (lastInteractionMs > 0L) {
            // 静音時間 + 余裕分待ってから再描画チャンスを与える。
            delay(MAP_INTERACTION_QUIET_MS + 100L)
            postInteractionTick += 1
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("移動軌跡") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                    }
                )
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 4.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.moveToPrevDay() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "前日")
                        }
                        Text(
                            text = dateFormatter.format(Date(targetDateStart)),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center
                        )
                        val isToday = targetDateStart >= GpsUtil.getLoggingStart(System.currentTimeMillis())
                        IconButton(onClick = { viewModel.moveToNextDay() }, enabled = !isToday) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "翌日")
                        }
                        // 状態ラベル表示トグル
                        TextButton(
                            onClick = { showStateLabels = !showStateLabels },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (showStateLabels)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                text = "K/W",
                                fontSize = 13.sp,
                                fontWeight = if (showStateLabels)
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                else
                                    androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                        TextButton(
                            onClick = { showTrkLabels = !showTrkLabels },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (showTrkLabels)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text(
                                text = "trK",
                                fontSize = 13.sp,
                                fontWeight = if (showTrkLabels)
                                    androidx.compose.ui.text.font.FontWeight.Bold
                                else
                                    androidx.compose.ui.text.font.FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(12.0)
                    controller.setCenter(GeoPoint(35.6812, 139.7671))
                    // パン・ズーム検知：操作開始時刻を記録し、live refresh による割り込み再描画を一時抑止する。
                    addMapListener(object : MapListener {
                        override fun onScroll(event: ScrollEvent?): Boolean {
                            lastInteractionMs = System.currentTimeMillis()
                            return false
                        }
                        override fun onZoom(event: ZoomEvent?): Boolean {
                            lastInteractionMs = System.currentTimeMillis()
                            return false
                        }
                    })
                }
            },
            update = { mapView ->
                // postInteractionTick の読み取り：操作終了後にこの値が変わると update が再実行され、
                // 直前の live refresh emit による未描画状態を遅延描画できる。
                @Suppress("UNUSED_VARIABLE")
                val tick = postInteractionTick
                val nowMs = System.currentTimeMillis()
                val isInteracting = lastInteractionMs > 0L &&
                    (nowMs - lastInteractionMs) < MAP_INTERACTION_QUIET_MS
                val entriesReadyForTarget = entriesMatchTargetDay(entries, targetDateStart)

                if (!entriesReadyForTarget && lastEmptyResetTarget != targetDateStart) {
                    // 日付切り替え直後など、対象日のデータがまだ届いていない間は初期表示へ戻す。
                    // ここでは auto-fit 完了扱いにしない。あとから対象日の entries が届いたら 1 回だけ fit する。
                    mapView.post {
                        mapView.overlays.clear()
                        mapView.controller.setZoom(12.0)
                        mapView.controller.setCenter(GeoPoint(35.6812, 139.7671))
                        lastRenderSignature = null
                        mapView.invalidate()
                    }
                    lastEmptyResetTarget = targetDateStart
                }

                if (entriesReadyForTarget) {
                    val displayEntries = entries
                    val renderSignature = MapRenderSignature.from(
                        targetDateStart = targetDateStart,
                        entries = displayEntries,
                        motionSamples = motionSamples,
                        showStateLabels = showStateLabels,
                        showTrkLabels = showTrkLabels
                    )
                    if (lastRenderSignature == renderSignature && lastAutoFitTarget == targetDateStart) {
                        return@AndroidView
                    }

                    // パン・ズーム中は再描画を遅延させて操作のがたつきを防ぐ。
                    // ただし初回描画（lastRenderSignature == null）は無条件で行う。
                    // 静音期間後に LaunchedEffect が postInteractionTick を進めて update を再実行する。
                    if (isInteracting && lastRenderSignature != null) {
                        return@AndroidView
                    }

                    if (lastAutoFitTarget != targetDateStart) {
                        lastAutoFitTarget = targetDateStart
                        lastEmptyResetTarget = targetDateStart
                        GpsUtil.calculateBounds(displayEntries)?.let { bounds ->
                            val capturedShowLabels = showStateLabels
                            val capturedShowTrkLabels = showTrkLabels
                            mapView.post {
                                mapView.overlays.clear()
                                mapView.zoomToBoundingBox(
                                    BoundingBox(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon),
                                    false,
                                    100
                                )
                                if (mapView.zoomLevelDouble > 16.0) mapView.controller.setZoom(16.0)
                                renderMapOverlays(
                                    mapView,
                                    displayEntries,
                                    motionSamples,
                                    capturedShowLabels,
                                    capturedShowTrkLabels
                                )
                                lastRenderSignature = renderSignature.copy(
                                    showStateLabels = capturedShowLabels,
                                    showTrkLabels = capturedShowTrkLabels
                                )
                                mapView.invalidate()
                            }
                        } ?: run {
                            mapView.overlays.clear()
                            renderMapOverlays(mapView, displayEntries, motionSamples, showStateLabels, showTrkLabels)
                            lastRenderSignature = renderSignature
                            mapView.invalidate()
                        }
                    } else {
                        mapView.overlays.clear()
                        renderMapOverlays(mapView, displayEntries, motionSamples, showStateLabels, showTrkLabels)
                        lastRenderSignature = renderSignature
                        mapView.invalidate()
                    }
                }
            }
        )
    }
}

private data class MapRenderSignature(
    val targetDateStart: Long,
    val entryCount: Int,
    val firstEntryTimestamp: Long,
    val lastEntryTimestamp: Long,
    val motionSampleCount: Int,
    val lastMotionSampleTimestamp: Long,
    val showStateLabels: Boolean,
    val showTrkLabels: Boolean
) {
    companion object {
        fun from(
            targetDateStart: Long,
            entries: List<LogEntry>,
            motionSamples: List<MotionSample>,
            showStateLabels: Boolean,
            showTrkLabels: Boolean
        ): MapRenderSignature {
            return MapRenderSignature(
                targetDateStart = targetDateStart,
                entryCount = entries.size,
                firstEntryTimestamp = entries.firstOrNull()?.timestamp ?: Long.MIN_VALUE,
                lastEntryTimestamp = entries.lastOrNull()?.timestamp ?: Long.MIN_VALUE,
                motionSampleCount = motionSamples.size,
                lastMotionSampleTimestamp = motionSamples.lastOrNull()?.timestamp ?: Long.MIN_VALUE,
                showStateLabels = showStateLabels,
                showTrkLabels = showTrkLabels
            )
        }
    }
}

private fun entriesMatchTargetDay(entries: List<LogEntry>, targetDateStart: Long): Boolean {
    if (entries.isEmpty()) return false
    val targetDateEnd = targetDateStart + GpsUtil.DAY_MS
    return entries.first().timestamp in targetDateStart until targetDateEnd &&
        entries.last().timestamp in targetDateStart until targetDateEnd
}

private fun renderMapOverlays(
    mapView: MapView,
    displayEntries: List<LogEntry>,
    motionSamples: List<com.example.gpspressurelogger.data.MotionSample>,
    showStateLabels: Boolean = false,
    showTrkLabels: Boolean = false
) {
    val polylineTrack = GpsUtil.buildDisplayPolyline(displayEntries, motionSamples)
    val stopMarkers = GpsUtil.clusterStops(displayEntries)
    val polylineSegments = GpsUtil.splitTrackByMode(polylineTrack)
    val gpsGapSegments = GpsUtil.buildGpsGapBreakSegments(polylineTrack)
    val density = mapView.context.resources.displayMetrics.density
    val directionMarkers = GpsUtil.computeDirectionArrowMarkersOnScreen(
        track = polylineTrack,
        projectToScreen = { point ->
            val pixel = mapView.projection.toPixels(GeoPoint(point.lat, point.lon), null)
            pixel.x.toFloat() to pixel.y.toFloat()
        },
        params = GpsUtil.appDirectionArrowParams(density)
    )
    // 軌跡は地図の auto-fit / zoom が終わった後に overlay として追加する。
    polylineSegments.forEach { segment ->
        drawPolyline(mapView, segment)
    }
    gpsGapSegments.forEach { segment ->
        drawGpsGapBreakPolyline(mapView, segment)
    }
    directionMarkers.forEach { arrow ->
        mapView.overlays.add(Marker(mapView).apply {
            position = GeoPoint(arrow.lat, arrow.lon)
            icon = createDirectionArrowDrawable(
                resources = mapView.context.resources,
                color = GpsUtil.modeColor(arrow.displayMode),
                angleDeg = arrow.angleDeg,
                surface = GpsUtil.MarkerSurface.APP_MAP
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        })
    }

    stopMarkers.filter { it.isStop }.forEach { pt ->
        mapView.overlays.add(Marker(mapView).apply {
            position = GeoPoint(pt.lat, pt.lon)
            icon = createCircleDrawable(
                resources = mapView.context.resources,
                color = 0xAA888888.toInt(),
                style = GpsUtil.stopMarkerStyle(pt.stopCount, GpsUtil.MarkerSurface.APP_MAP),
                isHollow = false
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "滞在点 (${pt.stopCount}点)"
        })
    }

    if (displayEntries.isNotEmpty()) {
        val start = displayEntries.first()
        mapView.overlays.add(Marker(mapView).apply {
            position = GeoPoint(start.latitude ?: return@apply, start.longitude ?: return@apply)
            icon = createCircleDrawable(
                resources = mapView.context.resources,
                color = android.graphics.Color.RED,
                style = GpsUtil.startMarkerStyle(GpsUtil.MarkerSurface.APP_MAP),
                isHollow = true
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        })

        val latest = displayEntries.last()
        mapView.overlays.add(Marker(mapView).apply {
            position = GeoPoint(latest.latitude ?: return@apply, latest.longitude ?: return@apply)
            icon = createCircleDrawable(
                resources = mapView.context.resources,
                color = android.graphics.Color.BLUE,
                style = GpsUtil.currentMarkerStyle(GpsUtil.MarkerSurface.APP_MAP),
                isHollow = true
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        })
    }

    // 状態ラベル：stK / W / 定速領域の変化点を地図上に表示する
    if (showStateLabels) {
        renderStateLabels(mapView, displayEntries, motionSamples)
    }
    if (showTrkLabels) {
        renderTrkLabels(mapView, displayEntries, motionSamples)
    }
}

private fun drawPolyline(mapView: MapView, segment: GpsUtil.TrackSegment) {
    val polyline = Polyline(mapView).apply {
        outlinePaint.color = GpsUtil.modeColor(segment.displayMode)
        outlinePaint.strokeWidth = GpsUtil.mapTrackStrokeWidthPx(
            GpsUtil.MarkerSurface.APP_MAP,
            mapView.context.resources.displayMetrics.density
        )
        setPoints(segment.points.map { GeoPoint(it.lat, it.lon) })
    }
    mapView.overlays.add(polyline)
}

private fun drawGpsGapBreakPolyline(mapView: MapView, segment: GpsUtil.TrackSegment) {
    val density = mapView.context.resources.displayMetrics.density
    val strokeWidth = GpsUtil.mapTrackStrokeWidthPx(GpsUtil.MarkerSurface.APP_MAP, density)
    val polyline = Polyline(mapView).apply {
        outlinePaint.color = android.graphics.Color.rgb(245, 158, 11)
        outlinePaint.strokeWidth = strokeWidth * 1.35f
        outlinePaint.strokeCap = android.graphics.Paint.Cap.BUTT
        outlinePaint.pathEffect = DashPathEffect(floatArrayOf(strokeWidth * 4.0f, strokeWidth * 2.4f), 0f)
        setPoints(segment.points.map { GeoPoint(it.lat, it.lon) })
    }
    mapView.overlays.add(polyline)
}

private fun createCircleDrawable(
    resources: Resources,
    color: Int,
    style: GpsUtil.MarkerStyle,
    isHollow: Boolean
): android.graphics.drawable.Drawable {
    val size = (style.outerRadiusPx * 2 + 10).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    paint.style = android.graphics.Paint.Style.FILL
    canvas.drawCircle(size / 2f, size / 2f, style.outerRadiusPx, paint)
    paint.color = color
    if (isHollow) {
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = style.strokeWidthPx
        canvas.drawCircle(size / 2f, size / 2f, style.innerRadiusPx, paint)
    } else {
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawCircle(size / 2f, size / 2f, style.innerRadiusPx, paint)
    }
    return android.graphics.drawable.BitmapDrawable(resources, bmp)
}

/**
 * stK / W / 定速領域の状態遷移点を小さなラベルバッジとして地図上に追加する。
 */
private fun renderStateLabels(
    mapView: MapView,
    displayEntries: List<LogEntry>,
    motionSamples: List<MotionSample>
) {
    val labels = GpsUtil.buildStateLabels(displayEntries, motionSamples)
    labels.forEach { label ->
        mapView.overlays.add(Marker(mapView).apply {
            position = GeoPoint(label.lat, label.lon)
            icon = createStateLabelDrawable(
                resources = mapView.context.resources,
                text = label.text,
                bgColor = label.bgColor
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        })
    }
}

private fun renderTrkLabels(
    mapView: MapView,
    displayEntries: List<LogEntry>,
    motionSamples: List<MotionSample>
) {
    val labels = GpsUtil.buildTrkLabels(displayEntries, motionSamples)
    labels.forEach { label ->
        mapView.overlays.add(Marker(mapView).apply {
            position = GeoPoint(label.lat, label.lon)
            icon = createStateLabelDrawable(
                resources = mapView.context.resources,
                text = label.text,
                bgColor = label.bgColor
            )
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
        })
    }
}

/**
 * 角丸四角形に白テキストを描いた、状態ラベル用の小さなバッジ Drawable を生成する。
 */
private fun createStateLabelDrawable(
    resources: Resources,
    text: String,
    bgColor: Int
): android.graphics.drawable.Drawable {
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = android.graphics.Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val padH = 11f
    val padV = 6f
    val textWidth = textPaint.measureText(text)
    val textHeight = textPaint.descent() - textPaint.ascent()
    val width = (textWidth + padH * 2).toInt().coerceAtLeast(44)
    val height = (textHeight + padV * 2).toInt().coerceAtLeast(30)

    val bmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)

    // 影（視認性向上）
    val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawRoundRect(
        android.graphics.RectF(2f, 2f, width.toFloat(), height.toFloat()),
        9f, 9f, shadowPaint
    )

    // バッジ背景
    val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = android.graphics.Paint.Style.FILL
    }
    canvas.drawRoundRect(
        android.graphics.RectF(0f, 0f, (width - 2).toFloat(), (height - 2).toFloat()),
        8f, 8f, bgPaint
    )

    // テキスト
    val baseline = (height - 2) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(text, (width - 2) / 2f, baseline, textPaint)

    return android.graphics.drawable.BitmapDrawable(resources, bmp)
}

private fun createDirectionArrowDrawable(
    resources: Resources,
    color: Int,
    angleDeg: Float,
    surface: GpsUtil.MarkerSurface
): android.graphics.drawable.Drawable {
    val textSize = GpsUtil.directionArrowTextSize(surface)
    val padding = GpsUtil.directionArrowBitmapPadding(surface)
    val outlineWidth = GpsUtil.directionArrowOutlineWidth(surface)
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeWidth = outlineWidth
        strokeJoin = Paint.Join.ROUND
        strokeMiter = 10f
    }
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.FILL
    }
    val glyphWidth = maxOf(outlinePaint.measureText(">"), fillPaint.measureText(">"))
    val size = (maxOf(glyphWidth, textSize) + padding * 2).toInt().coerceAtLeast((textSize + padding).toInt())
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.rotate(angleDeg, size / 2f, size / 2f)
    val baseline = size / 2f - (fillPaint.descent() + fillPaint.ascent()) / 2f
    canvas.drawText(">", size / 2f, baseline, outlinePaint)
    canvas.drawText(">", size / 2f, baseline, fillPaint)
    return android.graphics.drawable.BitmapDrawable(resources, bmp)
}
