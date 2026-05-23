package com.example.ui.screens

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ReelTrackerAccessibilityService
import com.example.ui.theme.*

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit
) {
    var currentSlide by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("reelguard_prefs", Context.MODE_PRIVATE) }

    val slides = listOf(
        OnboardingSlide(
            title = "Take Control of Your Scroll",
            description = "Unconscious vertical swiping drains your productivity. ReelGuard detects short-form video feeds and reminds you to look up when limits are hit.",
            illustration = { OnboardingIllustrationOne() }
        ),
        OnboardingSlide(
            title = "Secure Local Accessibility Checks",
            description = "We use Android's Accessibility framework solely to detect scrolling gestures. No data ever leaves your device — tracking is 100% private and private-by-design.",
            illustration = { OnboardingIllustrationTwo() }
        ),
        OnboardingSlide(
            title = "Enforce Your Boundaries",
            description = "Define individual scroll allowances. When limits are exceeded, ReelGuard launches a compulsory cooldown system to help you escape the dopamine feed.",
            illustration = { OnboardingIllustrationThree() }
        )
    )

    val totalSlides = 5 // 3 info slides + 1 profile slide + 1 permission slide

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = PureBlack
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Skip button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (currentSlide < totalSlides - 1) {
                    Text(
                        text = "SKIP",
                        color = TextGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                // Save default nickname if skipping profile slide
                                if (currentSlide <= 3) {
                                    val savedName = sharedPrefs.getString("user_nickname", "") ?: ""
                                    if (savedName.trim().isEmpty()) {
                                        sharedPrefs.edit().putString("user_nickname", "Scroller").apply()
                                    }
                                }
                                onFinished()
                            }
                            .padding(8.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }

            // Central Animated Slide
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentSlide,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn() togetherWith
                                    slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut()
                        } else {
                            slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn() togetherWith
                                    slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut()
                        }
                    },
                    label = "slide_transition"
                ) { index ->
                    if (index < 3) {
                        val slide = slides[index]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Slide drawing representation
                            Box(
                                modifier = Modifier
                                    .size(180.dp)
                                    .padding(bottom = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                slide.illustration()
                            }

                            Text(
                                text = slide.title,
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            Text(
                                text = slide.description,
                                color = TextGray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Light,
                                textAlign = TextAlign.Center,
                                lineHeight = 22.sp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    } else if (index == 3) {
                        var nameInput by remember { mutableStateOf(sharedPrefs.getString("user_nickname", "") ?: "") }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(140.dp)
                                    .border(2.dp, Brush.linearGradient(listOf(ElectricCyan, TealAccent)), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Profile icon placeholder",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(64.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "What is your Nickname?",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = "Your nickname is saved locally on your device to personalize your wellbeing dashboard.",
                                color = TextGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Light,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = {
                                    nameInput = it
                                    sharedPrefs.edit().putString("user_nickname", it).apply()
                                },
                                placeholder = { Text("Enter your name/nickname", color = LabelGray) },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricCyan,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    cursorColor = ElectricCyan,
                                    focusedContainerColor = Color(0xFF121212),
                                    unfocusedContainerColor = Color(0xFF121212)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                            )
                        }
                    } else {
                        var isAccessibilityGranted by remember { mutableStateOf(false) }
                        var isOverlayGranted by remember { mutableStateOf(false) }

                        LaunchedEffect(Unit) {
                            while (true) {
                                isAccessibilityGranted = isAccessibilitySettingsOn(context, ReelTrackerAccessibilityService::class.java)
                                isOverlayGranted = Settings.canDrawOverlays(context)
                                kotlinx.coroutines.delay(1000)
                            }
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .border(2.dp, Brush.linearGradient(listOf(ElectricCyan, SuccessGreen)), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = "Shield Security icon",
                                    tint = ElectricCyan,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Grant System Permissions",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                text = "ReelGuard requires these permissions to detect background social app usage and display the limit overlay.",
                                color = TextGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Light,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )

                            // Accessibility Setup Row
                            PermissionSetupRow(
                                title = "Accessibility Service",
                                isGranted = isAccessibilityGranted,
                                onClick = {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } catch (e: Exception) {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                        } catch (ex: Exception) {}
                                    }
                                }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Overlay Setup Row
                            PermissionSetupRow(
                                title = "Draw Over Other Apps (Overlay)",
                                isGranted = isOverlayGranted,
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
                                        } catch (ex: Exception) {}
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Bottom Navigation Indicators & Buttons
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(totalSlides) { i ->
                        Box(
                            modifier = Modifier
                                .size(width = if (i == currentSlide) 18.dp else 6.dp, height = 6.dp)
                                .clip(CircleShape)
                                .background(if (i == currentSlide) ElectricCyan else LabelGray)
                        )
                    }
                }

                // Next / Get Started controls
                Button(
                    onClick = {
                        if (currentSlide < totalSlides - 1) {
                            if (currentSlide == 3) {
                                val savedName = sharedPrefs.getString("user_nickname", "") ?: ""
                                if (savedName.trim().isEmpty()) {
                                    sharedPrefs.edit().putString("user_nickname", "Scroller").apply()
                                }
                            }
                            currentSlide++
                        } else {
                            onFinished()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricCyan,
                        contentColor = PureBlack
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (currentSlide == totalSlides - 1) "GET STARTED" else "CONTINUE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Forward arrow",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionSetupRow(
    title: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF121212),
        border = BorderStroke(1.dp, if (isGranted) SuccessGreen.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (isGranted) "Permission granted successfully" else "Tap here to grant permission actively",
                    color = if (isGranted) SuccessGreen else TextGray,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) SuccessGreen else DangerRed)
            )
        }
    }
}

private fun isAccessibilitySettingsOn(context: Context, service: Class<out AccessibilityService>): Boolean {
    return try {
        var accessibilityEnabled = 0
        val serviceName = context.packageName + "/" + (service.canonicalName ?: service.name)
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Exception) {
            // Fallback
        }
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
                    if (accessibilityService.equals(serviceName, ignoreCase = true)) {
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

data class OnboardingSlide(
    val title: String,
    val description: String,
    val illustration: @Composable () -> Unit
)

// SVG custom-rendered vector models inside Onboarding Screen
@Composable
fun OnboardingIllustrationOne() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .border(2.dp, Brush.linearGradient(listOf(ElectricCyan, TealAccent)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Speed,
                contentDescription = "Speed illustration",
                tint = ElectricCyan,
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "SCROLL DETECTED",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TealAccent
            )
        }
    }
}

@Composable
fun OnboardingIllustrationTwo() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .border(2.dp, Brush.linearGradient(listOf(SuccessGreen, TealAccent)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Security illustration",
                tint = SuccessGreen,
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "100% PRIVATE DATA",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = SuccessGreen
            )
        }
    }
}

@Composable
fun OnboardingIllustrationThree() {
    Box(
        modifier = Modifier
            .size(140.dp)
            .border(2.dp, Brush.linearGradient(listOf(DangerRed, PrimaryOrange)), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Limits lock illustration",
                tint = DangerRed,
                modifier = Modifier.size(54.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "LIMIT OVERRIDE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = DangerRed
            )
        }
    }
}
