package com.example.gpspressurelogger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LogEntry::class, MotionSample::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun logDao(): LogDao
    abstract fun motionSampleDao(): MotionSampleDao

    companion object {
        val MIGRATION_4_6 = object : Migration(4, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS log_entries_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        altitudeGps REAL,
                        pressureRaw REAL,
                        pressureQnh REAL,
                        gpsAccuracy REAL,
                        stepsDelta INTEGER,
                        legacyStepCount INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO log_entries_new (
                        id, timestamp, latitude, longitude, altitudeGps,
                        pressureRaw, pressureQnh, gpsAccuracy, stepsDelta, legacyStepCount
                    )
                    SELECT
                        source.id,
                        source.timestamp,
                        source.latitude,
                        source.longitude,
                        source.altitudeGps,
                        source.pressureRaw,
                        source.pressureQnh,
                        source.gpsAccuracy,
                        CASE
                            WHEN source.stepCount IS NULL THEN NULL
                            WHEN (
                                SELECT prev.stepCount
                                FROM log_entries AS prev
                                WHERE prev.timestamp < source.timestamp
                                ORDER BY prev.timestamp DESC
                                LIMIT 1
                            ) IS NULL THEN 0
                            ELSE MAX(
                                0,
                                source.stepCount - (
                                    SELECT prev.stepCount
                                    FROM log_entries AS prev
                                    WHERE prev.timestamp < source.timestamp
                                    ORDER BY prev.timestamp DESC
                                    LIMIT 1
                                )
                            )
                        END,
                        source.stepCount
                    FROM log_entries AS source
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE log_entries")
                db.execSQL("ALTER TABLE log_entries_new RENAME TO log_entries")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_log_entries_timestamp ON log_entries(timestamp)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE log_entries ADD COLUMN legacyStepCount INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS motion_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        accelStddev3s REAL,
                        accelMad3s REAL,
                        stepDelta3s INTEGER,
                        stepRate3s REAL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_motion_samples_timestamp ON motion_samples(timestamp)"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN kStatus TEXT")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN kRawStatus TEXT")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN kAvg REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN kVariance REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN kConfidence REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN wStatus TEXT")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN stepDeltaWindow INTEGER")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN gpsIntervalMs INTEGER")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN gpsImmediate INTEGER")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN confirmedMode TEXT")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionKind TEXT")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionSpeedKmh REAL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionStartLat REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionStartLon REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionEndLat REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionEndLon REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionStayLat REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionStayLon REAL")
                db.execSQL("ALTER TABLE motion_samples ADD COLUMN constantRegionDirectionDeg REAL")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gps_pressure_logger.db"
                )
                .addMigrations(MIGRATION_4_6, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build().also { INSTANCE = it }
            }
    }
}
