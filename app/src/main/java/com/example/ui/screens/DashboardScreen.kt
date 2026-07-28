package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.ReelRepository
import com.example.service.ReelService
import com.example.service.ReelTrackerAccessibilityService
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.Permissions
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* ─────────────────────────────────────────────────────────────
 *  Insights (Dashboard) — Midnight Aurora redesign.
 * ───────────────────────────────────────────────────────────── */

enum class Period(val displayName: String) {
    DAILY("Today"),
    WEEKLY("7 Days"),
    MONTHLY("30 Days")
}

data class ChartItem(val label: String, val value: Int, val packageName: String)

@Composable
fun DashboardScreen(repository: ReelRepository) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val sharedPrefs = remember(context) {
        context.getSharedPreferences(ReelRepository.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val userNickname = remember(sharedPrefs) {
        sharedPrefs.getString("user_nickname", "Scroller") ?: "Scroller"
    }

    // Database streams
    val todayCounts by repository.todayCounts.collectAsState(initial = emptyList())
    val allHistory by repository.allHistoricalCounts.collectAsState(initial = emptyList())
    val streak by repository.streakFlow.collectAsState(initial = 0)
    val isEngineEnabled by repository.isServiceEnabled.collectAsState()

    // Permission health polling
    var isServiceHealthy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val acc = Permissions.isAccessibilityEnabled(context, ReelTrackerAccessibilityService::class.java)
            val ovl = Permissions.canDrawOverlays(context)
            isServiceHealthy = ReelTrackerAccessibilityService.isRunning && acc && ovl
            kotlinx.coroutines.delay(1500)
        }
    }

    var selectedPeriod by remember { mutableStateOf(Period.DAILY) }

    val enabledApps by remember(todayCounts) {
        derivedStateOf { repository.targetApps.filter { repository.isAppEnabled(it.packageName) } }
    }

    val chartData by remember(selectedPeriod, todayCounts, allHistory) {
        derivedStateOf {
            val appData = repository.targetApps.associate { it.packageName to 0 }.toMutableMap()
            when (selectedPeriod) {
                Period.DAILY -> todayCounts.forEach { appData[it.appPackageName] = it.reelCount }
                Period.WEEKLY -> aggregateSince(allHistory, 7, appData)
                Period.MONTHLY -> aggregateSince(allHistory, 30, appData)
            }
            appData.map { (pkg, cnt) ->
                ChartItem(
                    label = repository.targetApps.find { it.packageName == pkg }?.appName ?: "App",
                    value = cnt,
                    packageName = pkg
                )
            }
        }
    }

    val totalScrolls by remember(chartData) { derivedStateOf { chartData.sumOf { it.value } } }
    val todayTotal by remember(todayCounts) { derivedStateOf { todayCounts.sumOf { it.reelCount } } }
    val todayBudget by remember(enabledApps) {
        derivedStateOf { enabledApps.sumOf { repository.getAppLimit(it.packageName) }.coerceAtLeast(1) }
    }
    val isAnyLimitBreached by remember(todayCounts) {
        derivedStateOf {
            todayCounts.any {
                repository.isAppEnabled(it.appPackageName) &&
                        it.reelCount >= repository.getAppLimit(it.appPackageName)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(28.dp)) }

        /* ── Header ── */
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = greetingForHour(),
                        fontSize = 12.sp,
                        color = TextFaint,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = userNickname,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextBright,
                        letterSpacing = (-0.5).sp
                    )
                }

                val (pillText, pillColor) = when {
                    !isEngineEnabled -> "PAUSED" to TextFaint
                    !isServiceHealthy -> "SETUP NEEDED" to Amber
                    isAnyLimitBreached -> "LIMIT HIT" to Rose
                    else -> "PROTECTED" to Mint
                }
                StatusPill(
                    text = pillText,
                    color = pillColor,
                    onClick = {
                        if (isEngineEnabled && !isServiceHealthy) {
                            if (!Permissions.isAccessibilityEnabled(context, ReelTrackerAccessibilityService::class.java)) {
                                Permissions.openAccessibilitySettings(context)
                            } else if (!Permissions.canDrawOverlays(context)) {
                                Permissions.openOverlaySettings(context)
                            }
                        }
                    }
                )
            }
        }

        /* ── Hero ring: today's usage vs total budget ── */
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                UsageRing(
                    current = todayTotal,
                    budget = todayBudget,
                    streak = streak
                )
            }
        }

        /* ── Streak banner ── */
        item {
            AuroraCard(accent = if (streak > 0) Amber else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Amber.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = "Streak flame",
                            tint = Amber,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (streak > 0) "$streak-day discipline streak" else "No streak yet",
                            color = TextBright,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (streak > 0) "Every day under limit keeps the flame alive."
                            else "Finish today under your limits to ignite one.",
                            color = TextMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    if (streak > 0) {
                        Text(
                            text = "×$streak",
                            color = Amber,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }

        /* ── Per-app usage cards ── */
        item { SectionLabel("Platforms") }

        items(items = enabledApps.chunked(2), key = { row -> row.joinToString("-") { it.packageName } }) { rowApps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowApps.forEach { app ->
                    val count = todayCounts.find { it.appPackageName == app.packageName }?.reelCount ?: 0
                    val limit = repository.getAppLimit(app.packageName)
                    val progress = if (limit > 0) count.toFloat() / limit else 0f

                    AuroraCard(
                        modifier = Modifier.weight(1f),
                        accent = if (progress >= 1f) Rose else null
                    ) {
                        Text(
                            text = app.appName.uppercase(),
                            fontSize = 10.sp,
                            color = TextFaint,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = count.toString(),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (progress >= 1f) Rose else TextBright
                            )
                            Text(
                                text = " / $limit",
                                fontSize = 13.sp,
                                color = TextFaint,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(bottom = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        GradientProgressBar(progress = progress, danger = progress >= 0.8f)
                    }
                }
                if (rowApps.size < 2) Spacer(modifier = Modifier.weight(1f))
            }
        }

        /* ── Trend chart ── */
        item { SectionLabel("Trend") }

        item {
            AuroraCard {
                // Period selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface3)
                        .padding(3.dp)
                ) {
                    Period.values().forEach { period ->
                        val selected = selectedPeriod == period
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Violet else Color.Transparent)
                                .clickable { selectedPeriod = period }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = period.displayName,
                                color = if (selected) Ink else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))
                AuroraBarChart(chartData = chartData)
            }
        }

        /* ── Aggregate stats ── */
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AuroraCard(modifier = Modifier.weight(1f)) {
                    Text("Total scrolls", color = TextMuted, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = totalScrolls.toString(),
                        color = Violet,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                AuroraCard(modifier = Modifier.weight(1f)) {
                    Text("Top platform", color = TextMuted, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (totalScrolls > 0) chartData.maxByOrNull { it.value }?.label ?: "—" else "—",
                        color = Aqua,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }

        /* ── Tools ── */
        item { SectionLabel("Tools") }

        // CSV export
        item {
            AuroraCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            coroutineScope.launch {
                                try {
                                    val file = repository.generateCSVExport()
                                    shareCsvFile(context, file)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Aqua.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = "Export CSV",
                            tint = Aqua,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text("Export data as CSV", color = TextBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Daily counts, sessions and override history.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // Debug simulator
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surface2)
                    .clickable {
                        coroutineScope.launch {
                            val pkg = enabledApps.firstOrNull()?.packageName ?: "com.instagram.android"
                            val crossed = repository.registerScroll(pkg)
                            val count = todayCounts.find { it.appPackageName == pkg }?.reelCount ?: 0
                            val limit = repository.getAppLimit(pkg)
                            Toast.makeText(
                                context,
                                if (crossed) "Limit crossed for $pkg — enforcement fires."
                                else "Simulated scroll (${count + 1}/$limit)",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (crossed) ReelService.start(context)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = TextFaint,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Debug · simulate one scroll",
                    color = TextFaint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        item { Spacer(modifier = Modifier.height(90.dp)) }
    }
}

/* ─────────────── Hero usage ring ─────────────── */

@Composable
private fun UsageRing(current: Int, budget: Int, streak: Int) {
    val target = (current.toFloat() / budget).coerceIn(0f, 1f)
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target) {
        animated.animateTo(target, animationSpec = tween(900, easing = FastOutSlowInEasing))
    }

    val ringBrush = Brush.sweepGradient(listOf(Violet, Aqua, Violet))
    val over = current >= budget

    Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 14f, cap = StrokeCap.Round)
            // Track
            drawArc(
                color = Color.White.copy(alpha = 0.05f),
                startAngle = 130f,
                sweepAngle = 280f,
                useCenter = false,
                style = stroke
            )
            // Progress
            if (over) {
                drawArc(
                    color = Rose,
                    startAngle = 130f,
                    sweepAngle = 280f * animated.value,
                    useCenter = false,
                    style = stroke
                )
            } else {
                drawArc(
                    brush = ringBrush,
                    startAngle = 130f,
                    sweepAngle = 280f * animated.value,
                    useCenter = false,
                    style = stroke
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = current.toString(),
                fontSize = 58.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (over) Rose else TextBright,
                lineHeight = 58.sp,
                letterSpacing = (-2).sp
            )
            Text(
                text = "of $budget scrolls",
                fontSize = 12.sp,
                color = TextMuted,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (over) "OVER BUDGET" else "SCROLLS TODAY",
                fontSize = 10.sp,
                color = if (over) Rose else Violet,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
    }
}

/* ─────────────── Bar chart ─────────────── */

@Composable
fun AuroraBarChart(chartData: List<ChartItem>) {
    val maxScroll = remember(chartData) {
        (chartData.maxOfOrNull { it.value } ?: 0).let { if (it == 0) 50 else it }
    }

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
                val animatedScale = remember { Animatable(0f) }
                LaunchedEffect(item.value) {
                    animatedScale.animateTo(
                        targetValue = item.value.toFloat() / maxScroll,
                        animationSpec = tween(600, delayMillis = index * 80, easing = FastOutSlowInEasing)
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = item.value.toString(),
                        color = Violet,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .height(110.dp)
                            .fillMaxWidth(fraction = 0.42f)
                            .graphicsLayer {
                                scaleY = animatedScale.value.coerceIn(0.02f, 1f)
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(Brush.verticalGradient(listOf(Violet, Aqua.copy(alpha = 0.65f))))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = HairLineSoft)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            chartData.forEach { item ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = item.label,
                        color = TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/* ─────────────── Helpers ─────────────── */

private fun greetingForHour(): String {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "GOOD MORNING"
        in 12..16 -> "GOOD AFTERNOON"
        in 17..21 -> "GOOD EVENING"
        else -> "LATE NIGHT SCROLL?"
    }
}

private fun aggregateSince(
    history: List<com.example.data.DailyCountEntity>,
    days: Int,
    into: MutableMap<String, Int>
) {
    val limitDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.time
    val sdf = SimpleDateFormat(ReelRepository.DATE_FORMAT, Locale.getDefault())
    history.forEach { entry ->
        val entryDate = try { sdf.parse(entry.date) } catch (_: Exception) { null }
        if (entryDate != null && entryDate.after(limitDate)) {
            into[entry.appPackageName] = (into[entry.appPackageName] ?: 0) + entry.reelCount
        }
    }
}

private fun shareCsvFile(context: Context, file: File) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val fileUri: Uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "ReelGuard Data Export")
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ReelGuard export"))
    } catch (e: Exception) {
        Toast.makeText(context, "Sharing failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}
