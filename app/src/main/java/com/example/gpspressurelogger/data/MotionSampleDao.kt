package com.example.gpspressurelogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MotionSampleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(entry: MotionSample): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(entries: List<MotionSample>)

    @Query("SELECT * FROM motion_samples WHERE timestamp = :ts LIMIT 1")
    suspend fun findByTimestamp(ts: Long): MotionSample?

    @Query("SELECT * FROM motion_samples WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun getSince(since: Long): Flow<List<MotionSample>>

    @Query("SELECT * FROM motion_samples WHERE timestamp >= :fromTs AND timestamp <= :toTs ORDER BY timestamp DESC")
    fun getBetween(fromTs: Long, toTs: Long): Flow<List<MotionSample>>

    @Query("SELECT * FROM motion_samples ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): MotionSample?
}
