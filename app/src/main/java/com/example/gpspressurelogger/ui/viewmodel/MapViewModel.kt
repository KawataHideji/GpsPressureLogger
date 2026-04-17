package com.example.gpspressurelogger.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.util.GpsUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)

    // 表示対象日の開始時刻（直近の3:00が初期値）
    private val _targetDateStart = MutableStateFlow(GpsUtil.getLoggingStart(System.currentTimeMillis()))
    val targetDateStart: StateFlow<Long> = _targetDateStart.asStateFlow()

    private val preparedEntries = _targetDateStart
        .flatMapLatest { start ->
            db.logDao().getEntriesSince(start)
        }
        .map { raw ->
            val start = _targetDateStart.value
            GpsUtil.prepareMapEntries(raw, start)
        }

    val motionSamples: StateFlow<List<MotionSample>> = _targetDateStart
        .flatMapLatest { start ->
            db.motionSampleDao().getBetween(start, start + GpsUtil.DAY_MS - 1)
        }
        .map { samples -> samples.sortedBy { it.timestamp } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * 選択された日の表示用エントリ一覧（3:00 〜 翌2:59）。
     * GPS 異常値除去のあと、viewer で試験した表示専用停止補正を適用する。
     */
    val entries: StateFlow<List<LogEntry>> = combine(preparedEntries, motionSamples) { mapEntries, samples ->
        if (samples.isEmpty()) {
            mapEntries
        } else {
            GpsUtil.normalizeStopsForDisplay(mapEntries, samples)
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
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
