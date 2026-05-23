package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.DailyCountEntity
import com.example.data.ReelRepository
import com.example.service.ReelService
import com.example.service.ReelTrackerAccessibilityService
import com.example.ReelGuardApplication
import com.example.ui.theme.*
import android.provider.Settings
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(
    repository: ReelRepository
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("reelguard_prefs", Context.MODE_PRIVATE) }
    val userNickname = remember(sharedPrefs) { sharedPrefs.getString("user_nickname", "Scroller") ?: "Scroller" }
    var activeToast: Toast? by remember { mutableStateOf(null) }

    // Observe DB
    val todayCounts by repository.todayCounts.collectAsState(initial = emptyList())
    val allHistory by repository.allHistoricalCounts.collectAsState(initial = emptyList())
    val streak by repository.streakFlow.collectAsState(initial = 0)

    var isAccessibilityGranted by remember { mutableStateOf(false) }
    var isOverlayGranted by remember { mutableStateOf(false) }
    var isServiceActiveGlobally by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isAccessibilityGranted = isAccessibilitySettingsOn(context, ReelTrackerAccessibilityService::class.java)
            isOverlayGranted = Settings.canDrawOverlays(context)
            isServiceActiveGlobally = ReelTrackerAccessibilityService.isRunning && isAccessibilityGranted && isOverlayGranted
            kotlinx.coroutines.delay(1000)
        }
    }

    var selectedPeriod by remember { mutableStateOf(Period.DAILY) }

    val enabledApps by remember(todayCounts) {
        derivedStateOf {
            repository.targetApps.filter { repository.isAppEnabled(it.packageName) }
        }
    }

    val chunkedEnabledApps by remember(enabledApps) {
        derivedStateOf {
            enabledApps.chunked(2)
        }
    }

    // Chart processing based on selected Period
    val chartData by remember(selectedPeriod, todayCounts, allHistory) {
         derivedStateOf {
            val appData = repository.targetApps.associate { it.packageName to 0 }.toMutableMap()

            when (selectedPeriod) {
                Period.DAILY -> {
                    todayCounts.forEach { entry ->
                        appData[entry.appPackageName] = entry.reelCount
                    }
                }
                Period.WEEKLY -> {
                    // Sum scrolls over last 7 days
                    val limitDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }.time
                    allHistory.forEach { entry ->
                        val entryDate = parseDate(entry.date)
                        if (entryDate != null && entryDate.after(limitDate)) {
                            appData[entry.appPackageName] = (appData[entry.appPackageName] ?: 0) + entry.reelCount
                        }
                    }
                }
                Period.MONTHLY -> {
                    // Sum scrolls over last 30 days
                    val limitDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -30) }.time
                    allHistory.forEach { entry ->
                        val entryDate = parseDate(entry.date)
                        if (entryDate != null && entryDate.after(limitDate)) {
                            appData[entry.appPackageName] = (appData[entry.appPackageName] ?: 0) + entry.reelCount
                        }
                    }
                }
            }
            appData.toList().map { (pkg, cnt) ->
                val label = repository.targetApps.find { it.packageName == pkg }?.appName ?: "App"
                ChartItem(label = label, value = cnt, packageName = pkg)
            }
        }
    }

    // Peak metric
    val totalScrollsSum by remember(chartData) {
         derivedStateOf {
            chartData.sumOf { it.value }
        }
    }

    val isEngineEnabled by repository.isServiceEnabled.collectAsState(initial = true)

    val isAnyLimitBreached by remember(todayCounts) {
         derivedStateOf {
            todayCounts.any { entry ->
                val limit = repository.getAppLimit(entry.appPackageName)
                val enabled = repository.isAppEnabled(entry.appPackageName)
                enabled && entry.reelCount > limit
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // App Header Section with Status Pill
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Welcome back, $userNickname",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = (-0.5).sp
                    )
                    val statusMessage = if (isEngineEnabled) "Tracking active • $totalScrollsSum reels tracked" else "Engine paused • Limits inactive"
                    Text(
                        text = statusMessage,
                        fontSize = 13.sp,
                        color = TextGray,
                        fontWeight = FontWeight.Light
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Service Status Pill
                    Surface(
                        onClick = {
                            if (!isServiceActiveGlobally) {
                                if (!isAccessibilityGranted) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } catch (e: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        } catch (ex: Exception) {}
                                    }
                                } else if (!isOverlayGranted) {
                                    try {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                                        } catch (ex: Exception) {}
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(50),
                        color = if (isServiceActiveGlobally) SuccessGreen.copy(alpha = 0.1f) else DangerRed.copy(alpha = 0.1f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isServiceActiveGlobally) SuccessGreen.copy(alpha = 0.4f) else DangerRed.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .wrapContentWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isServiceActiveGlobally) SuccessGreen else DangerRed)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isServiceActiveGlobally) "🟢 Tracking Active" else "🔴 Tracking Inactive (Tap to Fix)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isServiceActiveGlobally) SuccessGreen else DangerRed
                            )
                        }
                    }
                }

                val badgeText = if (!isEngineEnabled) "Paused" else if (isAnyLimitBreached) "Alert" else "Safe"
                val badgeColor = if (!isEngineEnabled) TextGray else if (isAnyLimitBreached) DangerRed else SuccessGreen

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor.copy(alpha = 0.1f))
                        .border(1.dp, badgeColor.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = badgeText.uppercase(),
                        fontSize = 10.sp,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }
            }
        }

        // Debug Simulator Button
        item {
            Button(
                onClick = {
                    coroutineScope.launch {
                        val targetAppPkg = enabledApps.firstOrNull()?.packageName ?: "com.instagram.android"
                        val limitValue = repository.getAppLimit(targetAppPkg)
                        val dao = ReelGuardApplication.instance.database.reelDao()
                        val beforeCounts = dao.getDailyCounts(repository.getTodayDateString())
                        val beforeCount = beforeCounts.find { it.appPackageName == targetAppPkg }?.reelCount ?: 0
                        val limitReached = repository.registerScroll(targetAppPkg)
                        val afterCounts = dao.getDailyCounts(repository.getTodayDateString())
                        val afterCount = afterCounts.find { it.appPackageName == targetAppPkg }?.reelCount ?: 0

                        activeToast?.cancel()
                        val textMsg = if (afterCount >= limitValue) {
                            "Limit exceeded for $targetAppPkg ($afterCount/$limitValue)! Cooldown triggered."
                        } else {
                            "Simulated scroll added for $targetAppPkg ($afterCount/$limitValue)"
                        }
                        val toastDur = if (afterCount >= limitValue) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                        val toastObj = Toast.makeText(context, textMsg, toastDur)
                        toastObj.show()
                        activeToast = toastObj

                        if (afterCount >= limitValue) {
                            ReelService.start(context)
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E1E1E),
                    contentColor = ElectricCyan
                ),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    text = "Debug: Simulate +1 Scroll",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }

        // Central Animated Display (Radial Circle Progress Indicator)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.size(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Background Ring Path
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = ElectricCyan.copy(alpha = 0.1f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                        )
                    }

                    // Active Animating Progress Circle
                    val animatedPercent = remember { Animatable(0f) }
                    val maxTarget = 100f
                    LaunchedEffect(totalScrollsSum) {
                        animatedPercent.animateTo(
                            targetValue = (totalScrollsSum.toFloat() / maxTarget).coerceIn(0f, 1f),
                            animationSpec = tween(800, easing = FastOutSlowInEasing)
                        )
                    }

                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        drawArc(
                            color = ElectricCyan,
                            startAngle = -90f,
                            sweepAngle = animatedPercent.value * 360f,
                            useCenter = false,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 4.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = totalScrollsSum.toString(),
                            fontSize = 64.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            lineHeight = 64.sp
                        )
                        Text(
                            text = "REELS TODAY",
                            fontSize = 11.sp,
                            color = ElectricCyan,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }
        }

        // Stats Dashboard (Enhanced Spacious Grid for Target Platforms)
        items(
            items = chunkedEnabledApps,
            key = { row -> row.joinToString("-") { it.packageName } }
        ) { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowApps.forEach { app ->
                    val todayAppCount = todayCounts.find { it.appPackageName == app.packageName }?.reelCount ?: 0
                    val limit = repository.getAppLimit(app.packageName)

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF121212).copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = app.appName.uppercase(),
                                fontSize = 12.sp,
                                color = LabelGray,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Text(
                                    text = todayAppCount.toString(),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Limit: $limit",
                                    fontSize = 12.sp,
                                    color = TealAccent,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            val progress = if (limit > 0) (todayAppCount.toFloat() / limit).coerceIn(0f, 1f) else 0f
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(progress)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(ElectricCyan, TealAccent)
                                            )
                                        )
                                )
                            }
                        }
                    }
                }
                if (rowApps.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // Streak Banner - Premium Material 3 Glassmorphic Card (integrated carefully into density layouts)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF121212).copy(alpha = 0.7f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(PrimaryOrange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = "Streak flame icon",
                            tint = PrimaryOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (streak > 0) "$streak Day Under-Limit Streak!" else "No Active Streak",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (streak > 0) "Stay conscious of scrolling boundaries." else "Keep under limit to kickstart a focus streak.",
                            color = TextGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        // Toggle Switch & Periods Header Row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Period.values().forEach { period ->
                    val selected = selectedPeriod == period
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) ElectricCyan else Color.Transparent)
                            .clickable { selectedPeriod = period }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = period.displayName,
                            color = if (selected) PureBlack else Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Weekly Trend Bar Chart Section Card (Styled as bg-[#0A0A0A] border-white/5)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0A0A0A),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedPeriod.displayName.uppercase()} TREND",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (streak > 0) "$streak DAY STREAK " else "ACTIVE LIMITS ",
                                color = TealAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(text = "🔥", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))

                    // Premium Bar Chart Frame Layout
                    CustomScrollsBarChart(chartData = chartData)
                }
            }
        }

        // Total Aggregate Metrics Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF121212).copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Total Scrolls",
                            color = TextGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = totalScrollsSum.toString(),
                            color = ElectricCyan,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF121212).copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Primary Platform",
                            color = TextGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        val peakApp = chartData.maxByOrNull { it.value }?.label ?: "None"
                        Text(
                            text = if (totalScrollsSum > 0) peakApp else "None",
                            color = TealAccent,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // CSV Export Section Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF121212).copy(alpha = 0.7f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                        .clickable {
                            coroutineScope.launch {
                                try {
                                    val file = repository.generateCSVExport()
                                    shareExcelFile(context, file)
                                } catch (e: Exception) {
                                    Toast
                                        .makeText(
                                            context,
                                            "Export action failed: ${e.localizedMessage}",
                                            Toast.LENGTH_LONG
                                        )
                                        .show()
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "CSV logo icon",
                            tint = ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Export Scrolling Database",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Compiles log entries to a local shareable CSV file.",
                                color = TextGray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // Space out bottom
        item {
            Spacer(modifier = Modifier.height(70.dp))
        }
    }
}

// Custom rendered premium bar chart using Compose Canvas
@Composable
fun CustomScrollsBarChart(chartData: List<ChartItem>) {
    val maxScroll = remember(chartData) {
        val peak = chartData.maxOfOrNull { it.value } ?: 0
        if (peak == 0) 50 else peak
    }

    val itemSize = chartData.size

    // Canvas drawing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            chartData.forEachIndexed { index, item ->
                // Apply stagger animation
                val animatedScale = remember { Animatable(0f) }
                LaunchedEffect(item.value) {
                    animatedScale.animateTo(
                        targetValue = item.value.toFloat() / maxScroll,
                        animationSpec = tween(600, delayMillis = index * 100, easing = FastOutSlowInEasing)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    // Display above bars
                    Text(
                        text = item.value.toString(),
                        color = ElectricCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Vertical Bar Node
                    Box(
                        modifier = Modifier
                            .height(110.dp)
                            .fillMaxWidth(fraction = 0.45f)
                            .graphicsLayer {
                                scaleY = animatedScale.value.coerceIn(0.02f, 1f)
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(ElectricCyan, TealAccent)
                                )
                            )
                    )
                }
            }
        }

        // Sub Bar Icons/Labels Row
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            chartData.forEach { item ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.label,
                        color = TextGray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

enum class Period(val displayName: String) {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly")
}

data class ChartItem(
    val label: String,
    val value: Int,
    val packageName: String
)

private fun parseDate(dateStr: String): Date? {
    return try {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)
    } catch (e: Exception) {
        null
    }
}

private fun shareExcelFile(context: Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val fileUri: Uri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "ReelGuard Scroll Limits Data Export")
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(intent, "Share SQLite Data via CSV"))
    } catch (e: Exception) {
        Toast.makeText(context, "Sharing action error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private fun isAccessibilitySettingsOn(context: Context, service: Class<out android.accessibilityservice.AccessibilityService>): Boolean {
    try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        if (am != null) {
            val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (enabledService in enabledServices) {
                val componentInfo = enabledService.resolveInfo?.serviceInfo
                if (componentInfo != null && componentInfo.packageName == context.packageName && componentInfo.name == service.name) {
                    return true
                }
            }
        }
    } catch (e: Exception) {}
    return try {
        var accessibilityEnabled = 0
        val serviceName = context.packageName + "/" + service.name
        val serviceNameAlt = context.packageName + "/" + (service.canonicalName ?: service.name)
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Exception) {}
        val mStringColonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    val accessibilityService = mStringColonSplitter.next()
                    if (accessibilityService.equals(serviceName, ignoreCase = true) || 
                        accessibilityService.equals(serviceNameAlt, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        false
    } catch (e: Exception) {
        false
    }
}
