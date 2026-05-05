package com.example.gpspressurelogger.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.Log
import android.widget.RemoteViews
import com.example.gpspressurelogger.R
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.sensor.MovementDetector
import com.example.gpspressurelogger.ui.MainActivity
import com.example.gpspressurelogger.util.GraphUtil
import com.example.gpspressurelogger.util.GpsUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 気圧・標高・歩数ウィジェット
 */
class PressureWidgetReceiver : AppWidgetProvider() {

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

    /** リサイズ時に新サイズでグラフを再描画 */
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
        private const val TAG = "PressureWidgetReceiver"
        private val lastRenderSignatures = ConcurrentHashMap<Int, PressureWidgetRenderSignature>()

        private data class PressureWidgetRenderSignature(
            val widgetId: Int,
            val widthPx: Int,
            val heightPx: Int,
            val lookbackMs: Long,
            val latestTimestamp: Long,
            val latestAltitudeM: Int?,
            val latestPressureRawTenth: Int?,
            val latestPressureQnhTenth: Int?,
            val stepsToday: Int,
            val rawEntryCount: Int,
            val motionSampleCount: Int,
            val transparency: Int
        )

        fun updateAll(context: Context) {
            updateAllWidgets(context)
        }

        fun forceUpdateAll(context: Context) {
            updateAllWidgets(context)
        }

