package com.example.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast

/**
 * Single source of truth for every runtime-permission / special-access check
 * ReelGuard depends on. Previously this logic was copy-pasted across three
 * screens — now it lives here once.
 */
object Permissions {

    fun isAccessibilityEnabled(context: Context, service: Class<out AccessibilityService>): Boolean {
        // Preferred: query the AccessibilityManager directly.
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.forEach { enabled ->
                val info = enabled.resolveInfo?.serviceInfo ?: return@forEach
                if (info.packageName == context.packageName && info.name == service.name) return true
            }
        } catch (_: Exception) { }

        // Fallback: parse the secure settings string.
        return try {
            val expected = "${context.packageName}/${service.name}"
            val expectedAlt = "${context.packageName}/${service.canonicalName ?: service.name}"
            val enabledFlag = try {
                Settings.Secure.getInt(context.applicationContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
            } catch (_: Exception) { 0 }
            if (enabledFlag != 1) return false

            val setting = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(setting)
            splitter.any { it.equals(expected, true) || it.equals(expectedAlt, true) }
        } catch (_: Exception) {
            false
        }
    }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun hasUsageStats(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun isBatteryExempt(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Exception) { false }

    /* ── Intent launchers with graceful fallbacks ── */

    fun openAccessibilitySettings(context: Context) = launchSafely(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun openOverlaySettings(context: Context) = launchSafely(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    )

    fun openUsageStatsSettings(context: Context) = launchSafely(
        context,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun openBatterySettings(context: Context) = launchSafely(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    private fun launchSafely(context: Context, primary: Intent, fallback: Intent) {
        try {
            context.startActivity(primary)
        } catch (_: Exception) {
            try {
                context.startActivity(fallback)
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open settings: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
