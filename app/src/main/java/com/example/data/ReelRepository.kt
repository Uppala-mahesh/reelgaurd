package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.example.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

import kotlin.OptIn

class ReelRepository(private val context: Context, private val database: ReelDatabase) {
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences("reelguard_prefs", Context.MODE_PRIVATE)
    private val dao = database.reelDao()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    // Current app tracking state (Dynamic)
    val activeApp = MutableStateFlow<String?>(null)
    val sessionCount = MutableStateFlow(0)
    val isTrackingActive = MutableStateFlow(false)

    // Cooldown state
    val cooldownEndTime = MutableStateFlow(sharedPrefs.getLong("cooldown_end", 0L))

    // Real-time tracking configuration & state
    val isServiceEnabled = MutableStateFlow(sharedPrefs.getBoolean("service_enabled", false))
    val biometricOverrideEnabled = MutableStateFlow(sharedPrefs.getBoolean("biometric_override", true))
    val cooldownDurationMinutes = MutableStateFlow(sharedPrefs.getInt("cooldown_duration", 10))
    val globalLimit = MutableStateFlow(sharedPrefs.getInt("global_limit", 50))

    // App packages definition
    val targetApps = listOf(
        AppInfo("com.instagram.android", "Instagram"),
        AppInfo("com.facebook.katana", "Facebook"),
        AppInfo("com.zhiliaoapp.musically", "TikTok"),
        AppInfo("com.google.android.youtube", "YouTube"),
        AppInfo("com.snapchat.android", "Snapchat")
    )

    data class AppInfo(val packageName: String, val appName: String)

    init {
        // Enforce default limits if not set
        targetApps.forEach { app ->
            if (!sharedPrefs.contains("limit_${app.packageName}")) {
                sharedPrefs.edit().putInt("limit_${app.packageName}", 25).apply()
            }
            if (!sharedPrefs.contains("enabled_${app.packageName}")) {
                sharedPrefs.edit().putBoolean("enabled_${app.packageName}", true).apply()
            }
        }
    }

    fun isAppEnabled(packageName: String): Boolean {
        return sharedPrefs.getBoolean("enabled_$packageName", true)
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        sharedPrefs.edit().putBoolean("enabled_$packageName", enabled).apply()
    }

    fun getAppLimit(packageName: String): Int {
        return sharedPrefs.getInt("limit_$packageName", 25)
    }

    fun setAppLimit(packageName: String, limit: Int) {
        sharedPrefs.edit().putInt("limit_$packageName", limit).apply()
        // Save to database daily count limit for today
        ioScope.launch {
            val today = getTodayDateString()
            val existing = dao.getDailyCounts(today).find { it.appPackageName == packageName }
            if (existing != null) {
                dao.updateDailyLimit(today, packageName, limit)
            }
        }
    }

