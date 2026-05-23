package com.example.ui.screens

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ReelTrackerAccessibilityService
import com.example.data.ReelRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repository: ReelRepository
) {
    val context = LocalContext.current

    // Global Preference Toggles
    val isEnabled by repository.isServiceEnabled.collectAsState()
    val isBiometricEnabled by repository.biometricOverrideEnabled.collectAsState()
    val cooldownMin by repository.cooldownDurationMinutes.collectAsState()

    // Permission check triggers
    var isAccessibilityGranted by remember { mutableStateOf(false) }
    var isOverlayGranted by remember { mutableStateOf(false) }
    var isUsageStatsGranted by remember { mutableStateOf(false) }
    var isBatteryExemptGranted by remember { mutableStateOf(false) }

    // Dynamic checks
    LaunchedEffect(Unit) {
        isAccessibilityGranted = isAccessibilitySettingsOn(context, ReelTrackerAccessibilityService::class.java)
        isOverlayGranted = Settings.canDrawOverlays(context)
        isUsageStatsGranted = checkUsageStatsPermission(context)
        isBatteryExemptGranted = checkBatteryExemption(context)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Upper Title Header
        item {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "REELGUARD",
                    fontSize = 11.sp,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Limit Controls",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        // Global Activation Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF121212),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Global Guard Engine",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Disable to pause all background tracker processes and bubble overlay loops.",
                            color = TextGray,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { repository.setServiceEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureBlack,
                            checkedTrackColor = ElectricCyan,
                            uncheckedThumbColor = TextGray,
                            uncheckedTrackColor = Color(0xFF1E1E1E)
                        )
                    )
                }
            }
        }

        // Permissions Checklist Dashboard
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "System Permissions Integrity",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. Accessibility Service indicator
                    PermissionDashboardItem(
                        title = "Accessibility Framework",
                        desc = "Needed to detect feed scroll gestures locally.",
                        granted = isAccessibilityGranted,
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (e: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                } catch (ex: Exception) {
                                    android.widget.Toast.makeText(context, "Accessibility settings could not be opened: ${ex.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 10.dp))

                    // 2. Windows Float Overlays indicator
                    PermissionDashboardItem(
                        title = "SYSTEM_ALERT_WINDOW Overlay",
                        desc = "Required to render the lock overlay and floating counter.",
                        granted = isOverlayGranted,
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    android.widget.Toast.makeText(context, "Overlay settings could not be opened: ${ex.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 10.dp))

                    // 3. Usage Stats index
                    PermissionDashboardItem(
                        title = "Usage Analytics Access",
                        desc = "Verifies background focus duration and streams.",
                        granted = isUsageStatsGranted,
                        onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (e: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                } catch (ex: Exception) {
                                    android.widget.Toast.makeText(context, "Usage statistics settings could not be opened: ${ex.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 10.dp))

                    // 4. Doze/Battery index
                    PermissionDashboardItem(
                        title = "Battery Unrestricted",
                        desc = "Exempts ReelGuard from aggressive task hibernation.",
                        granted = isBatteryExemptGranted,
                        onClick = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    android.widget.Toast.makeText(context, "Battery settings could not be opened: ${ex.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }
        }

        // Biometric override setting row
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF121212),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Biometric Override Release",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Enable to require secure face/fingerprint scans during override requests.",
                            color = TextGray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Switch(
                        checked = isBiometricEnabled,
                        onCheckedChange = { repository.setBiometricOverrideEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PureBlack,
                            checkedTrackColor = ElectricCyan,
                            uncheckedThumbColor = TextGray,
                            uncheckedTrackColor = Color(0xFF1E1E1E)
                        )
                    )
                }
            }
        }

        // Cooldown Picker Setting
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF121212),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Take-a-Break Cooldown Focus",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Specifies lockout duration minutes when limits are breached.",
                        color = TextGray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5, 10, 15, 30).forEach { mins ->
                            val isSelected = cooldownMin == mins
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) ElectricCyan else Color(0xFF03DAC6).copy(alpha = 0.1f))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) ElectricCyan else Color.White.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { repository.setCooldownDuration(mins) }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$mins MIN",
                                    color = if (isSelected) PureBlack else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // App selection slider limits section title
        item {
            Text(
                text = "Target Platforms Thresholds",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        // Load sliders per app
        items(
            items = repository.targetApps,
            key = { it.packageName }
        ) { app ->
            val appEnabled = remember(app.packageName) { mutableStateOf(repository.isAppEnabled(app.packageName)) }
            val currentLimit = remember(app.packageName) { mutableStateOf(repository.getAppLimit(app.packageName)) }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF121212),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header App and Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = app.appName,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = appEnabled.value,
                            onCheckedChange = {
                                appEnabled.value = it
                                repository.setAppEnabled(app.packageName, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureBlack,
                                checkedTrackColor = ElectricCyan,
                                uncheckedThumbColor = TextGray,
                                uncheckedTrackColor = Color(0xFF1E1E1E)
                            )
                        )
                    }

                    if (appEnabled.value) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Daily Scroll Allowance",
                                color = TextGray,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${currentLimit.value} Reels",
                                color = ElectricCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Sliding Gauge matching requirement "cyan active track"
                        Slider(
                            value = currentLimit.value.toFloat(),
                            onValueChange = {
                                currentLimit.value = it.roundToInt()
                            },
                            onValueChangeFinished = {
                                repository.setAppLimit(app.packageName, currentLimit.value)
                            },
                            valueRange = 5f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = ElectricCyan,
                                inactiveTrackColor = Color(0xFF1E1E1E),
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }

        // Clear all data card
        item {
            val scope = rememberCoroutineScope()
            var showDialog by remember { mutableStateOf(false) }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E1616),
                border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Reset & Pure Clean",
                        color = DangerRed,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Instantly clears all tracked scroll historical counters, session records, and active cooldown states back to 0. This cannot be undone.",
                        color = TextGray,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    Button(
                        onClick = { showDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DangerRed.copy(alpha = 0.1f),
                            contentColor = DangerRed
                        ),
                        border = BorderStroke(1.dp, DangerRed.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset All Metrics to 0", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Reset database metrics?", color = Color.White) },
                    text = { Text("This will permanently clear today's counts, historical logs, and custom overrides, resetting your active streak back to 0.", color = TextGray) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDialog = false
                                scope.launch {
                                    repository.clearAllData()
                                    android.widget.Toast.makeText(context, "Database cleared successfully! Data is now strictly 0.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("CONFIRM RESET", color = DangerRed, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) {
                            Text("CANCEL", color = Color.White)
                        }
                    },
                    containerColor = Color(0xFF121212),
                    textContentColor = TextGray,
                    titleContentColor = Color.White
                )
            }
        }

        // Spacing offset
        item {
            Spacer(modifier = Modifier.height(70.dp))
        }
    }
}

@Composable
fun PermissionDashboardItem(
    title: String,
    desc: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = TextGray,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (granted) SuccessGreen else DangerRed)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Expand permission settings indicator",
                tint = LabelGray,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun isAccessibilitySettingsOn(context: Context, service: Class<out AccessibilityService>): Boolean {
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

private fun checkUsageStatsPermission(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}

private fun checkBatteryExemption(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}
