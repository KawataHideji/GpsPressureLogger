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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.res.Resources
import com.example.gpspressurelogger.ui.viewmodel.MapViewModel
import com.example.gpspressurelogger.util.GpsUtil
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    viewModel: MapViewModel = viewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val motionSamples by viewModel.motionSamples.collectAsState()
    val targetDateStart by viewModel.targetDateStart.collectAsState()
    val dateFormatter = remember { SimpleDateFormat("yyyy年M月d日", Locale.JAPAN) }
    var lastAutoFitTarget by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var lastEmptyResetTarget by remember { mutableLongStateOf(Long.MIN_VALUE) }

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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { viewModel.moveToPrevDay() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "前日") }
                        Text(text = dateFormatter.format(Date(targetDateStart)), style = MaterialTheme.typography.titleMedium)
                        val isToday = targetDateStart >= GpsUtil.getLoggingStart(System.currentTimeMillis())
                        IconButton(onClick = { viewModel.moveToNextDay() }, enabled = !isToday) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "翌日") }
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
                }
            },
            update = { mapView ->
                mapView.overlays.clear()
                val entriesReadyForTarget = entriesMatchTargetDay(entries, targetDateStart)

                if (!entriesReadyForTarget && lastEmptyResetTarget != targetDateStart) {
                    // 日付切り替え直後など、対象日のデータがまだ届いていない間は初期表示へ戻す。
                    // ここでは auto-fit 完了扱いにしない。あとから対象日の entries が届いたら 1 回だけ fit する。
                    mapView.post {
                        mapView.controller.setZoom(12.0)
                        mapView.controller.setCenter(GeoPoint(35.6812, 139.7671))
                    }
                    lastEmptyResetTarget = targetDateStart
                }

                if (entriesReadyForTarget) {
                    val displayEntries = entries
                    val polylineTrack = GpsUtil.buildDisplayPolyline(displayEntries, motionSamples)
                    val stopMarkers = GpsUtil.clusterStops(displayEntries)
                    val polylineSegments = GpsUtil.splitTrackByMode(polylineTrack)
                    val directionMarkers = GpsUtil.computeDirectionArrowMarkers(polylineTrack)
                    
                    // 1. 軌跡の描画（viewer と同じ mode 色 + 進行方向マーカー）
                    polylineSegments.forEach { segment ->
                        drawPolyline(mapView, segment)
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

                    // 2. 滞在マーカー
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

                    // 3. 特殊マーカー：開始点（赤〇中抜き）と現在点（青〇）を最前面へ
                    if (displayEntries.isNotEmpty()) {
                        // 開始点 (3時基準の最初のデータ)
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
                            setInfoWindow(null) // タップしても窓を出さない
                        })

                        // 現在地 (最新のデータ)
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

                    if (lastAutoFitTarget != targetDateStart) {
                        lastAutoFitTarget = targetDateStart
                        lastEmptyResetTarget = targetDateStart
                        GpsUtil.calculateBounds(displayEntries)?.let { bounds ->
                            mapView.post {
                                mapView.zoomToBoundingBox(
                                    BoundingBox(bounds.maxLat, bounds.maxLon, bounds.minLat, bounds.minLon),
                                    false,
                                    100
                                )
                                if (mapView.zoomLevelDouble > 16.0) mapView.controller.setZoom(16.0)
                            }
                        }
                    }
                }
                mapView.invalidate()
            }
        )
    }
}

private fun entriesMatchTargetDay(entries: List<LogEntry>, targetDateStart: Long): Boolean {
    if (entries.isEmpty()) return false
    val targetDateEnd = targetDateStart + GpsUtil.DAY_MS
    return entries.first().timestamp in targetDateStart until targetDateEnd &&
        entries.last().timestamp in targetDateStart until targetDateEnd
}

private fun drawPolyline(mapView: MapView, segment: GpsUtil.TrackSegment) {
    val polyline = Polyline(mapView).apply {
        outlinePaint.color = GpsUtil.modeColor(segment.displayMode)
        outlinePaint.strokeWidth = 10f
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

private fun createDirectionArrowDrawable(
    resources: Resources,
    color: Int,
    angleDeg: Float,
    surface: GpsUtil.MarkerSurface
): android.graphics.drawable.Drawable {
    val textSize = GpsUtil.directionArrowTextSize(surface)
    val padding = GpsUtil.directionArrowBitmapPadding(surface)
    val outlineWidth = GpsUtil.directionArrowOutlineWidth(surface)
    val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        this.textSize = textSize
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = outlineWidth
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeMiter = 10f
    }
    val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSize
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val glyphWidth = maxOf(outlinePaint.measureText(">"), fillPaint.measureText(">"))
    val size = (maxOf(glyphWidth, textSize) + padding * 2).toInt().coerceAtLeast((textSize + padding).toInt())
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.rotate(angleDeg, size / 2f, size / 2f)
    val baseline = size / 2f - (fillPaint.descent() + fillPaint.ascent()) / 2f
    canvas.drawText(">", size / 2f, baseline, outlinePaint)
    canvas.drawText(">", size / 2f, baseline, fillPaint)
    return android.graphics.drawable.BitmapDrawable(resources, bmp)
}