    fun setServiceEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("service_enabled", enabled).apply()
        isServiceEnabled.value = enabled
    }

    fun setBiometricOverrideEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean("biometric_override", enabled).apply()
        biometricOverrideEnabled.value = enabled
    }

    fun setCooldownDuration(minutes: Int) {
        sharedPrefs.edit().putInt("cooldown_duration", minutes).apply()
        cooldownDurationMinutes.value = minutes
    }

    fun setGlobalLimit(limit: Int) {
        sharedPrefs.edit().putInt("global_limit", limit).apply()
        globalLimit.value = limit
    }

    fun setCooldownEnd(timestamp: Long) {
        sharedPrefs.edit().putLong("cooldown_end", timestamp).apply()
        cooldownEndTime.value = timestamp
    }

    fun getTodayDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    // Reactive streams from database
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val todayCounts: Flow<List<DailyCountEntity>> = flow {
        while (true) {
            emit(getTodayDateString())
            kotlinx.coroutines.delay(60000) // check every minute
        }
    }.flatMapLatest { today ->
        dao.getDailyCountsFlow(today)
    }

    val allHistoricalCounts: Flow<List<DailyCountEntity>> = dao.getAllDailyCountsFlow()
    val allSessions: Flow<List<SessionLogEntity>> = dao.getAllSessionsFlow()
    val allOverrides: Flow<List<OverrideLogEntity>> = dao.getAllOverridesFlow()

    // Core Tracking Logic — called from AccessibilityService when scroll is registered
    suspend fun registerScroll(packageName: String): Boolean {
        if (!isServiceEnabled.value) return false
        if (!isAppEnabled(packageName)) return false

        // Check if in cooldown
        if (System.currentTimeMillis() < cooldownEndTime.value) {
            return false // Tracking paused during countdown cooldown period
        }

        val today = getTodayDateString()
        var limitExceededJustNow = false

        withContext(Dispatchers.IO) {
            // Get existing or create new count for today
            val counts = dao.getDailyCounts(today)
            val existing = counts.find { it.appPackageName == packageName }
            val limitValue = getAppLimit(packageName)

            val currentCount = (existing?.reelCount ?: 0) + 1
            val previouslyExceeded = existing?.exceeded ?: false
            val isExceeded = currentCount >= limitValue

            if (isExceeded && !previouslyExceeded) {
                limitExceededJustNow = true
            }

            // Save to database
            val entity = DailyCountEntity(
                date = today,
                appPackageName = packageName,
                reelCount = currentCount,
                limitCount = limitValue,
                exceeded = isExceeded
            )
            dao.insertDailyCount(entity)

            // Dynamic tracking state update
            sessionCount.update { it + 1 }
        }

        return limitExceededJustNow
    }

    // Track active session lifespan
    private var sessionStartTime: Long = 0L
    private val sessionApps = mutableSetOf<String>()

    fun startSession(packageName: String) {
        if (!isTrackingActive.value) {
            sessionStartTime = System.currentTimeMillis()
            sessionCount.value = 0
            isTrackingActive.value = true
        }
        activeApp.value = packageName
        sessionApps.add(packageName)
    }

    fun stopSession() {
        if (isTrackingActive.value) {
            val endTime = System.currentTimeMillis()
            val finalCount = sessionCount.value
            val appsJoined = sessionApps.joinToString(",")

            if (finalCount > 0) {
                ioScope.launch {
                    val log = SessionLogEntity(
                        sessionStartTime = sessionStartTime,
                        sessionEndTime = endTime,
                        totalReels = finalCount,
                        appsUsed = appsJoined
                    )
                    dao.insertSessionLog(log)
                }
            }
            isTrackingActive.value = false
            activeApp.value = null
            sessionApps.clear()
        }
    }

    suspend fun emergencyOverride(packageName: String, reason: String) {
        withContext(Dispatchers.IO) {
            // Reset daily count slightly or extend daily limit to keep browsing
            val today = getTodayDateString()
            val counts = dao.getDailyCounts(today)
            val existing = counts.find { it.appPackageName == packageName }

            if (existing != null) {
                // Raise limit by 25 and set exceeded false to resume scrolling
                val newLimit = existing.limitCount + 25
                // Save new limit to prefs too for consistency
                setAppLimit(packageName, newLimit)

                val entity = existing.copy(limitCount = newLimit, exceeded = false)
                dao.insertDailyCount(entity)
            }

            // Log override event
            val log = OverrideLogEntity(
                timestamp = System.currentTimeMillis(),
                appName = packageName,
                reason = reason
            )
            dao.insertOverrideLog(log)
        }
    }

    // Core Streak Counter Calculation
    val streakFlow: Flow<Int> = allHistoricalCounts.map { allHistory ->
        calculateStreak(allHistory)
    }

    private fun calculateStreak(history: List<DailyCountEntity>): Int {
        if (history.isEmpty()) return 0

        // Group by date
        val grouped = history.groupBy { it.date }
        // Sort dates descending
        val sortedDates = grouped.keys.sortedDescending()

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = getTodayDateString()

        // Verify if we have entries. If no activities yesterday or today, streak might be broken.
        var streak = 0
        var checkCalendar = Calendar.getInstance()

        // Check streak starting from today or yesterday
        for (dateStr in sortedDates) {
            val records = grouped[dateStr] ?: continue
            val allUnderLimit = records.none { it.exceeded }

            if (allUnderLimit) {
                streak++
            } else {
                // If exceeded, streak breaks!
                break
            }
        }
        return streak
    }

    // CSV Exporter to private or external directory and return full path or file
    suspend fun generateCSVExport(): File = withContext(Dispatchers.IO) {
        val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(folder, "ReelGuard_Report_${System.currentTimeMillis()}.csv")
        val writer = FileWriter(file)

        // Title
        writer.append("ReelGuard — Digital Wellbeing Export Report\n")
        writer.append("Generated at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")

        // 1. Daily usage
        writer.append("DAILY USAGE DATA\n")
        writer.append("Date,App Package,Reels Clean Count,Caps Limit,Limit Exceeded\n")
        val dailies = dao.getAllDailyCounts()
        dailies.forEach { d ->
            writer.append("${d.date},${d.appPackageName},${d.reelCount},${d.limitCount},${d.exceeded}\n")
        }

        writer.append("\nSESSION SUMMARIES\n")
        writer.append("Session ID,Start Time,End Time,Total Reels Scrolling,Apps Used\n")
        val sessions = dao.getAllSessions()
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sessions.forEach { s ->
            writer.append("${s.id},${tf.format(Date(s.sessionStartTime))},${tf.format(Date(s.sessionEndTime))},${s.totalReels},${s.appsUsed}\n")
        }

        writer.append("\nOVERRIDE ACTIONS DATA\n")
        writer.append("Override ID,Timestamp,App Name,Justification Reason\n")
        val overrides = dao.getAllOverrides()
        overrides.forEach { o ->
            writer.append("${o.id},${tf.format(Date(o.timestamp))},${o.appName},${o.reason.replace(",", " ")}\n")
        }

        writer.flush()
        writer.close()
        file
    }
}
