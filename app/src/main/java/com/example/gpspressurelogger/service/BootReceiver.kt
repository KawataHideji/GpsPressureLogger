package com.example.gpspressurelogger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.gpspressurelogger.util.ExportUtil

/**
 * 端末起動時またはアプリ更新時にLoggingServiceを自動再開し、
 * 調査用に終了・再開トリガも記録するレシーバー
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        ExportUtil.writeDebugLog(context, "SERVICE_RECEIVER: action=$action")
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ExportUtil.writeDebugLog(context, "SERVICE_RESTART_TRIGGER: action=$action")
            val serviceIntent = Intent(context, LoggingService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        } else if (action == Intent.ACTION_SHUTDOWN) {
            ExportUtil.writeDebugLog(context, "SERVICE_SHUTDOWN_TRIGGER: action=$action")
        }
    }
}
