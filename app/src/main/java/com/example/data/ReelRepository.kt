package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * ReelRepository — the single backend brain of ReelGuard.
 *
 * Responsibilities:
 *  • scroll accounting (per-app daily counters, limits, exceeded state)
 *  • session lifecycle (start / stop / persist session logs)
 *  • cooldown state machine (persisted so it survives process death)
 *  • streak computation (calendar-aware: broken by gaps AND breaches)
 *  • CSV export & data reset
 *
 * Design notes vs. the previous revision:
 *  • Streak calculation is now date-continuity aware — a missing day
 *    no longer silently extends the streak.
 *  • Raising an app limit re-computes today's `exceeded` flag, so a
 *    user who bumps their allowance is immediately unblocked.
 *  • Cooldown helpers (isInCooldown / startCooldown / remaining) live
 *    here instead of being re-implemented inline by every service.
 *  • The todayCounts stream re-keys exactly at local midnight instead
 *    of polling every 60 seconds.
 */
class ReelRepository(private val context: Context, private val database: ReelDatabase) {

    private val sharedPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dao = database.reelDao()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /* ── Live session state ── */
    val activeApp = MutableStateFlow<String?>(null)
    val sessionCount = MutableStateFlow(0)
    val isTrackingActive = MutableStateFlow(false)

    /* ── Persisted configuration ── */
    val cooldownEndTime = MutableStateFlow(sharedPrefs.getLong(KEY_COOLDOWN_END, 0L))
    val isServiceEnabled = MutableStateFlow(sharedPrefs.getBoolean(KEY_SERVICE_ENABLED, true))
    val biometricOverrideEnabled = MutableStateFlow(sharedPrefs.getBoolean(KEY_BIOMETRIC, true))
    val cooldownDurationMinutes = MutableStateFlow(sharedPrefs.getInt(KEY_COOLDOWN_MIN, 10))
    val globalLimit = MutableStateFlow(sharedPrefs.getInt(KEY_GLOBAL_LIMIT, 50))

    /* ── Tracked platforms ── */
    data class AppInfo(val packageName: String, val appName: String)

    val targetApps = listOf(
        AppInfo("com.instagram.android", "Instagram"),
        AppInfo("com.facebook.katana", "Facebook"),
        AppInfo("com.zhiliaoapp.musically", "TikTok"),
        AppInfo("com.google.android.youtube", "YouTube"),
        AppInfo("com.snapchat.android", "Snapchat")
    )

    init {
        // Seed defaults exactly once (single batched commit).
        val editor = sharedPrefs.edit()
        var dirty = false
        if (!sharedPrefs.contains(KEY_SERVICE_ENABLED)) {
            editor.putBoolean(KEY_SERVICE_ENABLED, true); dirty = true
        }
        targetApps.forEach { app ->
            if (!sharedPrefs.contains(limitKey(app.packageName))) {
                editor.putInt(limitKey(app.packageName), DEFAULT_APP_LIMIT); dirty = true
            }
            if (!sharedPrefs.contains(enabledKey(app.packageName))) {
                editor.putBoolean(enabledKey(app.packageName), true); dirty = true
            }
        }
        if (dirty) editor.apply()

        // Drop stale cooldowns left over from a previous process.
        if (cooldownEndTime.value != 0L && cooldownEndTime.value < System.currentTimeMillis()) {
            setCooldownEnd(0L)
        }
    }

    /* ─────────────── Per-app preferences ─────────────── */

