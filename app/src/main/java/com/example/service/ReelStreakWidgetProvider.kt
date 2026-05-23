package com.example.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.ReelGuardApplication
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReelStreakWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        updateWidgetAsync(context, appWidgetManager, appWidgetIds)
    }

    private fun updateWidgetAsync(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val app = context.applicationContext as? ReelGuardApplication ?: return
        if (!app.isInitializedInternal()) return
        val repository = app.repository

        // Safely launch background coroutine to query streak and prevent blocking on broadcast receiver thread
        CoroutineScope(Dispatchers.IO).launch {
            val streak = try {
                repository.streakFlow.first()
            } catch (e: Exception) {
                0
            }

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_streak_layout)
                
                // Bind visual counters
                views.setTextViewText(R.id.widget_streak_value, streak.toString())
                views.setTextViewText(
                    R.id.widget_streak_label, 
                    if (streak == 1) "DAY STREAK" else "DAYS STREAK"
                )

                // Launch main app upon widget click
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context, 
                    0, 
                    intent, 
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                // Flush updates
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            try {
                val intent = Intent(context, ReelStreakWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                }
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val ids = appWidgetManager.getAppWidgetIds(
                    ComponentName(context, ReelStreakWidgetProvider::class.java)
                )
                if (ids != null && ids.isNotEmpty()) {
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    context.sendBroadcast(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("ReelStreakWidget", "Failed to update widgets", e)
            }
        }
    }
}
