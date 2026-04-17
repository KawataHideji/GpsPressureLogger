package com.example.gpspressurelogger.widget

import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.SettingsRepository
import kotlinx.coroutines.flow.first

class PressureWidget : GlanceAppWidget() {

    companion object {
        private const val TAG = "PressureWidget"
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Log.d(TAG, "provideGlance 開始")

        var altText  = "記録なし"
        var pText    = ""
        var qnhText  = ""
        var hasData  = false

        try {
            val db = AppDatabase.getInstance(context)
            Log.d(TAG, "DB取得OK")
            val latest: LogEntry? = db.logDao().getLatest()
            if (latest != null) {
                hasData = true
                altText = latest.altitudeGps?.let { "${"%.1f".format(it)} m" } ?: "記録なし"
                pText   = latest.pressureRaw?.let { "気圧   ${"%.1f".format(it)} hPa" } ?: ""
                qnhText = latest.pressureQnh?.let { "QNH  ${"%.1f".format(it)} hPa" } ?: ""
                Log.d(TAG, "データあり: alt=$altText p=$pText")
            } else {
                Log.d(TAG, "データなし")
            }
        } catch (e: Exception) {
            Log.e(TAG, "データ取得エラー", e)
        }

        val white     = ColorProvider(AndroidColor.WHITE)
        val gray      = ColorProvider(AndroidColor.rgb(150, 150, 150))
        val green     = ColorProvider(AndroidColor.rgb(100, 220, 100))
        val cyan      = ColorProvider(AndroidColor.rgb(0, 200, 220))

        val capturedHasData  = hasData
        val capturedAltText  = altText
        val capturedPText    = pText
        val capturedQnhText  = qnhText

        Log.d(TAG, "provideContent 呼び出し hasData=$hasData")
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(com.example.gpspressurelogger.R.drawable.widget_background)
                    .padding(14.dp)
            ) {
                Text(
                    text = "GPS 気圧ロガー",
                    style = TextStyle(color = gray, fontSize = 11.sp)
                )
                Spacer(GlanceModifier.height(8.dp))

                Text(
                    text = capturedAltText,
                    style = TextStyle(
                        color = white,
                        fontSize = if (capturedHasData) 26.sp else 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                Spacer(GlanceModifier.height(6.dp))

                Text(
                    text = if (capturedHasData) capturedPText else "アプリを起動してGPS安定後に表示",
                    style = TextStyle(
                        color = if (capturedHasData) green else gray,
                        fontSize = if (capturedHasData) 15.sp else 11.sp
                    )
                )

                Spacer(GlanceModifier.height(4.dp))

                Text(
                    text = capturedQnhText,
                    style = TextStyle(color = cyan, fontSize = 15.sp)
                )
            }
        }
    }
}
