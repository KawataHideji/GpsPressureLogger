package com.example.gpspressurelogger.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPOutputStream

/**
 * 解析用の生センサーデータを per-sensor の gzip CSV へ追記する。
 *
 * 設計のキモ:
 *  - センサー hot path から呼ばれるので、`motionScope.launch` を一切使わない。
 *    各センサー用の小さな synchronized ブロックで直接書き込む。
 *  - 1 日 (3:00 - 翌3:00 の論理日) 単位でファイルを切り替える。書き込み時に
 *    現在の論理日と writer 保有分が一致しなければ自動で旧 writer を閉じ、
 *    新規 writer を開きつつ「2 日以上前」のファイルを削除する（イベントドリブン）。
 *  - gzip 圧縮しながら append する。BATCH_FLUSH 件ごとに flush し、
 *    クラッシュ時のロスを上限化する。
 *  - 設定 OFF のときは [enabledFlag] が false を返し、append は即座に return する。
 */
class RawSensorWriter(
    private val baseDir: File,
    private val enabledFlag: () -> Boolean
) {

    enum class SensorKind(val filePrefix: String, val header: String) {
        LINEAR_ACCEL("raw_accel", "Timestamp,Source,X,Y,Z"),
        ROTATION("raw_rotation", "Timestamp,V0,V1,V2,V3,V4"),
        MAGNETIC("raw_magnetic", "Timestamp,X,Y,Z"),
        STEP("raw_step", "Timestamp,Source,Total"),
        GPS("raw_gps", "Timestamp,Lat,Lon,Alt,Accuracy,Provider,Bearing,Speed,Source"),
        PRESSURE("raw_pressure", "Timestamp,Pressure")
    }

    private data class WriterSlot(
        val dayStartMs: Long,
        val writer: Writer,
        var pendingFlushCount: Int
    )

    private val slots = EnumMap<SensorKind, WriterSlot?>(SensorKind::class.java)
    private val locks = EnumMap<SensorKind, Any>(SensorKind::class.java).apply {
        SensorKind.values().forEach { put(it, Any()) }
    }
    private val droppedCounts = EnumMap<SensorKind, AtomicLong>(SensorKind::class.java).apply {
        SensorKind.values().forEach { put(it, AtomicLong(0)) }
    }

    /** Drop された行数（IO 失敗 / 設定 OFF 等）を読み出してリセットする。debug log 出力用。 */
    fun snapshotAndResetDroppedCounts(): Map<SensorKind, Long> =
        SensorKind.values().associateWith { droppedCounts[it]!!.getAndSet(0) }

    fun appendLinearAccel(ts: Long, source: String, x: Float, y: Float, z: Float) {
        if (!enabledFlag()) return
        appendLine(SensorKind.LINEAR_ACCEL, ts, "$ts,$source,${csvFloat(x)},${csvFloat(y)},${csvFloat(z)}")
    }

    fun appendRotation(ts: Long, values: FloatArray) {
        if (!enabledFlag()) return
        val v0 = values.getOrNull(0)
        val v1 = values.getOrNull(1)
        val v2 = values.getOrNull(2)
        val v3 = values.getOrNull(3)
        val v4 = values.getOrNull(4)
        appendLine(
            SensorKind.ROTATION,
            ts,
            "$ts,${csvFloat(v0)},${csvFloat(v1)},${csvFloat(v2)},${csvFloat(v3)},${csvFloat(v4)}"
        )
    }

    fun appendMagnetic(ts: Long, x: Float, y: Float, z: Float) {
        if (!enabledFlag()) return
        appendLine(SensorKind.MAGNETIC, ts, "$ts,${csvFloat(x)},${csvFloat(y)},${csvFloat(z)}")
    }

    fun appendStepDetector(ts: Long) {
        if (!enabledFlag()) return
        appendLine(SensorKind.STEP, ts, "$ts,DETECTOR,")
    }

    fun appendStepCounter(ts: Long, total: Int) {
        if (!enabledFlag()) return
        appendLine(SensorKind.STEP, ts, "$ts,COUNTER,$total")
    }

    fun appendGps(
        ts: Long,
        lat: Double,
        lon: Double,
        alt: Double?,
        accuracy: Float?,
        provider: String?,
        bearing: Float?,
        speed: Float?,
        source: String
    ) {
        if (!enabledFlag()) return
        appendLine(
            SensorKind.GPS,
            ts,
            "$ts,${csvDouble(lat)},${csvDouble(lon)},${csvDouble(alt)}," +
                "${csvFloat(accuracy)},${csvString(provider)}," +
                "${csvFloat(bearing)},${csvFloat(speed)},$source"
        )
    }

    fun appendPressure(ts: Long, pressureHpa: Float) {
        if (!enabledFlag()) return
        appendLine(SensorKind.PRESSURE, ts, "$ts,${csvFloat(pressureHpa)}")
    }

    /** 全 writer を flush する（service の定期 flush から呼ぶ）。 */
    fun flushAll() {
        SensorKind.values().forEach { kind ->
            synchronized(locks[kind]!!) {
                val slot = slots[kind] ?: return@forEach
                runCatching { slot.writer.flush() }
                slot.pendingFlushCount = 0
            }
        }
    }

    /** 全 writer を閉じる（service onDestroy 等から呼ぶ）。 */
    fun closeAll() {
        SensorKind.values().forEach { kind ->
            synchronized(locks[kind]!!) {
                val slot = slots[kind] ?: return@forEach
                runCatching { slot.writer.flush() }
                runCatching { slot.writer.close() }
                slots[kind] = null
            }
        }
    }

    private fun appendLine(kind: SensorKind, ts: Long, line: String) {
        synchronized(locks[kind]!!) {
            val writer = ensureWriterLocked(kind, ts) ?: run {
                droppedCounts[kind]!!.incrementAndGet()
                return
            }
            try {
                writer.write(line)
                writer.write("\n")
                val slot = slots[kind]
                if (slot != null) {
                    slot.pendingFlushCount += 1
                    if (slot.pendingFlushCount >= BATCH_FLUSH_THRESHOLD) {
                        runCatching { writer.flush() }
                        slot.pendingFlushCount = 0
                    }
                }
            } catch (_: Throwable) {
                droppedCounts[kind]!!.incrementAndGet()
                // 書き込み失敗時は writer を捨てる。次回 append で再オープンする。
                runCatching { writer.close() }
                slots[kind] = null
            }
        }
    }

    private fun ensureWriterLocked(kind: SensorKind, ts: Long): Writer? {
        val dayStart = GpsUtil.getLoggingStart(ts)
        val current = slots[kind]
        if (current != null && current.dayStartMs == dayStart) return current.writer

        // 旧 writer を閉じる。
        if (current != null) {
            runCatching { current.writer.flush() }
            runCatching { current.writer.close() }
            slots[kind] = null
        }

        // 新 writer を開く。
        val file = buildFile(kind, dayStart)
        return try {
            file.parentFile?.mkdirs()
            val isNew = !file.exists() || file.length() == 0L
            val out = BufferedOutputStream(FileOutputStream(file, true))
            // syncFlush=true で flush するたびに復元可能なシンクポイントを作る。
            // クラッシュ時の損失を最小化する。
            val gzip = GZIPOutputStream(out, true)
            val writer = OutputStreamWriter(gzip, Charsets.UTF_8)
            if (isNew) {
                writer.write(kind.header)
                writer.write("\n")
            }
            slots[kind] = WriterSlot(dayStart, writer, 0)
            cleanupOldFilesLocked(kind, dayStart)
            writer
        } catch (_: Throwable) {
            slots[kind] = null
            null
        }
    }

    private fun cleanupOldFilesLocked(kind: SensorKind, currentDayStart: Long) {
        // 現在の論理日と「直前の論理日」の 2 日分を残し、それ以前のファイルを削除する。
        val keepFrom = currentDayStart - GpsUtil.DAY_MS
        val files = baseDir.listFiles { f ->
            f.isFile && f.name.startsWith("${kind.filePrefix}_") && f.name.endsWith(".csv.gz")
        } ?: return
        val df = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
        files.forEach { file ->
            val dateText = file.name
                .removePrefix("${kind.filePrefix}_")
                .removeSuffix(".csv.gz")
            val parsed = runCatching { df.parse(dateText) }.getOrNull() ?: return@forEach
            val cal = Calendar.getInstance().apply {
                time = parsed
                set(Calendar.HOUR_OF_DAY, GpsUtil.LOGGING_RESET_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis < keepFrom) {
                runCatching { file.delete() }
            }
        }
    }

    private fun buildFile(kind: SensorKind, dayStart: Long): File {
        val df = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
        val name = "${kind.filePrefix}_${df.format(Date(dayStart))}.csv.gz"
        return File(baseDir, name)
    }

    /** 現在保持している `.csv.gz` ファイル一覧（直近 2 日分）を返す。エクスポート用。 */
    fun listExportableFiles(): List<File> {
        val files = baseDir.listFiles { f ->
            f.isFile && f.name.endsWith(".csv.gz") &&
                SensorKind.values().any { f.name.startsWith("${it.filePrefix}_") }
        } ?: return emptyList()
        return files.sortedBy { it.name }.toList()
    }

    companion object {
        /** 何件書き込むごとに flush するか。クラッシュ時の損失上限を決める。 */
        private const val BATCH_FLUSH_THRESHOLD = 200

        // ExportUtil から close を呼ぶための弱い参照。LoggingService が onCreate で
        // setActiveInstance し、onDestroy で setActiveInstance(null) する。
        // close 後に sensor 経路が次のサンプルを受けると writer が自動再オープンする。
        @Volatile private var activeInstance: RawSensorWriter? = null

        fun setActiveInstance(writer: RawSensorWriter?) {
            activeInstance = writer
        }

        /**
         * 解析データ ZIP エクスポート直前に呼ぶ。書き込み中の gzip ストリームを
         * 全てクローズして trailer を書き出し、`GZIPInputStream` が正常に読めるようにする。
         * 次回 sensor サンプルが来たときに各 writer は自動的に再オープンする。
         */
        fun sealForExport() {
            activeInstance?.closeAll()
        }

        private fun csvFloat(value: Float?): String = value?.toString() ?: ""
        private fun csvDouble(value: Double?): String = value?.toString() ?: ""
        private fun csvString(value: String?): String =
            if (value == null) "" else value.replace(",", " ").replace("\n", " ")
    }
}
