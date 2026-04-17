package com.example.gpspressurelogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(entry: LogEntry): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: LogEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(entries: List<LogEntry>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entries: List<LogEntry>)

    /** タイムスタンプで1件取得（マージ用） */
    @Query("SELECT * FROM log_entries WHERE timestamp = :ts LIMIT 1")
    suspend fun findByTimestamp(ts: Long): LogEntry?

    /** 指定タイムスタンプより古い全レコードを削除 */
    @Query("DELETE FROM log_entries WHERE timestamp < :ts")
    suspend fun deleteEntriesBefore(ts: Long)

    /** 指定タイムスタンプ以降の全レコードをFlowで取得（新しい順） */
    @Query("SELECT * FROM log_entries WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getEntriesSince(since: Long): Flow<List<LogEntry>>

    /** 指定期間のレコードを Flow で取得（新しい順） */
    @Query("SELECT * FROM log_entries WHERE timestamp >= :fromTs AND timestamp <= :toTs ORDER BY timestamp DESC")
    fun getEntriesBetween(fromTs: Long, toTs: Long): Flow<List<LogEntry>>

    /** 最新1件 */
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): LogEntry?

    /** 最新1件を Flow で監視 */
    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC LIMIT 1")
    fun observeLatest(): Flow<LogEntry?>

    /** 最古1件のタイムスタンプ */
    @Query("SELECT MIN(timestamp) FROM log_entries")
    suspend fun getOldestTimestamp(): Long?

    /** 位置付きレコードが指定期間に何件あるか */
    @Query("SELECT COUNT(*) FROM log_entries WHERE timestamp >= :fromTs AND timestamp < :toTs AND latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun countLocationEntriesInRange(fromTs: Long, toTs: Long): Int

    /** 古いデータを削除（保持期間の管理）。削除件数を返す */
    @Query("DELETE FROM log_entries WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** 全件数（Flowで監視可能） */
    @Query("SELECT COUNT(*) FROM log_entries")
    fun count(): Flow<Int>

    /** 指定期間内の件数 */
    @Query("SELECT COUNT(*) FROM log_entries WHERE timestamp >= :fromTs AND timestamp <= :toTs")
    fun countInRange(fromTs: Long, toTs: Long): Flow<Int>
}
