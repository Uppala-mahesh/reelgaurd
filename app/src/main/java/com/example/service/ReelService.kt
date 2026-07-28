package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.ReelGuardApplication
import com.example.data.ReelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* Aurora palette constants (services can't reference @Composable theme). */
private val SvcInk = Color(0xFF07070C)
private val SvcSurface = Color(0xFF171726)
private val SvcViolet = Color(0xFFA78BFA)
private val SvcAqua = Color(0xFF22D3EE)
private val SvcRose = Color(0xFFFB7185)
private val SvcTextBright = Color(0xFFF4F4F8)
private val SvcTextMuted = Color(0xFF9B9BB0)
private val SvcTextFaint = Color(0xFF5C5C74)

/**
 * Foreground service that owns the two overlay windows:
 *  • floating counter bubble (draggable, edge-snapping)
 *  • full-screen cooldown lock overlay
 *
 * Fixes vs. previous revision:
 *  • Cooldown overlay resumes remaining time after process restarts
 *    instead of restarting the full duration.
 *  • Biometric-override setting is respected: when disabled the
 *    override happens directly, without launching the prompt.
 *  • Cooldown is started via repository.startCooldown() so the
 *    duration is consistently sourced from settings.
 */
class ReelService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ReelRepository

    private var bubbleView: View? = null
    private var overlayView: View? = null
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var overlayParams: WindowManager.LayoutParams

    private var burnInJob: Job? = null
    private var isBubbleShowing = false
    private var isOverlayShowing = false
    private var toneGenerator: ToneGenerator? = null

    companion object {
        private const val CHANNEL_ID = "reelguard_service_channel"
        private const val NOTIFICATION_ID = 4040

        fun start(context: Context) {
            val intent = Intent(context, ReelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReelService::class.java))
        }
    }

    override fun onCreate() {
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        super.onCreate()
        if (!ReelGuardApplication.isInitialized()) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = ReelGuardApplication.instance.repository
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (_: Exception) { null }

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Guard active"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Guard active"))
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        setupWindowParams()

        // 1. Bubble visibility follows tracking state.
        scope.launch {
            combine(
                repository.isTrackingActive,
                repository.activeApp,
                repository.isServiceEnabled
            ) { active, app, enabled -> Triple(active, app, enabled) }
                .collect { (active, app, enabled) ->
                    if (enabled && active && app != null) showBubble() else hideBubble()
                }
        }

        // 2. Fire enforcement when the active app's counter breaches.
        scope.launch {
            repository.todayCounts.collect { counts ->
                val activePkg = repository.activeApp.value ?: return@collect
                val entry = counts.find { it.appPackageName == activePkg } ?: return@collect
                if (entry.exceeded && !isOverlayShowing && !repository.isInCooldown()) {
                    triggerLimitEnforcement()
                }
            }
        }

        // 3. Resume an interrupted cooldown (process was killed mid-cooldown).
        if (repository.isInCooldown() && !isOverlayShowing) {
            triggerLimitEnforcement(resume = true)
        }

        // 4. Subtle pixel drift to prevent OLED burn-in.
        startBurnInWorker()
    }

    /* ─────────────── Window setup ─────────────── */

    private fun setupWindowParams() {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun startBurnInWorker() {
        burnInJob = scope.launch {
            while (true) {
                delay(5 * 60_000)
                if (isBubbleShowing && bubbleView != null) {
                    bubbleParams.x += (-3..3).random()
                    bubbleParams.y += (-3..3).random()
                    updateBubbleLayout()
                }
            }
        }
    }

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /* ─────────────── Bubble ─────────────── */

    private fun showBubble() {
        if (isBubbleShowing || !canOverlay()) return

        val host = HostComposeLayout(this)
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setContent { FloatingBubbleContent() }
        }
        host.addView(composeView)
        bubbleView = host

        try {
            windowManager.addView(bubbleView, bubbleParams)
            isBubbleShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideBubble() {
        if (!isBubbleShowing) return
        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
                (view as? HostComposeLayout)?.handleDestroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        bubbleView = null
        isBubbleShowing = false
    }

    private fun updateBubbleLayout() {
        bubbleView?.let {
            try {
                windowManager.updateViewLayout(it, bubbleParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /* ─────────────── Cooldown overlay ─────────────── */

    private fun triggerLimitEnforcement(resume: Boolean = false) {
        if (isOverlayShowing || !canOverlay()) return
        isOverlayShowing = true

        if (!resume) {
            scope.launch(Dispatchers.Default) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                vibrateDevice(300L)
            }
        }

        val host = HostComposeLayout(this)
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeSavedStateRegistryOwner(host)
            setViewTreeViewModelStoreOwner(host)
            setContent { CooldownOverlayContent(resume) }
        }
        host.addView(composeView)
        overlayView = host

        try {
            windowManager.addView(overlayView, overlayParams)
            hideBubble()
        } catch (e: Exception) {
            isOverlayShowing = false
            e.printStackTrace()
        }
    }

    private fun removeLimitEnforcementOverlay() {
        if (!isOverlayShowing) return
        isOverlayShowing = false
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                (view as? HostComposeLayout)?.handleDestroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
    }

    @Suppress("DEPRECATION")
    private fun vibrateDevice(timeMs: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(timeMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(timeMs)
        }
    }

    /* ─────────────── Bubble UI ─────────────── */

    @Composable
    private fun FloatingBubbleContent() {
        val sessionCount by repository.sessionCount.collectAsState()
        val activeAppPkg by repository.activeApp.collectAsState()
        val activeAppName = repository.targetApps
            .find { it.packageName == activeAppPkg }?.appName ?: "App"

        var isExpanded by remember { mutableStateOf(false) }

        val infiniteTransition = rememberInfiniteTransition(label = "breathing")
        val breathScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathing_scale"
        )

        Box(
            modifier = Modifier
                .wrapContentSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // Snap to nearest horizontal edge.
                            val screenWidth = resources.displayMetrics.widthPixels
                            val viewWidth = bubbleView?.width ?: 0
                            bubbleParams.x =
                                if (bubbleParams.x + viewWidth / 2 < screenWidth / 2) 12
                                else screenWidth - viewWidth - 12
                            updateBubbleLayout()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            bubbleParams.x = (bubbleParams.x + dragAmount.x).roundToInt()
                            bubbleParams.y = (bubbleParams.y + dragAmount.y).roundToInt()
                            updateBubbleLayout()
                        }
                    )
                }
        ) {
            if (!isExpanded) {
                // Collapsed: violet-ringed obsidian orb with session count.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .scale(breathScale)
                        .clip(CircleShape)
                        .background(SvcInk)
                        .border(
                            width = 1.5.dp,
                            brush = Brush.linearGradient(listOf(SvcViolet, SvcAqua)),
                            shape = CircleShape
                        )
                        .clickable { isExpanded = true }
                ) {
                    Text(
                        text = sessionCount.toString(),
                        color = SvcViolet,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Expanded panel.
                Surface(
                    modifier = Modifier
                        .width(210.dp)
                        .wrapContentHeight()
                        .padding(4.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = SvcSurface,
                    border = BorderStroke(1.dp, SvcViolet.copy(alpha = 0.25f)),
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ReelGuard · $activeAppName",
                            color = SvcTextBright,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Session scrolls", color = SvcTextMuted, fontSize = 11.sp)
                            Text(
                                text = sessionCount.toString(),
                                color = SvcViolet,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Button(
                            onClick = {
                                repository.stopSession()
                                isExpanded = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SvcRose.copy(alpha = 0.14f),
                                contentColor = SvcRose
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause tracking",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause tracking", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isExpanded = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "collapse",
                                color = SvcTextFaint,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    /* ─────────────── Cooldown overlay UI ─────────────── */

    @Composable
    private fun CooldownOverlayContent(resume: Boolean) {
        val activeAppPkg by repository.activeApp.collectAsState()
        val biometricEnabled by repository.biometricOverrideEnabled.collectAsState()

        // On resume use whatever cooldown time is left; otherwise start fresh.
        var timeLeftSeconds by remember {
            mutableStateOf(
                if (resume) (repository.cooldownRemainingMs() / 1000L).toInt().coerceAtLeast(1)
                else repository.cooldownDurationMinutes.value * 60
            )
        }

        LaunchedEffect(Unit) {
            if (!resume) repository.startCooldown()

            while (timeLeftSeconds > 0) {
                delay(1000L)
                timeLeftSeconds--
            }

            repository.setCooldownEnd(0L)
            removeLimitEnforcementOverlay()
            repository.stopSession()
        }

        val glowBrush = remember {
            object : ShaderBrush() {
                override fun createShader(size: androidx.compose.ui.geometry.Size): Shader {
                    return RadialGradientShader(
                        colors = listOf(SvcViolet.copy(alpha = 0.14f), SvcInk),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2.4f),
                        radius = size.width * 0.75f
                    )
                }
            }
        }

        val formattedTime = remember(timeLeftSeconds) {
            String.format("%02d:%02d", timeLeftSeconds / 60, timeLeftSeconds % 60)
        }

        val context = LocalContext.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SvcInk)
                .background(glowBrush),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = "Cooldown",
                    tint = SvcViolet,
                    modifier = Modifier
                        .size(72.dp)
                        .padding(bottom = 12.dp)
                )

                Text(
                    text = "LIMIT REACHED",
                    color = SvcTextFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )

                Text(
                    text = "Time to look up",
                    color = SvcTextBright,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Text(
                    text = "You've spent your scroll budget for this app. The feed unlocks when the cooldown ends.",
                    color = SvcTextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(36.dp))

                Text(
                    text = formattedTime,
                    color = SvcAqua,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.padding(bottom = 36.dp)
                )

                Button(
                    onClick = {
                        val pkg = activeAppPkg ?: ""
                        if (biometricEnabled) {
                            val intent = Intent(context, BiometricOverrideActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                putExtra("package_name", pkg)
                            }
                            context.startActivity(intent)
                        } else {
                            // Biometric disabled → direct override.
                            scope.launch {
                                repository.emergencyOverride(pkg, "Manual override (biometric disabled).")
                                removeLimitEnforcementOverlay()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SvcRose.copy(alpha = 0.14f),
                        contentColor = SvcRose
                    ),
                    border = BorderStroke(1.dp, SvcRose.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text(
                        text = "EMERGENCY OVERRIDE",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (biometricEnabled)
                        "Biometric confirmation required to unlock early"
                    else
                        "Override grants +${ReelRepository.OVERRIDE_BONUS} scrolls and is logged",
                    color = SvcTextFaint,
                    fontSize = 10.sp
                )
            }
        }
    }

    /* ─────────────── Notification ─────────────── */

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReelGuard Guard Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing scroll tracking status"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReelGuard")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /* ─────────────── Lifecycle ─────────────── */

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ReelGuardApplication.isInitialized()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.hasExtra("remove_overlay") == true) {
            scope.launch { removeLimitEnforcementOverlay() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideBubble()
        removeLimitEnforcementOverlay()
        if (::lifecycleRegistry.isInitialized) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        job.cancel()
        toneGenerator?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** FrameLayout host that provides the tree owners Compose needs. */
    class HostComposeLayout(context: Context) : FrameLayout(context),
        LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun handleDestroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
    }
}
