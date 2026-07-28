package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReelRepository
import com.example.service.ReelTrackerAccessibilityService
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.util.Permissions
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/* ─────────────────────────────────────────────────────────────
 *  Controls (Settings) — Midnight Aurora redesign.
 * ───────────────────────────────────────────────────────────── */

@Composable
fun SettingsScreen(repository: ReelRepository) {
    val context = LocalContext.current

    val isEnabled by repository.isServiceEnabled.collectAsState()
    val isBiometricEnabled by repository.biometricOverrideEnabled.collectAsState()
    val cooldownMin by repository.cooldownDurationMinutes.collectAsState()

    // Permission states, refreshed periodically so returning from
    // system settings updates the checklist without a manual refresh.
    var accGranted by remember { mutableStateOf(false) }
    var ovlGranted by remember { mutableStateOf(false) }
    var usageGranted by remember { mutableStateOf(false) }
    var battGranted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            accGranted = Permissions.isAccessibilityEnabled(context, ReelTrackerAccessibilityService::class.java)
            ovlGranted = Permissions.canDrawOverlays(context)
            usageGranted = Permissions.hasUsageStats(context)
            battGranted = Permissions.isBatteryExempt(context)
            kotlinx.coroutines.delay(2000)
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
            Column {
                Text(
                    text = "REELGUARD",
                    fontSize = 11.sp,
                    color = Violet,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "Controls",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextBright,
                    letterSpacing = (-0.5).sp
                )
            }
        }

        /* ── Engine master switch ── */
        item {
            AuroraCard(accent = if (isEnabled) Violet else null) {
                ToggleRow(
                    title = "Guard Engine",
                    description = "Master switch — pauses all tracking, overlays and enforcement.",
                    checked = isEnabled,
                    onCheckedChange = { repository.setServiceEnabled(it) }
                )
            }
        }

        /* ── Permission health ── */
        item { SectionLabel("System Access") }

        item {
            AuroraCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionRow(
                        title = "Accessibility service",
                        description = "Detects feed scroll gestures on-device.",
                        granted = accGranted,
                        icon = Icons.Default.Accessibility,
                        onClick = { Permissions.openAccessibilitySettings(context) }
                    )
                    PermissionRow(
                        title = "Display over other apps",
                        description = "Renders the counter bubble and lock screen.",
                        granted = ovlGranted,
                        icon = Icons.Default.Layers,
                        onClick = { Permissions.openOverlaySettings(context) }
                    )
                    PermissionRow(
                        title = "Usage access",
                        description = "Verifies background focus sessions.",
                        granted = usageGranted,
                        icon = Icons.Default.QueryStats,
                        onClick = { Permissions.openUsageStatsSettings(context) }
                    )
                    PermissionRow(
                        title = "Unrestricted battery",
                        description = "Prevents the tracker being hibernated.",
                        granted = battGranted,
                        icon = Icons.Default.BatteryChargingFull,
                        onClick = { Permissions.openBatterySettings(context) }
                    )
                }
            }
        }

        /* ── Enforcement behaviour ── */
        item { SectionLabel("Enforcement") }

        item {
            AuroraCard {
                ToggleRow(
                    title = "Biometric override",
                    description = "Require fingerprint / face scan to break an active cooldown.",
                    checked = isBiometricEnabled,
                    onCheckedChange = { repository.setBiometricOverrideEnabled(it) }
                )
            }
        }

        item {
            AuroraCard {
                Text(
                    text = "Cooldown duration",
                    color = TextBright,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "How long apps stay locked after a limit is breached.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp, bottom = 14.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(5, 10, 15, 30).forEach { mins ->
                        val selected = cooldownMin == mins
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) Violet else Surface3)
                                .border(
                                    1.dp,
                                    if (selected) Violet else HairLineSoft,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { repository.setCooldownDuration(mins) }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${mins}m",
                                color = if (selected) Ink else TextBright,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        /* ── Per-app limits ── */
        item { SectionLabel("Daily Allowances") }

        items(items = repository.targetApps, key = { it.packageName }) { app ->
            var appEnabled by remember(app.packageName) {
                mutableStateOf(repository.isAppEnabled(app.packageName))
            }
            var currentLimit by remember(app.packageName) {
                mutableStateOf(repository.getAppLimit(app.packageName))
            }

            AuroraCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = app.appName,
                        color = TextBright,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Switch(
                        checked = appEnabled,
                        onCheckedChange = {
                            appEnabled = it
                            repository.setAppEnabled(app.packageName, it)
                        },
                        colors = auroraSwitchColors()
                    )
                }

                if (appEnabled) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Scrolls per day", color = TextMuted, fontSize = 12.sp)
                        Text(
                            text = "$currentLimit",
                            color = Violet,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Slider(
                        value = currentLimit.toFloat(),
                        onValueChange = { currentLimit = it.roundToInt() },
                        onValueChangeFinished = {
                            repository.setAppLimit(app.packageName, currentLimit)
                        },
                        valueRange = 5f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = TextBright,
                            activeTrackColor = Violet,
                            inactiveTrackColor = Surface3,
                            activeTickColor = androidx.compose.ui.graphics.Color.Transparent,
                            inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent
                        )
                    )
                }
            }
        }

        /* ── Danger zone ── */
        item { SectionLabel("Danger Zone") }

        item {
            val scope = rememberCoroutineScope()
            var showDialog by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Rose.copy(alpha = 0.06f),
                border = BorderStroke(1.dp, Rose.copy(alpha = 0.25f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Reset everything",
                        color = Rose,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Wipes all counters, session logs, override history and the active streak. Irreversible.",
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )
                    Button(
                        onClick = { showDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Rose.copy(alpha = 0.12f),
                            contentColor = Rose
                        ),
                        border = BorderStroke(1.dp, Rose.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Wipe all data", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Wipe all data?", color = TextBright) },
                    text = {
                        Text(
                            "Today's counts, historical logs, overrides and your streak will all be permanently deleted.",
                            color = TextMuted
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            showDialog = false
                            scope.launch {
                                repository.clearAllData()
                                Toast.makeText(context, "All data wiped.", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("WIPE", color = Rose, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) {
                            Text("Cancel", color = TextBright)
                        }
                    },
                    containerColor = Surface2,
                    titleContentColor = TextBright,
                    textContentColor = TextMuted
                )
            }
        }

        item { Spacer(modifier = Modifier.height(90.dp)) }
    }
}
