package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "daily_counts", primaryKeys = ["date", "appPackageName"])
data class DailyCountEntity(
    val date: String,             // Format: "YYYY-MM-DD"
    val appPackageName: String,
    val reelCount: Int,
    val limitCount: Int,
    val exceeded: Boolean
)

@Entity(tableName = "session_logs")
data class SessionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionStartTime: Long,
    val sessionEndTime: Long,
    val totalReels: Int,
    val appsUsed: String          // Comma-separated package names
)

@Entity(tableName = "override_logs")
data class OverrideLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val appName: String,
    val reason: String
)

@Dao
interface ReelDao {
    @Query("SELECT * FROM daily_counts WHERE date = :date")
    fun getDailyCountsFlow(date: String): Flow<List<DailyCountEntity>>

    @Query("SELECT * FROM daily_counts WHERE date = :date")
    suspend fun getDailyCounts(date: String): List<DailyCountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyCount(dailyCount: DailyCountEntity)

    @Query("UPDATE daily_counts SET reelCount = :count, exceeded = :exceeded WHERE date = :date AND appPackageName = :packageName")
    suspend fun updateDailyCount(date: String, packageName: String, count: Int, exceeded: Boolean)

    @Query("UPDATE daily_counts SET limitCount = :limitVal WHERE date = :date AND appPackageName = :packageName")
    suspend fun updateDailyLimit(date: String, packageName: String, limitVal: Int)

    // Session Logs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessionLog(sessionLog: SessionLogEntity)

    @Query("SELECT * FROM session_logs ORDER BY sessionStartTime DESC")
    fun getAllSessionsFlow(): Flow<List<SessionLogEntity>>

    @Query("SELECT * FROM session_logs ORDER BY sessionStartTime DESC")
    suspend fun getAllSessions(): List<SessionLogEntity>

    // Override Logs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverrideLog(overrideLog: OverrideLogEntity)

    @Query("SELECT * FROM override_logs ORDER BY timestamp DESC")
    fun getAllOverridesFlow(): Flow<List<OverrideLogEntity>>

    @Query("SELECT * FROM override_logs ORDER BY timestamp DESC")
    suspend fun getAllOverrides(): List<OverrideLogEntity>

    // Aggregates for Charts & History
    @Query("SELECT * FROM daily_counts ORDER BY date DESC")
    fun getAllDailyCountsFlow(): Flow<List<DailyCountEntity>>

    @Query("SELECT * FROM daily_counts ORDER BY date DESC")
    suspend fun getAllDailyCounts(): List<DailyCountEntity>

    @Query("SELECT * FROM daily_counts WHERE date >= :startDate ORDER BY date ASC")
    fun getCountsSinceFlow(startDate: String): Flow<List<DailyCountEntity>>

    @Query("DELETE FROM daily_counts")
    suspend fun clearDailyCounts()

    @Query("DELETE FROM session_logs")
    suspend fun clearSessionLogs()

    @Query("DELETE FROM override_logs")
    suspend fun clearOverrideLogs()
}

@Database(
    entities = [DailyCountEntity::class, SessionLogEntity::class, OverrideLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ReelDatabase : RoomDatabase() {
    abstract fun reelDao(): ReelDao
}
