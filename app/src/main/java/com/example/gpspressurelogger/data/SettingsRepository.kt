package com.example.gpspressurelogger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
        /** GPS記録間隔（秒） */
        val KEY_INTERVAL_SEC = intPreferencesKey("interval_sec")
        /** グラフで遡る時間（分） */
        val KEY_LOOKBACK_MIN = intPreferencesKey("lookback_min")
        /** 気圧・標高ウィジェット更新間隔（秒） */
        val KEY_PRESSURE_WIDGET_INTERVAL_MIN = intPreferencesKey("pressure_widget_interval_min")
        /** 地図ウィジェット更新間隔（秒） */
        val KEY_MAP_WIDGET_INTERVAL_MIN = intPreferencesKey("map_widget_interval_min")
        /** 気圧・標高ウィジェットの最終描画時刻 */
        val KEY_PRESSURE_WIDGET_LAST_RENDER_MS = longPreferencesKey("pressure_widget_last_render_ms")
        /** 地図ウィジェットの最終描画時刻 */
        val KEY_MAP_WIDGET_LAST_RENDER_MS = longPreferencesKey("map_widget_last_render_ms")
        /** ウィジェットの背景透明度 (0-255) */
        val KEY_WIDGET_TRANSPARENCY = intPreferencesKey("widget_transparency")
        /** GPS不在時のセンサー強制記録間隔（秒） */
        val KEY_SENSOR_ONLY_INTERVAL_SEC = intPreferencesKey("sensor_only_interval_sec")
        /** import 用の標準バックアップ CSV URI */
        val KEY_IMPORT_FILE_URI = stringPreferencesKey("import_file_uri")
        /** import 用の補助センサー判定ログ CSV URI */
        val KEY_MOTION_IMPORT_FILE_URI = stringPreferencesKey("motion_import_file_uri")
        /** Drive デバッグログ出力先ファイル URI */
        val KEY_DEBUG_LOG_FILE_URI = stringPreferencesKey("debug_log_file_uri")
        /** Google Drive へのデバッグログ送信 */
        val KEY_DRIVE_DEBUG_ENABLED = booleanPreferencesKey("drive_debug_enabled")
        /** 詳細デバッグログ */
        val KEY_VERBOSE_DEBUG_LOG_ENABLED = booleanPreferencesKey("verbose_debug_log_enabled")
        /** 起動時歩数修復の実行済みバージョン */
        val KEY_STEP_REPAIR_VERSION = intPreferencesKey("step_repair_version")

        const val DEFAULT_INTERVAL_SEC = 10
        const val DEFAULT_LOOKBACK_MIN = 3 * 24 * 60
        const val DEFAULT_PRESSURE_WIDGET_INTERVAL_MIN = 60
        const val DEFAULT_MAP_WIDGET_INTERVAL_MIN = 60
        const val DEFAULT_WIDGET_TRANSPARENCY = 255
        const val DEFAULT_SENSOR_ONLY_INTERVAL_SEC = 30
        const val CURRENT_STEP_REPAIR_VERSION = 1
        const val MIN_WIDGET_INTERVAL_SEC = 30
        const val MAX_WIDGET_INTERVAL_SEC = 300
    }

    val intervalSec: Flow<Int> = context.dataStore.data
        .map { it[KEY_INTERVAL_SEC] ?: DEFAULT_INTERVAL_SEC }

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

    val pressureWidgetLastRenderMs: Flow<Long> = context.dataStore.data
        .map { it[KEY_PRESSURE_WIDGET_LAST_RENDER_MS] ?: 0L }

    val mapWidgetLastRenderMs: Flow<Long> = context.dataStore.data
        .map { it[KEY_MAP_WIDGET_LAST_RENDER_MS] ?: 0L }

    val widgetTransparency: Flow<Int> = context.dataStore.data
        .map { it[KEY_WIDGET_TRANSPARENCY] ?: DEFAULT_WIDGET_TRANSPARENCY }

    val sensorOnlyIntervalSec: Flow<Int> = context.dataStore.data
        .map { it[KEY_SENSOR_ONLY_INTERVAL_SEC] ?: DEFAULT_SENSOR_ONLY_INTERVAL_SEC }

    val importFileUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_IMPORT_FILE_URI] }

    val motionImportFileUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_MOTION_IMPORT_FILE_URI] }

    val debugLogFileUri: Flow<String?> = context.dataStore.data
        .map { it[KEY_DEBUG_LOG_FILE_URI] }

    val driveDebugEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DRIVE_DEBUG_ENABLED] ?: false }

    val verboseDebugLogEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_VERBOSE_DEBUG_LOG_ENABLED] ?: false }

    val stepRepairVersion: Flow<Int> = context.dataStore.data
        .map { it[KEY_STEP_REPAIR_VERSION] ?: 0 }

    suspend fun setIntervalSec(value: Int) {
        context.dataStore.edit { it[KEY_INTERVAL_SEC] = value }
    }

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

    suspend fun setPressureWidgetLastRenderMs(value: Long) {
        context.dataStore.edit { it[KEY_PRESSURE_WIDGET_LAST_RENDER_MS] = value }
    }

    suspend fun setMapWidgetLastRenderMs(value: Long) {
        context.dataStore.edit { it[KEY_MAP_WIDGET_LAST_RENDER_MS] = value }
    }

    suspend fun setWidgetTransparency(value: Int) {
        context.dataStore.edit { it[KEY_WIDGET_TRANSPARENCY] = value }
    }

    suspend fun setSensorOnlyIntervalSec(value: Int) {
        context.dataStore.edit { it[KEY_SENSOR_ONLY_INTERVAL_SEC] = value }
    }

    suspend fun setImportFileUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(KEY_IMPORT_FILE_URI)
            else it[KEY_IMPORT_FILE_URI] = value
        }
    }

    suspend fun setMotionImportFileUri(value: String?) {
        context.dataStore.edit {
            if (value == null) it.remove(KEY_MOTION_IMPORT_FILE_URI)
            else it[KEY_MOTION_IMPORT_FILE_URI] = value
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

    suspend fun setStepRepairVersion(value: Int) {
        context.dataStore.edit { it[KEY_STEP_REPAIR_VERSION] = value }
    }
}