    fun isAppEnabled(packageName: String): Boolean =
        sharedPrefs.getBoolean(enabledKey(packageName), true)

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        sharedPrefs.edit().putBoolean(enabledKey(packageName), enabled).apply()
    }

    fun getAppLimit(packageName: String): Int =
        sharedPrefs.getInt(limitKey(packageName), DEFAULT_APP_LIMIT)

    /**
     * Update an app's daily allowance. Today's row is patched and the
     * `exceeded` flag re-evaluated against the new limit, so raising
     * the allowance immediately unblocks the user.
     */
    fun setAppLimit(packageName: String, limit: Int) {
        sharedPrefs.edit().putInt(limitKey(packageName), limit).apply()
        ioScope.launch {
            val today = getTodayDateString()
            val existing = dao.getDailyCounts(today).find { it.appPackageName == packageName }
            if (existing != null) {
                dao.insertDailyCount(
                    existing.copy(limitCount = limit, exceeded = existing.reelCount >= limit)
                )
            }
        }
    }

    /* ─────────────── Global settings ─────────────── */

    fun setServiceEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
        isServiceEnabled.value = enabled
    }

    fun setBiometricOverrideEnabled(enabled: Boolean) {
        sharedPrefs.edit().putBoolean(KEY_BIOMETRIC, enabled).apply()
        biometricOverrideEnabled.value = enabled
    }

    fun setCooldownDuration(minutes: Int) {
        sharedPrefs.edit().putInt(KEY_COOLDOWN_MIN, minutes).apply()
        cooldownDurationMinutes.value = minutes
    }

    fun setGlobalLimit(limit: Int) {
        sharedPrefs.edit().putInt(KEY_GLOBAL_LIMIT, limit).apply()
        globalLimit.value = limit
    }

    /* ─────────────── Cooldown state machine ─────────────── */

    fun setCooldownEnd(timestamp: Long) {
        sharedPrefs.edit().putLong(KEY_COOLDOWN_END, timestamp).apply()
        cooldownEndTime.value = timestamp
    }

    /** Start a cooldown using the configured duration. Returns its end timestamp. */
    fun startCooldown(): Long {
        val end = System.currentTimeMillis() + cooldownDurationMinutes.value * 60_000L
        setCooldownEnd(end)
        return end
    }

    fun isInCooldown(): Boolean = System.currentTimeMillis() < cooldownEndTime.value

    fun cooldownRemainingMs(): Long =
        (cooldownEndTime.value - System.currentTimeMillis()).coerceAtLeast(0L)

    /* ─────────────── Date helpers ─────────────── */

    fun getTodayDateString(): String =
        SimpleDateFormat(DATE_FORMAT, Locale.getDefault()).format(Date())

    private fun millisUntilMidnight(): Long {
        val now = Calendar.getInstance()
        val midnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 1)
            set(Calendar.MILLISECOND, 0)
        }
        return (midnight.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
    }

    /* ─────────────── Reactive streams ─────────────── */

    /** Today's counters — automatically re-keys at local midnight. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todayCounts: Flow<List<DailyCountEntity>> = flow {
        while (true) {
            emit(getTodayDateString())
            delay(millisUntilMidnight())
        }
    }.flatMapLatest { today -> dao.getDailyCountsFlow(today) }

    val allHistoricalCounts: Flow<List<DailyCountEntity>> = dao.getAllDailyCountsFlow()
    val allSessions: Flow<List<SessionLogEntity>> = dao.getAllSessionsFlow()
    val allOverrides: Flow<List<OverrideLogEntity>> = dao.getAllOverridesFlow()

    /* ─────────────── Core tracking ─────────────── */

    /**
     * Register one detected scroll for [packageName].
     * @return true when this exact scroll crossed the daily limit
     *         (i.e. enforcement should fire right now).
     */
    suspend fun registerScroll(packageName: String): Boolean {
        if (!isServiceEnabled.value) return false
        if (!isAppEnabled(packageName)) return false
        if (isInCooldown()) return false // counters frozen during cooldown

        val today = getTodayDateString()
        var crossedLimit = false

        withContext(Dispatchers.IO) {
            val existing = dao.getDailyCounts(today).find { it.appPackageName == packageName }
            val limit = getAppLimit(packageName)
            val newCount = (existing?.reelCount ?: 0) + 1
            val wasExceeded = existing?.exceeded ?: false
            val isExceeded = newCount >= limit

            crossedLimit = isExceeded && !wasExceeded

            dao.insertDailyCount(
                DailyCountEntity(
                    date = today,
                    appPackageName = packageName,
                    reelCount = newCount,
                    limitCount = limit,
                    exceeded = isExceeded
                )
            )
            sessionCount.update { it + 1 }
        }
        return crossedLimit
    }

    /** True when [packageName] is over its allowance today. */
    suspend fun isAppExceededToday(packageName: String): Boolean = withContext(Dispatchers.IO) {
        dao.getDailyCounts(getTodayDateString())
            .find { it.appPackageName == packageName }?.exceeded == true
    }

    /* ─────────────── Session lifecycle ─────────────── */

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
        if (!isTrackingActive.value) return

        val endTime = System.currentTimeMillis()
        val finalCount = sessionCount.value
        val appsJoined = sessionApps.joinToString(",")
        val startedAt = sessionStartTime

        if (finalCount > 0) {
            ioScope.launch {
                dao.insertSessionLog(
                    SessionLogEntity(
                        sessionStartTime = startedAt,
                        sessionEndTime = endTime,
                        totalReels = finalCount,
                        appsUsed = appsJoined
                    )
                )
            }
        }
        isTrackingActive.value = false
        activeApp.value = null
        sessionApps.clear()
    }

    /* ─────────────── Emergency override ─────────────── */

    /**
     * Grants +[OVERRIDE_BONUS] scrolls for the app, clears the exceeded
     * flag, ends the active cooldown, and records an audit-trail entry.
     */
    suspend fun emergencyOverride(packageName: String, reason: String) {
        withContext(Dispatchers.IO) {
            val today = getTodayDateString()
            val existing = dao.getDailyCounts(today).find { it.appPackageName == packageName }

            if (existing != null) {
                val newLimit = existing.limitCount + OVERRIDE_BONUS
                sharedPrefs.edit().putInt(limitKey(packageName), newLimit).apply()
                dao.insertDailyCount(existing.copy(limitCount = newLimit, exceeded = false))
            }

            setCooldownEnd(0L)

            dao.insertOverrideLog(
                OverrideLogEntity(
                    timestamp = System.currentTimeMillis(),
                    appName = packageName,
                    reason = reason
                )
            )
        }
    }

    /* ─────────────── Streak engine ─────────────── */

    val streakFlow: Flow<Int> = allHistoricalCounts.map { calculateStreak(it) }

    /**
     * Days-under-limit streak, calendar-continuity aware:
     *  • Walks backwards day by day starting from today.
     *  • Today with no activity yet doesn't break the streak (pending),
     *    but a *past* day with no records ends the run.
     *  • Any breached record on a day ends the run.
     */
    internal fun calculateStreak(history: List<DailyCountEntity>): Int {
        if (history.isEmpty()) return 0

        val byDate = history.groupBy { it.date }
        val sdf = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
        val cursor = Calendar.getInstance()
        var streak = 0
        var isToday = true

        while (true) {
            val dateStr = sdf.format(cursor.time)
            val records = byDate[dateStr]

            when {
                records == null -> if (!isToday) return streak
                records.any { it.exceeded } -> return streak
                else -> streak++
            }

            isToday = false
            cursor.add(Calendar.DAY_OF_YEAR, -1)

            // Sanity bound so a corrupt clock can't loop forever.
            if (streak > 3650) return streak
        }
    }

    /* ─────────────── CSV export ─────────────── */

    suspend fun generateCSVExport(): File = withContext(Dispatchers.IO) {
        val folder = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(folder, "ReelGuard_Report_$stamp.csv")
        val tf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        FileWriter(file).use { writer ->
            writer.append("ReelGuard — Digital Wellbeing Export\n")
            writer.append("Generated,${tf.format(Date())}\n\n")

            writer.append("DAILY USAGE\n")
            writer.append("Date,App Package,Scroll Count,Limit,Exceeded\n")
            dao.getAllDailyCounts().forEach { d ->
                writer.append("${d.date},${d.appPackageName},${d.reelCount},${d.limitCount},${d.exceeded}\n")
            }

            writer.append("\nSESSIONS\n")
            writer.append("ID,Start,End,Total Scrolls,Apps Used\n")
            dao.getAllSessions().forEach { s ->
                writer.append("${s.id},${tf.format(Date(s.sessionStartTime))},${tf.format(Date(s.sessionEndTime))},${s.totalReels},\"${s.appsUsed}\"\n")
            }

            writer.append("\nOVERRIDES\n")
            writer.append("ID,Timestamp,App,Reason\n")
            dao.getAllOverrides().forEach { o ->
                writer.append("${o.id},${tf.format(Date(o.timestamp))},${o.appName},\"${o.reason.replace("\"", "'")}\"\n")
            }
        }
        file
    }

    /* ─────────────── Reset ─────────────── */

    suspend fun clearAllData() {
        withContext(Dispatchers.IO) {
            dao.clearDailyCounts()
            dao.clearSessionLogs()
            dao.clearOverrideLogs()
            sharedPrefs.edit().remove(KEY_COOLDOWN_END).apply()
            cooldownEndTime.value = 0L
            sessionCount.value = 0
        }
    }

    companion object {
        const val PREFS_NAME = "reelguard_prefs"
        const val DATE_FORMAT = "yyyy-MM-dd"
        const val DEFAULT_APP_LIMIT = 25
        const val OVERRIDE_BONUS = 25

        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_BIOMETRIC = "biometric_override"
        private const val KEY_COOLDOWN_MIN = "cooldown_duration"
        private const val KEY_COOLDOWN_END = "cooldown_end"
        private const val KEY_GLOBAL_LIMIT = "global_limit"

        private fun limitKey(pkg: String) = "limit_$pkg"
        private fun enabledKey(pkg: String) = "enabled_$pkg"
    }
}
