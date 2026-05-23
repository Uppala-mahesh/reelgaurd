package com.example.service

import android.view.accessibility.AccessibilityEvent
import com.example.ReelGuardApplication
import com.example.data.ReelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReelTrackerAccessibilityService : android.accessibilityservice.AccessibilityService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var lastScrollTime = 0L
    private var cachedRepository: ReelRepository? = null

    private val targetPackages = setOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.facebook.katana",
        "com.zhiliaoapp.musically",
        "com.snapchat.android"
    )

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    private fun getRepositorySafe(): ReelRepository? {
        if (cachedRepository == null && ReelGuardApplication.isInitialized()) {
            cachedRepository = ReelGuardApplication.instance.repository
        }
        return cachedRepository
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val repository = getRepositorySafe() ?: return
        val packageName = event.packageName?.toString() ?: return

        val isTargetApp = targetPackages.contains(packageName)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isTargetApp) {
                    // User opened a tracked short-form layout
                    repository.startSession(packageName)
                    // Start Foreground Service to ensure background capabilities
                    ReelService.start(this)
                    
                    // Direct home check if they open the app and it is already exceeded with active cooldown
                    scope.launch {
                        val today = repository.getTodayDateString()
                        val counts = ReelGuardApplication.instance.database.reelDao().getDailyCounts(today)
                        val appCount = counts.find { it.appPackageName == packageName }
                        if (appCount != null && appCount.exceeded) {
                            if (System.currentTimeMillis() < repository.cooldownEndTime.value) {
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            }
                        }
                    }
                } else {
                    // User returned home or navigated away
                    repository.stopSession()
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isTargetApp) return

                // To filter general non-scrolling events inside TYPE_WINDOW_CONTENT_CHANGED:
                val isScrollingContent = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                    event.isScrollable ||
                    (event.className?.toString()?.contains("ViewPager", ignoreCase = true) == true) ||
                    (event.className?.toString()?.contains("RecyclerView", ignoreCase = true) == true)

                if (!isScrollingContent) return

                if (packageName == "com.google.android.youtube") {
                    android.util.Log.d("ReelGuardTracker", "SCROLL DETECTED: YouTube Short")
                } else if (packageName == "com.instagram.android") {
                    android.util.Log.d("ReelGuardTracker", "SCROLL DETECTED: Instagram Reel")
                } else {
                    android.util.Log.d("ReelGuardTracker", "SCROLL DETECTED: $packageName")
                }

                if (repository.isTrackingActive.value) {
                    val currentTime = System.currentTimeMillis()
                    // 1500ms Debounce mechanism to prevent double trigger on frame redraw jitter
                    if (currentTime - lastScrollTime >= 1500) {
                        lastScrollTime = currentTime
                        scope.launch {
                            val limitReached = repository.registerScroll(packageName)
                            if (limitReached) {
                                // Kick to home screen immediately when limit is reached
                                performGlobalAction(GLOBAL_ACTION_HOME)
                            } else {
                                // If they attempt to continue scrolling after limit is reached
                                val today = repository.getTodayDateString()
                                val counts = ReelGuardApplication.instance.database.reelDao().getDailyCounts(today)
                                val appCount = counts.find { it.appPackageName == packageName }
                                if (appCount != null && appCount.exceeded) {
                                    if (System.currentTimeMillis() < repository.cooldownEndTime.value) {
                                        performGlobalAction(GLOBAL_ACTION_HOME)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        // Stop session if service is forcibly killed/disabled
        val repository = getRepositorySafe()
        if (repository != null) {
            repository.stopSession()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        job.cancel()
    }

    companion object {
        @Volatile
        var isRunning = false
    }
}