        private fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val cn = ComponentName(context, PressureWidgetReceiver::class.java)
            val ids = mgr.getAppWidgetIds(cn)
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                ids.forEach { updateSingleWidget(context, mgr, it, allowSignatureSkip = true) }
            }
        }

        private suspend fun updateSingleWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            allowSignatureSkip: Boolean
        ) {
            try {
                val settings = SettingsRepository(context)
                val views = RemoteViews(context.packageName, R.layout.widget_pressure_layout)
                bindOpenAppClick(
                    views,
                    PendingIntent.getActivity(
                        context,
                        1002,
                        Intent(context, MainActivity::class.java).apply {
                            action = MainActivity.ACTION_OPEN_GRAPH
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(MainActivity.EXTRA_OPEN_SCREEN, MainActivity.SCREEN_HOME)
                            putExtra(MainActivity.EXTRA_RESET_GRAPH_WINDOW, true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )

                val db = AppDatabase.getInstance(context)
                val lookbackMin = settings.lookbackMin.first()
                val latest = db.logDao().getLatest()
                val lookbackMs = lookbackMin * 60_000L
                val windowEndMs = latest?.timestamp ?: System.currentTimeMillis()
                val opts = manager.getAppWidgetOptions(widgetId)
                val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
                val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 200)
                val density = context.resources.displayMetrics.density
                val wPx = (wDp * density).toInt().coerceAtLeast(200)
                val hPx = (hDp * density).toInt().coerceAtLeast(130)
                val windowStartMs = (windowEndMs - lookbackMs).coerceAtLeast(0L)
                val fetchStartMs = GraphUtil.graphSourceStartMs(windowStartMs)
                val rawEntries = db.logDao().getEntriesBetween(fetchStartMs, windowEndMs).first().reversed()
                val motionSamples = db.motionSampleDao().getBetween(fetchStartMs, windowEndMs).first().sortedBy { it.timestamp }
                val transparency = settings.widgetTransparency.first()

                val stepsToday = GraphUtil.calculateTodaySteps(rawEntries)
                val latestMetrics = GraphUtil.resolveLatestMetricValues(rawEntries.sortedBy { it.timestamp }, latest)
                val signature = PressureWidgetRenderSignature(
                    widgetId = widgetId,
                    widthPx = wPx,
                    heightPx = hPx,
                    lookbackMs = lookbackMs,
                    latestTimestamp = latest?.timestamp ?: -1L,
                    latestAltitudeM = latestMetrics.altitude?.roundToInt(),
                    latestPressureRawTenth = latestMetrics.pressureRaw?.let { (it * 10f).roundToInt() },
                    latestPressureQnhTenth = latestMetrics.pressureQnh?.let { (it * 10f).roundToInt() },
                    stepsToday = stepsToday,
                    rawEntryCount = rawEntries.size,
                    motionSampleCount = motionSamples.size,
                    transparency = transparency
                )
                if (allowSignatureSkip && lastRenderSignatures[widgetId] == signature) {
                    Log.d(
                        TAG,
                        "WIDGET_RENDER_SKIPPED: widgetId=$widgetId reason=signature_unchanged " +
                            "latestTs=${signature.latestTimestamp} rawRows=${signature.rawEntryCount} motionRows=${signature.motionSampleCount}"
                    )
                    return
                }

                views.setInt(R.id.widget_pressure_image, "setBackgroundColor", Color.TRANSPARENT)
                views.setImageViewBitmap(
                    R.id.widget_pressure_image,
                    createGraphBitmap(rawEntries, motionSamples, latestMetrics, stepsToday, wPx, hPx, density, transparency, lookbackMs, windowEndMs)
                )
                manager.updateAppWidget(widgetId, views)
                lastRenderSignatures[widgetId] = signature
                Log.d(
                    TAG,
                    "WIDGET_RENDERED: widgetId=$widgetId latestTs=${latest?.timestamp ?: -1} " +
                        "rows=${rawEntries.size} lookbackMs=$lookbackMs windowEndMs=$windowEndMs fetchStartMs=$fetchStartMs"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Update Error", e)
            }
        }

        private fun bindOpenAppClick(views: RemoteViews, pendingIntent: PendingIntent) {
            views.setOnClickPendingIntent(R.id.widget_pressure_root, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_pressure_image, pendingIntent)
        }

        private fun createGraphBitmap(
            rawEntries: List<LogEntry>,
            motionSamples: List<MotionSample>,
            latestMetrics: GraphUtil.LatestMetricValues,
            stepsToday: Int,
            w: Int,
            h: Int,
            density: Float,
            transparency: Int,
            lookbackMs: Long,
            windowEndMs: Long
        ): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.argb(transparency, 0x11, 0x11, 0x22))

            val valSz = 16f * density
            val lblSz = 10f * density
            val axisLblSz = 9f * density

            val items = listOf(
                Triple("歩数", "$stepsToday", GraphUtil.COLOR_STEPS),
                Triple("標高", latestMetrics.altitude?.let { "%.0fm".format(it) } ?: "-", GraphUtil.COLOR_ALT),
                Triple("気圧", latestMetrics.pressureRaw?.let { "%.1f".format(it) } ?: "-", GraphUtil.COLOR_PRES),
                Triple("補正", latestMetrics.pressureQnh?.let { "%.1f".format(it) } ?: "-", GraphUtil.COLOR_QNH)
            )
            val colW = w / 4f
            val pLbl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = lblSz
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val pVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = valSz
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            items.forEachIndexed { i, (lbl, v, c) ->
                val cx = colW * i + colW / 2f
                pLbl.color = c
                pLbl.alpha = 180
                canvas.drawText(lbl, cx, density * 4f + lblSz, pLbl)
                pVal.color = c
                pVal.alpha = 255
                canvas.drawText(v, cx, density * 8f + lblSz + valSz, pVal)
            }

            val d = GraphUtil.getProcessedSeriesForWindow(
                entries = rawEntries,
                motionSamples = motionSamples,
                intervalMs = 60_000L,
                windowStartMs = windowEndMs - lookbackMs,
                windowEndMs = windowEndMs
            )
            if (d == null) {
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.GRAY
                    textSize = axisLblSz
                    textAlign = Paint.Align.CENTER
                }.also {
                    canvas.drawText("データを収集中...", w / 2f, h * 0.75f, it)
                }
                return bmp
            }

            val padL = axisLblSz * 3.5f
            val padR = axisLblSz * 3.5f
            val plotW = w - padL - padR
            val metricValueBottom = density * 8f + lblSz + valSz + pVal.descent()
            val plotTop = maxOf(metricValueBottom + density * 10f, h * GraphUtil.PRES_TOP)
            val plotBottom = minOf(h * GraphUtil.STEPS_0, h - axisLblSz * 2f)
            val t0 = windowEndMs - lookbackMs
            val tr = lookbackMs.coerceAtLeast(1L)

            fun xOf(ts: Long) = padL + (ts - t0).toFloat() / tr * plotW
            fun yOfP(v: Float) = GraphUtil.valueToYRatio(v, d.pMin, d.pMax, GraphUtil.PRES_TOP, GraphUtil.PRES_BOT) * h
            fun yOfA(v: Float) = GraphUtil.valueToYRatio(v, d.aMin, d.aMax, GraphUtil.ALT_TOP, GraphUtil.ALT_BOT) * h
            fun yOfS(v: Float) = GraphUtil.stepsToYRatio(v, d.sMax) * h

            val pL = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = axisLblSz; color = 0xDC4CAF50.toInt(); textAlign = Paint.Align.RIGHT }
            val pR = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = axisLblSz; color = 0xDCFFEB3B.toInt(); textAlign = Paint.Align.LEFT }
            val pS = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = axisLblSz; color = GraphUtil.COLOR_STEPS; textAlign = Paint.Align.LEFT }
            val pG = Paint().apply { color = 0x15FFFFFF; strokeWidth = 1f }
            val pMidnight = Paint().apply {
                color = GraphUtil.COLOR_MIDNIGHT_LINE
                strokeWidth = 1f * density
            }
            val pMidnightLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = GraphUtil.COLOR_MIDNIGHT_LABEL
                textSize = axisLblSz
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.LEFT
            }
            val dateLabelFormat = SimpleDateFormat("M/d", Locale.JAPAN)
            val gap = axisLblSz * 0.15f

            listOf(d.pMax, d.pMin).forEach { v ->
                val y = yOfP(v)
                canvas.drawLine(padL, y, padL + plotW, y, pG)
                canvas.drawText("%.1f".format(v), padL - gap, y + axisLblSz / 3, pL)
            }
            val aTicks = listOf(d.aMax, d.aMin).map { it to yOfA(it) }
            aTicks.forEach { (v, y) ->
                canvas.drawLine(padL, y, padL + plotW, y, pG)
                canvas.drawText("%.0f".format(v), padL + plotW + gap, y + axisLblSz / 3, pR)
            }
            GraphUtil.getStepTicks(d.sMax).forEach { sv ->
                val yS = yOfS(sv)
                if (yS in 0f..h.toFloat()) {
                    val altYs = aTicks.map { it.second }
                    var nearestAltY = -1000f
                    var minDist = Float.MAX_VALUE
                    for (ay in altYs) {
                        val dst = abs(ay - yS)
                        if (dst < minDist) {
                            minDist = dst
                            nearestAltY = ay
                        }
                    }
                    val (offsetS, _) = GraphUtil.calculateVerticalOffset(yS, nearestAltY)
                    canvas.drawLine(padL, yS, padL + plotW, yS, Paint().apply { color = 0x08FFFFFF; strokeWidth = 1f })
                    canvas.drawText("%.0f".format(sv), padL + plotW + gap, yS + axisLblSz / 3 + offsetS, pS)
                }
            }

            GraphUtil.midnightTicks(t0, windowEndMs).forEach { ts ->
                val x = xOf(ts)
                if (x in padL..(padL + plotW)) {
                    canvas.drawLine(x, plotTop, x, plotBottom, pMidnight)
                    val label = dateLabelFormat.format(Date(ts))
                    val labelWidth = pMidnightLabel.measureText(label)
                    val labelX = if (x + density * 3f + labelWidth <= padL + plotW) {
                        pMidnightLabel.textAlign = Paint.Align.LEFT
                        x + density * 3f
                    } else {
                        pMidnightLabel.textAlign = Paint.Align.RIGHT
                        x - density * 3f
                    }
                    canvas.drawText(label, labelX, plotTop + axisLblSz, pMidnightLabel)
                }
            }

            fun drawPath(vals: List<Float?>, yOf: (Float) -> Float, c: Int, strokeW: Float, alpha: Int = 220, isSteps: Boolean = false) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = c
                    this.alpha = alpha
                    strokeWidth = strokeW
                    style = Paint.Style.STROKE
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
                val path = Path()
                var prevV = -1f
                var drawing = false
                vals.forEachIndexed { i, v ->
                    val value = v
                    if (value == null || !value.isFinite()) {
                        drawing = false
                        prevV = Float.NaN
                        return@forEachIndexed
                    }
                    val x = xOf(d.times[i])
                    val y = yOf(value)
                    if (!drawing || (isSteps && prevV.isFinite() && value < prevV)) {
                        path.moveTo(x, y)
                        drawing = true
                    } else {
                        path.lineTo(x, y)
                    }
                    prevV = value
                }
                canvas.drawPath(path, p)
            }
            listOf(
                MovementDetector.Mode.DEVICE_STILL,
                MovementDetector.Mode.STOPPED,
                MovementDetector.Mode.WALKING,
                MovementDetector.Mode.VEHICLE
            ).forEach { mode ->
                val values = d.steps.mapIndexed { index, value ->
                    if (d.stepModes[index] == mode) value else null
                }
                drawPath(values, ::yOfS, GpsUtil.modeColor(mode), 2f * density, 255, isSteps = true)
            }
            drawPath(d.alt, ::yOfA, GraphUtil.COLOR_ALT, 2f * density)
            drawPath(d.pres, ::yOfP, GraphUtil.COLOR_PRES, 2f * density)
            drawPath(d.qnh, ::yOfP, GraphUtil.COLOR_QNH, 2f * density)

            return bmp
        }
    }
}
