package com.example.gpspressurelogger.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.util.GpsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MapUiState(
    val targetDateStart: Long = GpsUtil.getLoggingStart(System.currentTimeMillis()),
    val entries: List<LogEntry> = emptyList(),
    val motionSamples: List<MotionSample> = emptyList()
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    // 表示対象日の開始時刻（直近の3:00が初期値）
    private val _targetDateStart = MutableStateFlow(GpsUtil.getLoggingStart(System.currentTimeMillis()))

    /**
     * 選択された日の表示用エントリ一覧（3:00 〜 翌2:59）。
     * GPS 異常値除去のあと、viewer で試験した表示専用停止補正を適用する。
     */
    val mapUiState: StateFlow<MapUiState> = _targetDateStart
        .flatMapLatest { start ->
            flow {
                val rawEntries = db.logDao().getEntriesBetweenAscOnce(start, start + GpsUtil.DAY_MS)
                val samples = db.motionSampleDao()
                    .getBetweenOnce(start, start + GpsUtil.DAY_MS)
                    .sortedBy { it.timestamp }
                val preparedEntries = GpsUtil.prepareMapEntries(rawEntries, start)
                val displayEntries = if (samples.isEmpty()) {
                    preparedEntries
                } else {
                    GpsUtil.normalizeStopsForDisplay(preparedEntries, samples)
                }
                emit(
                    MapUiState(
                        targetDateStart = start,
                        entries = displayEntries,
                        motionSamples = samples
                    )
                )
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MapUiState(targetDateStart = _targetDateStart.value)
        )

    fun moveToPrevDay() {
        viewModelScope.launch {
            val oldestTimestamp = db.logDao().getOldestTimestamp() ?: return@launch
            val oldestStart = GpsUtil.getLoggingStart(oldestTimestamp)
            var candidate = _targetDateStart.value - GpsUtil.DAY_MS
            while (candidate >= oldestStart) {
                if (db.logDao().countLocationEntriesInRange(candidate, candidate + GpsUtil.DAY_MS) > 0) {
                    _targetDateStart.value = candidate
                    return@launch
                }
                candidate -= GpsUtil.DAY_MS
            }
        }
    }

    fun moveToNextDay() {
        viewModelScope.launch {
            val latestTimestamp = db.logDao().getLatest()?.timestamp ?: return@launch
            val latestStart = GpsUtil.getLoggingStart(latestTimestamp)
            var candidate = _targetDateStart.value + GpsUtil.DAY_MS
            while (candidate <= latestStart) {
                if (db.logDao().countLocationEntriesInRange(candidate, candidate + GpsUtil.DAY_MS) > 0) {
                    _targetDateStart.value = candidate
                    return@launch
                }
                candidate += GpsUtil.DAY_MS
            }
        }
    }
}
