package com.example.gpspressurelogger.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.gpspressurelogger.util.ExportUtil

/**
 * 端末イベントの調査ログを残すレシーバー。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        ExportUtil.writeDebugLog(context, "SERVICE_RECEIVER: action=$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                ExportUtil.writeDebugLog(context, "SERVICE_RESTART_SKIPPED: action=$action reason=background_location_fgs_restricted")
            }
            Intent.ACTION_SHUTDOWN -> {
                ExportUtil.writeDebugLog(context, "SERVICE_SHUTDOWN_TRIGGER: action=$action")
            }
        }
    }
}
