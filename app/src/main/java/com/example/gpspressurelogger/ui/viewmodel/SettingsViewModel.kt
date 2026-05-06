package com.example.gpspressurelogger.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.SettingsRepository
import com.example.gpspressurelogger.util.ExportUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val settings = SettingsRepository(application)

    val intervalSec: StateFlow<Int> = settings.intervalSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_INTERVAL_SEC)

    val lookbackMin: StateFlow<Int> = settings.lookbackMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_LOOKBACK_MIN)

    val pressureWidgetIntervalMin: StateFlow<Int> = settings.pressureWidgetIntervalMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_PRESSURE_WIDGET_INTERVAL_MIN)

    val mapWidgetIntervalMin: StateFlow<Int> = settings.mapWidgetIntervalMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_MAP_WIDGET_INTERVAL_MIN)

    val widgetTransparency: StateFlow<Int> = settings.widgetTransparency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_WIDGET_TRANSPARENCY)

    val importFileUri: StateFlow<String?> = settings.importFileUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val motionImportFileUri: StateFlow<String?> = settings.motionImportFileUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val debugLogFileUri: StateFlow<String?> = settings.debugLogFileUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val driveDebugEnabled: StateFlow<Boolean> = settings.driveDebugEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val verboseDebugLogEnabled: StateFlow<Boolean> = settings.verboseDebugLogEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setIntervalSec(value: Int) = viewModelScope.launch {
        settings.setIntervalSec(value)
    }

    fun setLookbackMin(value: Int) = viewModelScope.launch {
        settings.setLookbackMin(value)
        com.example.gpspressurelogger.widget.PressureWidgetReceiver.forceUpdateAll(getApplication())
    }

    fun setPressureWidgetIntervalMin(value: Int) = viewModelScope.launch {
        settings.setPressureWidgetIntervalMin(value)
    }

    fun setMapWidgetIntervalMin(value: Int) = viewModelScope.launch {
        settings.setMapWidgetIntervalMin(value)
    }

    fun setWidgetTransparency(value: Int) = viewModelScope.launch {
        settings.setWidgetTransparency(value)
        com.example.gpspressurelogger.widget.PressureWidgetReceiver.forceUpdateAll(getApplication())
    }

    fun setImportFileUri(value: String?) = viewModelScope.launch {
        settings.setImportFileUri(value)
    }

    fun setMotionImportFileUri(value: String?) = viewModelScope.launch {
        settings.setMotionImportFileUri(value)
    }

    fun setDebugLogFileUri(value: String?) = viewModelScope.launch {
        settings.setDebugLogFileUri(value)
    }

    fun setDriveDebugEnabled(value: Boolean) = viewModelScope.launch {
        settings.setDriveDebugEnabled(value)
        ExportUtil.writeDebugLog(getApplication(), "DRIVE_DEBUG_CHANGED: enabled=$value")
    }

    fun setVerboseDebugLogEnabled(value: Boolean) = viewModelScope.launch {
        settings.setVerboseDebugLogEnabled(value)
        ExportUtil.writeDebugLog(getApplication(), "VERBOSE_DEBUG_CHANGED: enabled=$value")
    }

    fun syncDebugLogNow() = viewModelScope.launch {
        val fileUri = settings.debugLogFileUri.first()
        if (fileUri == null) {
            Toast.makeText(getApplication(), "先にデバッグログ出力先ファイルを選択してください", Toast.LENGTH_SHORT).show()
            return@launch
        }
        withContext(Dispatchers.IO) {
            ExportUtil.syncDebugLogToUri(getApplication(), Uri.parse(fileUri))
        }
        Toast.makeText(getApplication(), "デバッグログを送信しました", Toast.LENGTH_SHORT).show()
    }

    fun logDatabaseSummary() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            ExportUtil.logDatabaseSummary(getApplication())
        }
        Toast.makeText(getApplication(), "DB サマリをデバッグログへ出力しました", Toast.LENGTH_SHORT).show()
    }

    fun exportToUri(fileUri: Uri) = viewModelScope.launch {
        Toast.makeText(getApplication(), "エクスポートを開始します...", Toast.LENGTH_SHORT).show()
        val success = withContext(Dispatchers.IO) {
            ExportUtil.writeLocalDebugLog(getApplication(), "EXPORT_STANDARD_STARTED uri=$fileUri")
            try {
                val allEntries = db.logDao().getEntriesSinceOnce(0L)
                ExportUtil.writeLocalDebugLog(
                    getApplication(),
                    "EXPORT_STANDARD_REQUESTED uri=$fileUri rows=${allEntries.size}"
                )
                ExportUtil.writeEntriesToUri(getApplication(), fileUri, allEntries)
            } catch (e: Exception) {
                ExportUtil.writeLocalDebugLog(
                    getApplication(),
                    "EXPORT_STANDARD_FAILED uri=$fileUri reason=query:${e.javaClass.simpleName}:${e.message ?: "unknown"}"
                )
                false
            }
        }
        if (success) {
            Toast.makeText(getApplication(), "エクスポートが完了しました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(getApplication(), "エクスポートに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    fun exportMotionSamplesToUri(fileUri: Uri) = viewModelScope.launch {
        Toast.makeText(getApplication(), "状態イベントログのエクスポートを開始します...", Toast.LENGTH_SHORT).show()
        val success = withContext(Dispatchers.IO) {
            ExportUtil.writeLocalDebugLog(getApplication(), "EXPORT_MOTION_STARTED uri=$fileUri")
            try {
                val sampleCount = db.motionSampleDao().countSince(0L)
                ExportUtil.writeLocalDebugLog(
                    getApplication(),
                    "EXPORT_MOTION_REQUESTED uri=$fileUri rows=$sampleCount"
                )
                ExportUtil.writeMotionSamplesToUriPaged(getApplication(), fileUri, db)
            } catch (e: Throwable) {
                ExportUtil.writeLocalDebugLog(
                    getApplication(),
                    "EXPORT_MOTION_FAILED uri=$fileUri reason=query:${e.javaClass.simpleName}:${e.message ?: "unknown"}"
                )
                false
            }
        }
        if (success) {
            Toast.makeText(getApplication(), "状態イベントログのエクスポートが完了しました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(getApplication(), "状態イベントログのエクスポートに失敗しました", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Google ドライブからデータを復元（進捗表示付き）
     */
    fun triggerImport(overwrite: Boolean) = viewModelScope.launch {
        val fileUri = settings.importFileUri.first() ?: return@launch
        
        Toast.makeText(getApplication(), "インポートを開始します...", Toast.LENGTH_SHORT).show()
        
        var lastToastTime = 0L
        val report = withContext(Dispatchers.IO) {
            ExportUtil.importFromUriWithProgress(getApplication(), Uri.parse(fileUri), overwrite) { fileName, currentCount ->
                val now = System.currentTimeMillis()
                if (now - lastToastTime > 10_000L) {
                    viewModelScope.launch(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "読込中: $fileName\n合計: $currentCount 件", Toast.LENGTH_SHORT).show()
                    }
                    lastToastTime = now
                }
            }
        }
        
        if (report.processedFiles > 0 || report.hasIssues) {
            val issueSummary = buildString {
                if (report.skippedFiles.isNotEmpty()) append("\nスキップ: ${report.skippedFiles.size} 件")
                if (report.parseErrors.isNotEmpty()) append("\n解析エラー: ${report.parseErrors.size} 件")
            }
            Toast.makeText(
                getApplication(),
                "インポート完了: ${report.importedCount} レコード$issueSummary",
                Toast.LENGTH_LONG
            ).show()
            ExportUtil.writeDebugLog(
                getApplication(),
                "IMPORT_DONE: imported=${report.importedCount}, files=${report.processedFiles}, skipped=${report.skippedFiles.size}, parseErrors=${report.parseErrors.size}"
            )
            report.skippedFiles.take(10).forEach { issue ->
                ExportUtil.writeDebugLog(getApplication(), "IMPORT_SKIP: ${issue.fileName} ${issue.message}")
            }
            report.parseErrors.take(20).forEach { issue ->
                val linePart = issue.lineNumber?.let { " line=$it" } ?: ""
                ExportUtil.writeDebugLog(getApplication(), "IMPORT_PARSE_ERROR: ${issue.fileName}$linePart ${issue.message}")
            }
        } else {
            Toast.makeText(getApplication(), "エラーが発生しました", Toast.LENGTH_LONG).show()
        }
    }

    fun triggerMotionImport(overwrite: Boolean) = viewModelScope.launch {
        val fileUri = settings.motionImportFileUri.first() ?: return@launch

        Toast.makeText(getApplication(), "補助ログのインポートを開始します...", Toast.LENGTH_SHORT).show()

        var lastToastTime = 0L
        val report = withContext(Dispatchers.IO) {
            ExportUtil.importMotionSamplesFromUriWithProgress(getApplication(), Uri.parse(fileUri), overwrite) { fileName, currentCount ->
                val now = System.currentTimeMillis()
                if (now - lastToastTime > 10_000L) {
                    viewModelScope.launch(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "補助ログ読込中: $fileName\n合計: $currentCount 件", Toast.LENGTH_SHORT).show()
                    }
                    lastToastTime = now
                }
            }
        }

        if (report.processedFiles > 0 || report.hasIssues) {
            val issueSummary = buildString {
                if (report.skippedFiles.isNotEmpty()) append("\nスキップ: ${report.skippedFiles.size} 件")
                if (report.parseErrors.isNotEmpty()) append("\n解析エラー: ${report.parseErrors.size} 件")
            }
            Toast.makeText(
                getApplication(),
                "補助ログ import 完了: ${report.importedCount} レコード$issueSummary",
                Toast.LENGTH_LONG
            ).show()
            ExportUtil.writeDebugLog(
                getApplication(),
                "MOTION_IMPORT_DONE: imported=${report.importedCount}, files=${report.processedFiles}, skipped=${report.skippedFiles.size}, parseErrors=${report.parseErrors.size}"
            )
            report.skippedFiles.take(10).forEach { issue ->
                ExportUtil.writeDebugLog(getApplication(), "MOTION_IMPORT_SKIP: ${issue.fileName} ${issue.message}")
            }
            report.parseErrors.take(20).forEach { issue ->
                val linePart = issue.lineNumber?.let { " line=$it" } ?: ""
                ExportUtil.writeDebugLog(getApplication(), "MOTION_IMPORT_PARSE_ERROR: ${issue.fileName}$linePart ${issue.message}")
            }
        } else {
            Toast.makeText(getApplication(), "補助ログ import でエラーが発生しました", Toast.LENGTH_LONG).show()
        }
    }
}
