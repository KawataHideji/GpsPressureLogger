package com.example.gpspressurelogger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.gpspressurelogger.util.ExportUtil

/**
 * 端末起動・アプリ更新後に LoggingService を自動復帰させるレシーバー。
 *
 * Android 12+ の「バックグラウンドから FGS 起動」制限に対しては、
 * manifest で `ACCESS_BACKGROUND_LOCATION` + `FOREGROUND_SERVICE_LOCATION` を
 * 宣言し、`LoggingService` を `foregroundServiceType="location"` として登録して
 * あるため、`ACTION_BOOT_COMPLETED` から `startForegroundService()` を呼ぶ経路は
 * 許可される（BOOT_COMPLETED は temporary allowlist 対象）。
 *
 * それでも一部の OEM / セキュリティ設定で拒否されるケースが観測されているので、
 * 失敗しても例外を握り潰してデバッグログに理由を残す。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        ExportUtil.writeDebugLog(context, "SERVICE_RECEIVER: action=$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                tryStartLoggingService(context, action)
            }
            Intent.ACTION_SHUTDOWN -> {
                ExportUtil.writeDebugLog(context, "SERVICE_SHUTDOWN_TRIGGER: action=$action")
            }
        }
    }

    private fun tryStartLoggingService(context: Context, action: String?) {
        try {
            val serviceIntent = Intent(context, LoggingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            ExportUtil.writeDebugLog(context, "SERVICE_RESTART_OK: action=$action")
        } catch (e: Throwable) {
            // Android 12+ の ForegroundServiceStartNotAllowedException 他、
            // OEM 個別制限に引っかかった場合はログだけ残して次の起動チャンス（画面ON時等）に委ねる。
            ExportUtil.writeDebugLog(
                context,
                "SERVICE_RESTART_FAILED: action=$action reason=${e.javaClass.simpleName}:${e.message ?: "unknown"}"
            )
        }
    }
}
