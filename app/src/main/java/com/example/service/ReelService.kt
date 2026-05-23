package com.example.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.ReelGuardApplication
import com.example.data.DailyCountEntity
import com.example.data.ReelRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

class ReelService : Service(), LifecycleOwner {

    private lateinit var lifecycleRegistry: LifecycleRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private lateinit var windowManager: WindowManager
    private lateinit var repository: ReelRepository

    // Window Layout references
    private var bubbleView: View? = null
    private var overlayView: View? = null

    // Layout Params
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var overlayParams: WindowManager.LayoutParams

    // Burn-in pixel protection offsets
    private var burnInXOffset = 0
    private var burnInYOffset = 0
    private var burnInJob: Job? = null

    // Track state of overlays
    private var isBubbleShowing = false
    private var isOverlayShowing = false

    // Sound alert generator
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
        // Simple custom lifecycle implementation to host Compose views cleanly
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        super.onCreate()
        if (!ReelGuardApplication.isInitialized()) {
            stopSelf()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repository = ReelGuardApplication.instance.repository
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Service Active"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Service Active"))
        }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // Initialize WindowParams
        setupWindowParams()

        // 1. Observe active tracking states
        scope.launch {
            combine(
                repository.isTrackingActive,
                repository.activeApp,
                repository.isServiceEnabled
            ) { active, app, enabled ->
                Triple(active, app, enabled)
            }.collect { (active, app, enabled) ->
                if (enabled && active && app != null) {
                    showBubble()
                } else {
                    hideBubble()
                }
            }
        }

        // 2. Observe limit status of currently active app daily counts
        scope.launch {
            repository.todayCounts.collect { counts ->
                val activeAppPkg = repository.activeApp.value
                if (activeAppPkg != null) {
                    val activeCount = counts.find { it.appPackageName == activeAppPkg }
                    if (activeCount != null && activeCount.exceeded) {
                        // Check if we are already in overlay mode
                        if (!isOverlayShowing && !isInCooldown()) {
                            triggerLimitEnforcement()
                        }
                    }
                }
            }
        }

        // 3. Keep updating the bubble position subtly to prevent burn-in
        startBurnInExemptionWorker()
    }

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
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun startBurnInExemptionWorker() {
        burnInJob = scope.launch {
            while (true) {
                delay(5 * 60000) // 5 minutes
                // Shift by 2-3 pixels randomly
                burnInXOffset = (-3..3).random()
                burnInYOffset = (-3..3).random()
                if (isBubbleShowing && bubbleView != null) {
                    bubbleParams.x += burnInXOffset
                    bubbleParams.y += burnInYOffset
                    updateBubbleLayout()
                }
            }
        }
    }

    private fun isInCooldown(): Boolean {
        return System.currentTimeMillis() < repository.cooldownEndTime.value
    }

    // Displays the floating bubble
    private fun showBubble() {
        if (isBubbleShowing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        val frameLayout = HostComposeLayout(this)
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(frameLayout)
            setViewTreeSavedStateRegistryOwner(frameLayout)
            setViewTreeViewModelStoreOwner(frameLayout)
            setContent {
                FloatingBubbleContent()
            }
        }
        frameLayout.addView(composeView)
        bubbleView = frameLayout

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
                if (view is HostComposeLayout) {
                    view.handleDestroy()
                }
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

    // Displays full-screen limit overlay
    private fun triggerLimitEnforcement() {
        if (isOverlayShowing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }
        isOverlayShowing = true

        // Play standard glass tap tone + sharp vibration
        scope.launch(Dispatchers.Default) {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            vibrateDevice(300L)
        }

        val frameLayout = HostComposeLayout(this)
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(frameLayout)
            setViewTreeSavedStateRegistryOwner(frameLayout)
            setViewTreeViewModelStoreOwner(frameLayout)
            setContent {
                LimitOverlayContent()
            }
        }
        frameLayout.addView(composeView)
        overlayView = frameLayout

        try {
            windowManager.addView(overlayView, overlayParams)
            // Hide bubble while full-screen warning is on
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
                if (view is HostComposeLayout) {
                    view.handleDestroy()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        overlayView = null
    }

    @Suppress("DEPRECATION")
    private fun vibrateDevice(timeMs: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                it.vibrate(VibrationEffect.createOneShot(timeMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                it.vibrate(timeMs)
            }
        }
    }

    // Floating Bubble Visual Layout
    @Composable
    fun FloatingBubbleContent() {
        val sessionCount by repository.sessionCount.collectAsState()
        val activeAppPkg by repository.activeApp.collectAsState()
        val activeAppName = repository.targetApps.find { it.packageName == activeAppPkg }?.appName ?: "App"

        var isExpanded by remember { mutableStateOf(false) }

        // Breathing scale transition
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

        val density = LocalDensity.current

        Box(
            modifier = Modifier
                .wrapContentSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            // Snap-to-edge calculation
                            val displayMetrics = resources.displayMetrics
                            val screenWidth = displayMetrics.widthPixels
                            val middle = screenWidth / 2

                            // Nearest edge snapping
                            bubbleParams.x = if (bubbleParams.x + bubbleView!!.width / 2 < middle) {
                                12 // margin
                            } else {
                                screenWidth - bubbleView!!.width - 12
                            }
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.wrapContentSize()
            ) {
                if (!isExpanded) {
                    // Mini Mode bubble: 56dp pure black with custom aqua glow
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .scale(breathScale)
                            .clip(CircleShape)
                            .background(Color(0xFF000000))
                            .border(
                                width = 1.5.dp,
                                color = Color(0xFF00E5FF).copy(alpha = 0.6f),
                                shape = CircleShape
                            )
                            .clickable { isExpanded = true }
                    ) {
                        Text(
                            text = sessionCount.toString(),
                            color = Color(0xFF00E5FF),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // Expanded Menu Box
                    Surface(
                        modifier = Modifier
                            .width(200.dp)
                            .wrapContentHeight()
                            .padding(4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF121212),
                        border = borderStrokeCyan(),
                        tonalElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "ReelGuard: $activeAppName",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Divider(color = Color.White.copy(alpha = 0.1f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Session Reels:",
                                    color = Color("#A0A0A0".toColorInt()),
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = sessionCount.toString(),
                                    color = Color(0xFF00E5FF),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            // Pause tracking
                            Button(
                                onClick = {
                                    repository.stopSession()
                                    isExpanded = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF453A).copy(alpha = 0.15f),
                                    contentColor = Color(0xFFFF453A)
                                ),
                                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Pause,
                                        contentDescription = "Pause",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Pause Track", fontSize = 10.sp)
                                }
                            }
                            // Close Expanded overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isExpanded = false },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Collapse",
                                    color = Color("#5E5E5E".toColorInt()),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Full-screen Limit Reached Overlay Visual Layout
    @Composable
    fun LimitOverlayContent() {
        val activeAppPkg by repository.activeApp.collectAsState()
        val activeAppName = repository.targetApps.find { it.packageName == activeAppPkg }?.appName ?: "Active App"

        val durationMinutes by repository.cooldownDurationMinutes.collectAsState()
        var timeLeftSeconds by remember { mutableStateOf(durationMinutes * 60) }

        // Cooldown timer loop
        LaunchedEffect(Unit) {
            val cooldownEnd = System.currentTimeMillis() + (timeLeftSeconds * 1000)
            repository.setCooldownEnd(cooldownEnd)

            while (timeLeftSeconds > 0) {
                delay(1000L)
                timeLeftSeconds--
            }

            // Remove overlay when normal cooldown finishes
            repository.setCooldownEnd(0L)
            removeLimitEnforcementOverlay()
            // Stop current session so it re-triggers if they scroll again later
            repository.stopSession()
        }

        // Custom Radial Glow background Brush
        val radialGlowBrush = remember {
            object : ShaderBrush() {
                override fun createShader(size: androidx.compose.ui.geometry.Size): Shader {
                    return RadialGradientShader(
                        colors = listOf(
                            Color(0xFF00E5FF).copy(alpha = 0.15f),
                            Color(0xFF000000)
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                        radius = size.width * 0.7f
                    )
                }
            }
        }

        val formattedTime = remember(timeLeftSeconds) {
            val min = timeLeftSeconds / 60
            val sec = timeLeftSeconds % 60
            String.format("%02d:%02d", min, sec)
        }

        val context = LocalContext.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .background(radialGlowBrush),
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
                    imageVector = Icons.Default.Block,
                    contentDescription = "Stop icon",
                    tint = Color(0xFFFF453A),
                    modifier = Modifier
                        .size(80.dp)
                        .padding(bottom = 16.dp)
                )

                Text(
                    text = "Screen Time Spent",
                    color = Color("#A0A0A0".toColorInt()),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light
                )

                Text(
                    text = "Limit Reached — Take a Break",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Text(
                    text = "Enforced break cooldown active. Step away from your short-form algorithm for digital wellness.",
                    color = Color("#A0A0A0".toColorInt()),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Timer Countdown
                Text(
                    text = formattedTime,
                    color = Color(0xFF00E5FF),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 32.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Emergency Override Button
                Button(
                    onClick = {
                        // Start biometric override activity
                        val intent = Intent(context, BiometricOverrideActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra("package_name", activeAppPkg ?: "")
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF453A)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = "EMERGENCY OVERRIDE",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Small quit label
                Text(
                    text = "Biometric identification required for manual release",
                    color = Color("#5E5E5E".toColorInt()),
                    fontSize = 10.sp
                )
            }
        }
    }

    private fun borderStrokeCyan() = BorderStroke(
        width = 1.dp,
        color = Color(0xFF00E5FF).copy(alpha = 0.2f)
    )

    private fun String.toColorInt(): Int {
        return android.graphics.Color.parseColor(this)
    }

    // Foreground Service Management helpers
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReelGuard Service Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows your daily scrolling wellbeing limit tracking alert"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
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
            .setContentTitle("ReelGuard Scrolling Counter")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ReelGuardApplication.isInitialized()) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            if (intent?.hasExtra("remove_overlay") == true) {
                removeLimitEnforcementOverlay()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideBubble()
        removeLimitEnforcementOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        job.cancel()
        toneGenerator?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // Custom Compose Frame Host View Tree classes
    class HostComposeLayout(context: Context) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
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

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = store
    }
}
