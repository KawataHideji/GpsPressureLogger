package com.example.gpspressurelogger

import android.app.Application
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.util.ExportUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class GpsLoggerApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installUnhandledExceptionLogger()
        // OSMDroid の設定
        // Android 11以降は外部ストレージ不可 → アプリ内部キャッシュを指定
        val config = Configuration.getInstance()
        config.userAgentValue = packageName
        val osmBase = File(cacheDir, "osmdroid")
        osmBase.mkdirs()
        config.osmdroidBasePath = osmBase
        config.osmdroidTileCache = File(osmBase, "tiles")

        applicationScope.launch {
            val settings = SettingsRepository(this@GpsLoggerApplication)
            if (settings.stepRepairVersion.first() < SettingsRepository.CURRENT_STEP_REPAIR_VERSION) {
                val repaired = ExportUtil.repairStepDataFromLocalLogs(this@GpsLoggerApplication)
                if (repaired) {
                    settings.setStepRepairVersion(SettingsRepository.CURRENT_STEP_REPAIR_VERSION)
                }
            }
        }
    }

    private fun installUnhandledExceptionLogger() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                ExportUtil.logUnhandledException(this, thread, throwable)
            } catch (_: Exception) {
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
