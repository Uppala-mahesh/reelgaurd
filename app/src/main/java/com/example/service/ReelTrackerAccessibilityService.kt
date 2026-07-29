package com.example.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.example.ReelGuardApplication
import com.example.data.ReelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches for scroll events inside short-form video apps and feeds
 * them into [ReelRepository]. Enforcement (kick to home) fires when
 * a limit is crossed or when the user re-enters a locked app during
 * an active cooldown.
 */
class ReelTrackerAccessibilityService : AccessibilityService() {

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

    private fun repositoryOrNull(): ReelRepository? {
        if (cachedRepository == null && ReelGuardApplication.isInitialized()) {
            cachedRepository = ReelGuardApplication.instance.repository
        }
        return cachedRepository
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val repository = repositoryOrNull() ?: return
        val packageName = event.packageName?.toString() ?: return
        val isTargetApp = targetPackages.contains(packageName)

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isTargetApp) {
                    repository.startSession(packageName)
                    ReelService.start(this)

                    // Re-entering a locked app during cooldown → back to home.
                    scope.launch {
                        if (repository.isInCooldown() && repository.isAppExceededToday(packageName)) {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }
                } else {
                    repository.stopSession()
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (!isTargetApp) return

                // Auto-start session if scroll detected without a window event.
                if (!repository.isTrackingActive.value) {
                    repository.startSession(packageName)
                    ReelService.start(this)
                }

                val now = System.currentTimeMillis()
                // 800ms debounce filters layout-jitter double counts.
                if (now - lastScrollTime < SCROLL_DEBOUNCE_MS) return
                lastScrollTime = now

                scope.launch {
                    val crossedLimit = repository.registerScroll(packageName)
                    when {
                        crossedLimit -> {
                            // Limit crossed on this scroll → enforce immediately.
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        repository.isInCooldown() && repository.isAppExceededToday(packageName) -> {
                            // Attempting to scroll a locked app mid-cooldown.
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        repositoryOrNull()?.stopSession()
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
        private const val SCROLL_DEBOUNCE_MS = 800L

        @Volatile
        var isRunning = false
    }
}
