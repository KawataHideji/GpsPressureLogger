package com.example.gpspressurelogger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * アプリ設定（DataStore）
 */
class SettingsRepository(private val context: Context) {

    companion object {
        /** グラフで遡る時間（分） */
        val KEY_LOOKBACK_MIN = intPreferencesKey("lookback_min")
        /** 気圧・標高ウィジェット更新間隔（秒） */
        val KEY_PRESSURE_WIDGET_INTERVAL_MIN = intPreferencesKey("pressure_widget_interval_min")
        /** 地図ウィジェット更新間隔（秒） */
        val KEY_MAP_WIDGET_INTERVAL_MIN = intPreferencesKey("map_widget_interval_min")
        /** ウィジェットの背景透明度 (0-255) */
        val KEY_WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")
        /** import 用の標準バックアップ CSV URI */
        val KEY_IMPORT_FILE_URI = stringPreferencesKey("import_file_uri")
        /** Drive デバッグログ出力先ファイル URI */
        val KEY_DEBUG_LOG_FILE_URI = stringPreferencesKey("debug_log_file_uri")
        /** Google Drive へのデバッグログ送信 */
        val KEY_DRIVE_DEBUG_ENABLED = booleanPreferencesKey("drive_debug_enabled")
        /** 詳細デバッグログ */
        val KEY_VERBOSE_DEBUG_LOG_ENABLED = booleanPreferencesKey("verbose_debug_log_enabled")
        /** 解析用データ（生センサー値）の保持 ON/OFF */
        val KEY_ANALYSIS_DATA_ENABLED = booleanPreferencesKey("analysis_data_enabled")
        /** 起動時歩数修復の実行済みバージョン */
        val KEY_STEP_REPAIR_VERSION = intPreferencesKey("step_repair_version")

        const val DEFAULT_LOOKBACK_MIN = 3 * 24 * 60
        const val DEFAULT_PRESSURE_WIDGET_INTERVAL_MIN = 60
        const val DEFAULT_MAP_WIDGET_INTERVAL_MIN = 60
        const val DEFAULT_WIDGET_TRANSPARENCY = 255
        const val CURRENT_STEP_REPAIR_VERSION = 1
        const val MIN_WIDGET_INTERVAL_SEC = 30
        const val MAX_WIDGET_INTERVAL_SEC = 300
    }

    val lookbackMin: Flow<Int> = context.dataStore.data
        .map { it[KEY_LOOKBACK_MIN] ?: DEFAULT_LOOKBACK_MIN }

    val pressureWidgetIntervalMin: Flow<Int> = context.dataStore.data
        .map {
            (it[KEY_PRESSURE_WIDGET_INTERVAL_MIN] ?: DEFAULT_PRESSURE_WIDGET_INTERVAL_MIN)
                .coerceIn(MIN_WIDGET_INTERVAL_SEC, MAX_WIDGET_INTERVAL_SEC)
        }

    val mapWidgetIntervalMin: Flow<Int> = context.dataStore.data
        .map {
            (it[KEY_MAP_WIDGET_INTERVAL_MIN] ?: DEFAULT_MAP_WIDGET_INTERVAL_MIN)
                .coerceIn(MIN_WIDGET_INTERVAL_SEC, MAX_WIDGET_INTERVAL_SEC)
        }

    val widgetTransparency: Flow<Int> = context.dataStore.data
        .map { it[KEY_WIDGET_TRANSPARENCY] ?: DEFAULT_WIDGET_TRANSPARENCY }

    val importFileUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_IMPORT_FILE_URI] }

    val debugLogFileUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_DEBUG_LOG_FILE_URI] }

    val driveDebugEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DRIVE_DEBUG_ENABLED] ?: false }

    val verboseDebugLogEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VERBOSE_DEBUG_LOG_ENABLED] ?: false }

    val analysisDataEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ANALYSIS_DATA_ENABLED] ?: false }

    val stepRepairVersion: Flow<Int> = context.dataStore.data
        .map { it[KEY_STEP_REPAIR_VERSION] ?: 0 }

    suspend fun setLookbackMin(value: Int) {
        context.dataStore.edit { it[KEY_LOOKBACK_MIN] = value }
    }

    suspend fun setPressureWidgetIntervalMin(value: Int) {
        context.dataStore.edit {
            it[KEY_PRESSURE_WIDGET_INTERVAL_MIN] = value.coerceIn(MIN_WIDGET_INTERVAL_SEC, MAX_WIDGET_INTERVAL_SEC)
        }
    }

    suspend fun setMapWidgetIntervalMin(value: Int) {
        context.dataStore.edit {
            it[KEY_MAP_WIDGET_INTERVAL_MIN] = value.coerceIn(MIN_WIDGET_INTERVAL_SEC, MAX_WIDGET_INTERVAL_SEC)
        }
    }

    suspend fun setWidgetTransparency(value: Int) {
        context.dataStore.edit { it[KEY_WIDGET_TRANSPARENCY] = value }
    }

    suspend fun setImportFileUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(KEY_IMPORT_FILE_URI)
            else it[KEY_IMPORT_FILE_URI] = value
        }
    }

    suspend fun setDebugLogFileUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(KEY_DEBUG_LOG_FILE_URI)
            else it[KEY_DEBUG_LOG_FILE_URI] = value
        }
    }

    suspend fun setDriveDebugEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_DRIVE_DEBUG_ENABLED] = value }
    }

    suspend fun setVerboseDebugLogEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_VERBOSE_DEBUG_LOG_ENABLED] = value }
    }

    suspend fun setAnalysisDataEnabled(value: Boolean) {
        context.dataStore.edit { it[KEY_ANALYSIS_DATA_ENABLED] = value }
    }

    suspend fun setStepRepairVersion(value: Int) {
        context.dataStore.edit { it[KEY_STEP_REPAIR_VERSION] = value }
    }
}
