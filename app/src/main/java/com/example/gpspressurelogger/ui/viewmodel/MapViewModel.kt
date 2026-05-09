package com.example.gpspressurelogger.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gpspressurelogger.data.AppDatabase
import com.example.gpspressurelogger.data.LogEntry
import com.example.gpspressurelogger.data.MotionSample
import com.example.gpspressurelogger.util.GpsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
     *
     * 表示対象が「現在の論理日」の間は LIVE_REFRESH_INTERVAL_MS ごとに DB 再読込して
     * 移動中の track が地図に伸びていくようにする。過去の日付は変化しないので one-shot のまま。
     * 過去日付は変化しないので一度 emit したら以後 emit しない。
     */
    val mapUiState: StateFlow<MapUiState> = _targetDateStart
        .flatMapLatest { start -> refreshKeysFor(start) }
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

    /**
     * 表示対象が「現在の論理日」なら LIVE_REFRESH_INTERVAL_MS ごとに start を再 emit して
     * 下流で DB を再読込させる。過去日付なら 1 回だけ emit。
     *
     * 同じ Long 値を繰り返し emit しても下流の flatMapLatest は新しい flow を起動するので、
     * 既存の MapScreen 側 lastRenderSignature チェックでデータ未変化なら再描画はスキップされる。
     */
    private fun refreshKeysFor(start: Long): kotlinx.coroutines.flow.Flow<Long> {
        val isToday = start >= GpsUtil.getLoggingStart(System.currentTimeMillis())
        return if (isToday) {
            flow {
                while (true) {
                    emit(start)
                    delay(LIVE_REFRESH_INTERVAL_MS)
                }
            }
        } else {
            flowOf(start)
        }
    }

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

    companion object {
        // 今日表示中の地図 live 再読込周期。長すぎると track が伸びにくく、短すぎると
        // パン・ズーム中の再描画頻度が上がるため 5 秒に設定。MapScreen 側の interaction
        // ガードで、操作中はこの周期 emit でも再描画をスキップする。
        private const val LIVE_REFRESH_INTERVAL_MS: Long = 5_000L
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
