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
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.tan

class MapWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { updateSingleWidget(context, manager, it, allowSignatureSkip = false) }
            } finally {
                pending.finish()
            }
        }
    }

    /** リサイズ時に新サイズで zoom を再計算して再描画 */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateSingleWidget(context, appWidgetManager, appWidgetId, allowSignatureSkip = false)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MapWidgetReceiver"
        private const val TILE_PX = 256
        private val lastRenderSignatures = ConcurrentHashMap<Int, MapWidgetRenderSignature>()
        private val baseMapCache = ConcurrentHashMap<Int, BaseMapCacheEntry>()

        private data class MapWidgetRenderSignature(
            val widgetId: Int,
            val widthPx: Int,
            val heightPx: Int,
            val latestTimestamp: Long,
            val entryCount: Int,
            val motionSampleCount: Int,
            val trackDigest: Long
        )

        private data class BaseMapSignature(
            val widgetId: Int,
            val widthPx: Int,
            val heightPx: Int,
            val zoomPermille: Int,
            val centerPxXMilli: Long,
            val centerPxYMilli: Long
        )

        private data class BaseMapCacheEntry(
            val signature: BaseMapSignature,
            val bitmap: Bitmap
        )

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MapWidgetReceiver::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                ids.forEach { updateSingleWidget(context, manager, it, allowSignatureSkip = true) }
            }
        }

        private suspend fun updateSingleWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            allowSignatureSkip: Boolean
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_map_layout)
                bindOpenAppClick(
                    views,
                    PendingIntent.getActivity(
                        context,
                        1001,
                        Intent(context, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_OPEN_MAP
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(MainActivity.EXTRA_OPEN_SCREEN, MainActivity.SCREEN_MAP)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

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
                val signature = MapWidgetRenderSignature(
                    widgetId = widgetId,
                    widthPx = wPx,
                    heightPx = hPx,
                    latestTimestamp = latest?.timestamp ?: -1L,
                    entryCount = normalizedEntries.size,
                    motionSampleCount = motionSamples.size,
                    trackDigest = trackDigest(normalizedEntries)
                )
                if (allowSignatureSkip && lastRenderSignatures[widgetId] == signature) {
                    Log.d(
                        TAG,
                        "WIDGET_RENDER_SKIPPED: widgetId=$widgetId reason=signature_unchanged " +
                            "latestTs=${signature.latestTimestamp} entries=${signature.entryCount}"
                    )
                    return
                }
                ExportUtil.writeVerboseDebugLog(
                    context,
                    "MAP_WIDGET_RENDER: widgetId=$widgetId rawEntries=${rawEntries.size} filteredEntries=${entries.size} " +
                        "widthPx=$wPx heightPx=$hPx latestTs=${latest?.timestamp ?: -1} bounds=$boundsText"
                )

                views.setImageViewBitmap(
                    R.id.widget_map_image,
                    createMapBitmap(context, widgetId, normalizedEntries, motionSamples, latest, wPx, hPx)
                )
                manager.updateAppWidget(widgetId, views)
                lastRenderSignatures[widgetId] = signature
                Log.d(
                    TAG,
                    "WIDGET_RENDERED: widgetId=$widgetId latestTs=${latest?.timestamp ?: -1} " +
                        "entries=${entries.size} widthPx=$wPx heightPx=$hPx"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Update Error", e)
            }
        }

        private fun bindOpenAppClick(views: RemoteViews, pendingIntent: PendingIntent) {
            views.setOnClickPendingIntent(R.id.widget_map_root, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_map_image, pendingIntent)
        }

        private fun trackDigest(entries: List<LogEntry>): Long {
            var hash = 17L
            entries.forEach { entry ->
                hash = 31L * hash + entry.timestamp
                hash = 31L * hash + ((entry.latitude ?: 0.0) * 1_000_000.0).roundToLong()
                hash = 31L * hash + ((entry.longitude ?: 0.0) * 1_000_000.0).roundToLong()
            }
            return hash
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
            // margin = 1.05 → トラックがウィジェットの 95% を占める（以前の 1.20 は 83% で小さすぎた）
            val margin = 1.05
            val dLon = abs(maxLon - minLon).coerceAtLeast(0.0001) * margin
            val zLon = ln((wPx * 360.0) / (dLon * TILE_PX)) / ln(2.0)
            val dY = abs(latToY(maxLat) - latToY(minLat)).coerceAtLeast(0.000001) * margin
            val zLat = ln(hPx / (dY * TILE_PX)) / ln(2.0)
            // 下限を 8.0 に下げる（広域トラックで自然ズームが 9 未満になる場合に対応）
            return minOf(zLon, zLat).coerceIn(8.0, 18.5)
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

        fun createMapBitmap(context: Context, widgetId: Int, entries: List<LogEntry>, motionSamples: List<com.example.gpspressurelogger.data.MotionSample>, latest: LogEntry?, wPx: Int, hPx: Int): Bitmap {
            val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.RGB_565)
            val canvas = Canvas(bmp)
            val density = context.resources.displayMetrics.density
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

            val baseMapSignature = BaseMapSignature(
                widgetId = widgetId,
                widthPx = wPx,
                heightPx = hPx,
                zoomPermille = (zoom * 1000.0).roundToLong().toInt(),
                centerPxXMilli = (cpx * 1000.0).roundToLong(),
                centerPxYMilli = (cpy * 1000.0).roundToLong()
            )
            val baseMapBitmap = obtainBaseMapBitmap(
                context = context,
                signature = baseMapSignature,
                baseZoom = baseZoom,
                scale = fScale,
                centerTileX = cxT,
                centerTileY = cyT,
                tileOffsetX = tOX,
                tileOffsetY = tOY,
                tileLimit = n,
                widthPx = wPx,
                heightPx = hPx
            )
            canvas.drawBitmap(baseMapBitmap, 0f, 0f, null)

            if (entries.isNotEmpty()) {
                val polylineTrack = GpsUtil.buildDisplayPolyline(entries, motionSamples)
                val polylineSegments = GpsUtil.splitTrackByMode(polylineTrack)
                val directionMarkers = GpsUtil.computeDirectionArrowMarkersOnScreen(
                    track = polylineTrack,
                    projectToScreen = { point -> toCanvas(point.lat, point.lon) },
                    params = GpsUtil.widgetDirectionArrowParams(density)
                )
                val stopMarkers = GpsUtil.clusterStops(entries)
                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    strokeWidth = GpsUtil.mapTrackStrokeWidthPx(GpsUtil.MarkerSurface.WIDGET, density)
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
                    textSize = GpsUtil.widgetDirectionArrowTextSize(density)
                }
                val arrowOutlinePaint = Paint(arrowPaint).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = GpsUtil.widgetDirectionArrowOutlineWidth(density)
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
                    val style = GpsUtil.widgetStopMarkerStyle(pt.stopCount, density)
                    markerPaint.style = Paint.Style.FILL
                    markerPaint.color = Color.WHITE
                    canvas.drawCircle(cx, cy, style.outerRadiusPx, markerPaint)
                    markerPaint.color = Color.GRAY
                    canvas.drawCircle(cx, cy, style.innerRadiusPx, markerPaint)
                }

                val start = entries.first()
                val (sx, sy) = toCanvas(start.latitude ?: return bmp, start.longitude ?: return bmp)
                val startStyle = GpsUtil.widgetStartMarkerStyle(density)
                markerPaint.style = Paint.Style.FILL
                markerPaint.color = Color.WHITE
                canvas.drawCircle(sx, sy, startStyle.outerRadiusPx, markerPaint)
                markerPaint.style = Paint.Style.STROKE
                markerPaint.strokeWidth = startStyle.strokeWidthPx
                markerPaint.color = Color.RED
                canvas.drawCircle(sx, sy, startStyle.innerRadiusPx, markerPaint)

                val latestPt = entries.last()
                val (lx, ly) = toCanvas(latestPt.latitude ?: return bmp, latestPt.longitude ?: return bmp)
                val currentStyle = GpsUtil.widgetCurrentMarkerStyle(density)
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

        private fun obtainBaseMapBitmap(
            context: Context,
            signature: BaseMapSignature,
            baseZoom: Int,
            scale: Double,
            centerTileX: Int,
            centerTileY: Int,
            tileOffsetX: Double,
            tileOffsetY: Double,
            tileLimit: Int,
            widthPx: Int,
            heightPx: Int
        ): Bitmap {
            val cached = baseMapCache[signature.widgetId]
            if (cached != null && cached.signature == signature && !cached.bitmap.isRecycled) {
                Log.d(
                    TAG,
                    "WIDGET_BASEMAP_CACHE_HIT: widgetId=${signature.widgetId} zoom=${signature.zoomPermille} " +
                        "centerPx=${signature.centerPxXMilli},${signature.centerPxYMilli}"
                )
                return cached.bitmap
            }

            val background = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.RGB_565)
            val backgroundCanvas = Canvas(background)
            backgroundCanvas.drawColor(Color.parseColor("#121E30"))
            val tileXCount = (widthPx / (TILE_PX * scale) / 2).toInt() + 2
            val tileYCount = (heightPx / (TILE_PX * scale) / 2).toInt() + 2
            for (dy in -tileYCount..tileYCount) {
                for (dx in -tileXCount..tileXCount) {
                    val tileX = centerTileX + dx
                    val tileY = centerTileY + dy
                    if (tileX < 0 || tileY < 0 || tileX >= tileLimit || tileY >= tileLimit) continue
                    val drawX = (tileOffsetX + dx * TILE_PX * scale).toFloat()
                    val drawY = (tileOffsetY + dy * TILE_PX * scale).toFloat()
                    if (drawX + TILE_PX * scale < 0 || drawX > widthPx || drawY + TILE_PX * scale < 0 || drawY > heightPx) continue
                    val tile = fetchTile(context, baseZoom, tileX, tileY)
                    if (tile != null) {
                        val dest = android.graphics.RectF(
                            drawX,
                            drawY,
                            (drawX + TILE_PX * scale).toFloat(),
                            (drawY + TILE_PX * scale).toFloat()
                        )
                        backgroundCanvas.drawBitmap(tile, null, dest, null)
                    }
                }
            }
            baseMapCache[signature.widgetId] = BaseMapCacheEntry(signature, background)
            Log.d(
                TAG,
                "WIDGET_BASEMAP_CACHE_MISS: widgetId=${signature.widgetId} zoom=${signature.zoomPermille} " +
                    "centerPx=${signature.centerPxXMilli},${signature.centerPxYMilli}"
            )
            return background
        }
    }
}
