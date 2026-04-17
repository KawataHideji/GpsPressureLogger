package com.example.gpspressurelogger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.widget.RemoteViews
import com.example.gpspressurelogger.R
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.ui.MainActivity
import com.example.gpspressurelogger.util.ExportUtil
import com.example.gpspressurelogger.util.GpsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

class MapWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { updateSingleWidget(context, manager, it, WidgetUpdateReason.HOST) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MapWidgetReceiver"
        private const val TILE_PX = 256

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MapWidgetReceiver::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                ids.forEach { updateSingleWidget(context, manager, it, WidgetUpdateReason.SERVICE) }
            }
        }

        private suspend fun updateSingleWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            reason: WidgetUpdateReason
        ) {
            try {
                val settings = SettingsRepository(context)
                val views = RemoteViews(context.packageName, R.layout.widget_map_layout)
                bindOpenAppClick(
                    views,
                    PendingIntent.getActivity(
                        context,
                        1001,
                        Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(MainActivity.EXTRA_OPEN_SCREEN, MainActivity.SCREEN_MAP)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                if (!shouldRenderWidget(settings, reason)) {
                    manager.partiallyUpdateAppWidget(widgetId, views)
                    return
                }

                val db = AppDatabase.getInstance(context)
                val since = GpsUtil.getLoggingStart(System.currentTimeMillis())
                val rawEntries = db.logDao().getEntriesSince(since).first()
                val entries = GpsUtil.prepareMapEntries(rawEntries, since)
                val motionSamples = db.motionSampleDao().getBetween(since, since + GpsUtil.DAY_MS - 1).first()
                val latest = entries.lastOrNull() ?: rawEntries.firstOrNull { it.hasLocation }

                val opts = manager.getAppWidgetOptions(widgetId)
                val isPort = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val wDp = if (isPort) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
                else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300)
                val hDp = if (isPort) opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300)
                else opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300)

                val density = context.resources.displayMetrics.density
                var wPx = (wDp * density).toInt()
                var hPx = (hDp * density).toInt()
                val maxPx = 500_000
                if (wPx * hPx > maxPx) {
                    val scale = Math.sqrt(maxPx.toDouble() / (wPx * hPx))
                    wPx = (wPx * scale).toInt()
                    hPx = (hPx * scale).toInt()
                }

                val normalizedEntries = GpsUtil.normalizeStopsForDisplay(entries, motionSamples)
                val bounds = GpsUtil.calculateBounds(normalizedEntries)
                val boundsText = bounds?.let { "lat=${it.minLat}..${it.maxLat},lon=${it.minLon}..${it.maxLon}" } ?: "none"
                ExportUtil.writeVerboseDebugLog(
                    context,
                    "MAP_WIDGET_RENDER: widgetId=$widgetId rawEntries=${rawEntries.size} filteredEntries=${entries.size} " +
                        "widthPx=$wPx heightPx=$hPx latestTs=${latest?.timestamp ?: -1} bounds=$boundsText"
                )

                views.setImageViewBitmap(
                    R.id.widget_map_image,
                    createMapBitmap(context, normalizedEntries, motionSamples, latest, wPx, hPx)
                )
                manager.updateAppWidget(widgetId, views)
                settings.setMapWidgetLastRenderMs(System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Update Error", e)
            }
        }

        private fun bindOpenAppClick(views: RemoteViews, pendingIntent: PendingIntent) {
            views.setOnClickPendingIntent(R.id.widget_map_root, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_map_image, pendingIntent)
        }

        private suspend fun shouldRenderWidget(
            settings: SettingsRepository,
            reason: WidgetUpdateReason
        ): Boolean {
            return WidgetRenderGate.shouldRender(
                tag = TAG,
                reason = reason,
                intervalSec = settings.mapWidgetIntervalMin.first(),
                lastRenderMs = settings.mapWidgetLastRenderMs.first()
            )
        }

        private fun latToY(lat: Double): Double {
            val rad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
        }

        private fun yToLat(y: Double): Double {
            val a = Math.exp(PI * (1.0 - 2.0 * y))
            return Math.toDegrees(Math.asin((a * a - 1.0) / (a * a + 1.0)))
        }

        private fun toGlobalPx(lat: Double, lon: Double, zoom: Double): Pair<Double, Double> {
            val n = Math.pow(2.0, zoom)
            return Pair((lon + 180.0) / 360.0 * n * TILE_PX, latToY(lat) * n * TILE_PX)
        }

        private fun calculateBestZoom(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, wPx: Int, hPx: Int): Double {
            val margin = 1.20
            val dLon = abs(maxLon - minLon).coerceAtLeast(0.0001) * margin
            val zLon = ln((wPx * 360.0) / (dLon * TILE_PX)) / ln(2.0)
            val dY = abs(latToY(maxLat) - latToY(minLat)).coerceAtLeast(0.000001) * margin
            val zLat = ln(hPx / (dY * TILE_PX)) / ln(2.0)
            return minOf(zLon, zLat).coerceIn(10.0, 18.5)
        }

        private fun fetchTile(context: Context, zoom: Int, x: Int, y: Int): Bitmap? {
            val cacheFile = File(context.cacheDir, "widget_tiles/${zoom}_${x}_${y}.png")
            cacheFile.parentFile?.mkdirs()
            if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < 86400000L) {
                return BitmapFactory.decodeFile(cacheFile.absolutePath)
            }
            return try {
                val conn = URL("https://tile.openstreetmap.org/$zoom/$x/$y.png").openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "GpsPressureLogger/1.0")
                conn.connectTimeout = 5000
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return null
                }
                val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
                conn.disconnect()
                bmp?.also {
                    FileOutputStream(cacheFile).use { out -> it.compress(Bitmap.CompressFormat.PNG, 90, out) }
                }
            } catch (e: Exception) {
                null
            }
        }

        fun createMapBitmap(context: Context, entries: List<LogEntry>, motionSamples: List<com.example.gpspressurelogger.data.MotionSample>, latest: LogEntry?, wPx: Int, hPx: Int): Bitmap {
            val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            val centerLat: Double
            val centerLon: Double
            val zoom: Double

            if (entries.size >= 2) {
                val bounds = GpsUtil.calculateBounds(entries) ?: return bmp
                val minLat = bounds.minLat
                val maxLat = bounds.maxLat
                val minLon = bounds.minLon
                val maxLon = bounds.maxLon
                zoom = calculateBestZoom(minLat, maxLat, minLon, maxLon, wPx, hPx)
                centerLon = (minLon + maxLon) / 2.0
                centerLat = yToLat((latToY(maxLat) + latToY(minLat)) / 2.0)
            } else {
                centerLat = latest?.latitude ?: 35.6812
                centerLon = latest?.longitude ?: 139.7671
                zoom = 15.0
            }

            val baseZoom = floor(zoom).toInt()
            val fScale = Math.pow(2.0, zoom - baseZoom)
            val (cpx, cpy) = toGlobalPx(centerLat, centerLon, zoom)
            fun toCanvas(lat: Double, lon: Double): Pair<Float, Float> {
                val (px, py) = toGlobalPx(lat, lon, zoom)
                return Pair((wPx / 2.0 + (px - cpx)).toFloat(), (hPx / 2.0 + (py - cpy)).toFloat())
            }

            val (cpxB, cpyB) = toGlobalPx(centerLat, centerLon, baseZoom.toDouble())
            val cxT = floor(cpxB / TILE_PX).toInt()
            val cyT = floor(cpyB / TILE_PX).toInt()
            val tOX = wPx / 2.0 - (cpxB - cxT * TILE_PX) * fScale
            val tOY = hPx / 2.0 - (cpyB - cyT * TILE_PX) * fScale
            val n = 1 shl baseZoom

            canvas.drawColor(Color.parseColor("#121E30"))
            val tXC = (wPx / (TILE_PX * fScale) / 2).toInt() + 2
            val tYC = (hPx / (TILE_PX * fScale) / 2).toInt() + 2
            for (dy in -tYC..tYC) {
                for (dx in -tXC..tXC) {
                    val tx = cxT + dx
                    val ty = cyT + dy
                    if (tx < 0 || ty < 0 || tx >= n || ty >= n) continue
                    val tX = (tOX + dx * TILE_PX * fScale).toFloat()
                    val tY = (tOY + dy * TILE_PX * fScale).toFloat()
                    if (tX + TILE_PX * fScale < 0 || tX > wPx || tY + TILE_PX * fScale < 0 || tY > hPx) continue
                    val tile = fetchTile(context, baseZoom, tx, ty)
                    if (tile != null) {
                        val dest = android.graphics.RectF(tX, tY, (tX + TILE_PX * fScale).toFloat(), (tY + TILE_PX * fScale).toFloat())
                        canvas.drawBitmap(tile, null, dest, null)
                    }
                }
            }

            if (entries.isNotEmpty()) {
                val polylineTrack = GpsUtil.buildDisplayPolyline(entries, motionSamples)
                val polylineSegments = GpsUtil.splitTrackByMode(polylineTrack)
                val directionMarkers = GpsUtil.computeDirectionArrowMarkers(polylineTrack)
                val stopMarkers = GpsUtil.clusterStops(entries)
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    strokeWidth = 7f
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }

                polylineSegments.forEach { segment ->
                    val path = android.graphics.Path()
                    segment.points.forEachIndexed { index, pt ->
                        val (x, y) = toCanvas(pt.lat, pt.lon)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    linePaint.color = GpsUtil.modeColor(segment.displayMode)
                    canvas.drawPath(path, linePaint)
                }

                val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textSize = GpsUtil.directionArrowTextSize(GpsUtil.MarkerSurface.WIDGET)
                }
                val arrowOutlinePaint = Paint(arrowPaint).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = GpsUtil.directionArrowOutlineWidth(GpsUtil.MarkerSurface.WIDGET)
                    strokeJoin = Paint.Join.ROUND
                    strokeMiter = 10f
                }
                directionMarkers.forEach { arrow ->
                    val (x, y) = toCanvas(arrow.lat, arrow.lon)
                    arrowPaint.color = GpsUtil.modeColor(arrow.displayMode)
                    canvas.save()
                    canvas.rotate(arrow.angleDeg, x, y)
                    val baseline = y - (arrowPaint.descent() + arrowPaint.ascent()) / 2f
                    canvas.drawText(">", x, baseline, arrowOutlinePaint)
                    canvas.drawText(">", x, baseline, arrowPaint)
                    canvas.restore()
                }

                val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                stopMarkers.filter { it.isStop }.forEach { pt ->
                    val (cx, cy) = toCanvas(pt.lat, pt.lon)
                    val style = GpsUtil.stopMarkerStyle(pt.stopCount, GpsUtil.MarkerSurface.WIDGET)
                    markerPaint.style = Paint.Style.FILL
                    markerPaint.color = Color.WHITE
                    canvas.drawCircle(cx, cy, style.outerRadiusPx, markerPaint)
                    markerPaint.color = Color.GRAY
                    canvas.drawCircle(cx, cy, style.innerRadiusPx, markerPaint)
                }

                val start = entries.first()
                val (sx, sy) = toCanvas(start.latitude ?: return bmp, start.longitude ?: return bmp)
                val startStyle = GpsUtil.startMarkerStyle(GpsUtil.MarkerSurface.WIDGET)
                markerPaint.style = Paint.Style.FILL
                markerPaint.color = Color.WHITE
                canvas.drawCircle(sx, sy, startStyle.outerRadiusPx, markerPaint)
                markerPaint.style = Paint.Style.STROKE
                markerPaint.strokeWidth = startStyle.strokeWidthPx
                markerPaint.color = Color.RED
                canvas.drawCircle(sx, sy, startStyle.innerRadiusPx, markerPaint)

                val latestPt = entries.last()
                val (lx, ly) = toCanvas(latestPt.latitude ?: return bmp, latestPt.longitude ?: return bmp)
                val currentStyle = GpsUtil.currentMarkerStyle(GpsUtil.MarkerSurface.WIDGET)
                markerPaint.style = Paint.Style.FILL
                markerPaint.color = Color.WHITE
                canvas.drawCircle(lx, ly, currentStyle.outerRadiusPx, markerPaint)
                markerPaint.style = Paint.Style.STROKE
                markerPaint.strokeWidth = currentStyle.strokeWidthPx
                markerPaint.color = Color.BLUE
                canvas.drawCircle(lx, ly, currentStyle.innerRadiusPx, markerPaint)
            }
            return bmp
        }
    }
}
