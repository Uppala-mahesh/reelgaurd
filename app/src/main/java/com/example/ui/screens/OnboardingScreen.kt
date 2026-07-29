package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReelRepository
import com.example.service.ReelTrackerAccessibilityService
import com.example.ui.components.PermissionRow
import com.example.ui.theme.*
import com.example.util.Permissions

/* ─────────────────────────────────────────────────────────────
 *  Onboarding — Midnight Aurora redesign.
 *  3 story slides → nickname → permission checklist.
 * ───────────────────────────────────────────────────────────── */

private data class Slide(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val tagline: String,
    val colors: List<Color>
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    var currentSlide by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val sharedPrefs = remember(context) {
        context.getSharedPreferences(ReelRepository.PREFS_NAME, Context.MODE_PRIVATE)
    }

    val slides = remember {
        listOf(
            Slide(
                title = "Own your scroll",
                description = "Endless vertical feeds are engineered to keep you hooked. ReelGuard counts every swipe and reminds you to look up.",
                icon = Icons.Default.Speed,
                tagline = "SCROLLS, COUNTED",
                colors = listOf(Violet, Aqua)
            ),
            Slide(
                title = "Private by design",
                description = "Scroll detection runs entirely on-device through Android's accessibility framework. Nothing ever leaves your phone.",
                icon = Icons.Default.Security,
                tagline = "100% ON-DEVICE",
                colors = listOf(Mint, Aqua)
            ),
            Slide(
                title = "Limits with teeth",
                description = "Set a daily allowance per app. Cross it and ReelGuard locks the feed behind a cooldown — with a biometric-guarded escape hatch.",
                icon = Icons.Default.Lock,
                tagline = "ENFORCED COOLDOWNS",
                colors = listOf(Rose, Amber)
            )
        )
    }

    val totalSlides = 5 // 3 story + nickname + permissions

    fun ensureNickname() {
        val saved = sharedPrefs.getString("user_nickname", "") ?: ""
        if (saved.trim().isEmpty()) {
            sharedPrefs.edit().putString("user_nickname", "Scroller").apply()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Ink) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            /* Skip */
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (currentSlide < totalSlides - 1) {
                    Text(
                        text = "SKIP",
                        color = TextFaint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        modifier = Modifier
                            .clickable {
                                ensureNickname()
                                onFinished()
                            }
                            .padding(8.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }

            /* Slide content */
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
                            slideInHorizontally(tween(300)) { it } + fadeIn() togetherWith
                                    slideOutHorizontally(tween(300)) { -it } + fadeOut()
                        } else {
                            slideInHorizontally(tween(300)) { -it } + fadeIn() togetherWith
                                    slideOutHorizontally(tween(300)) { it } + fadeOut()
                        }
                    },
                    label = "onboarding_slide"
                ) { index ->
                    when {
                        index < 3 -> StorySlide(slides[index])
                        index == 3 -> NicknameSlide(sharedPrefs)
                        else -> PermissionsSlide()
                    }
                }
            }

            /* Dots + CTA */
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(totalSlides) { i ->
                        val width by animateDpAsState(
                            targetValue = if (i == currentSlide) 22.dp else 6.dp,
                            animationSpec = tween(250),
                            label = "dot"
                        )
                        Box(
                            modifier = Modifier
                                .size(width = width, height = 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == currentSlide)
                                        Brush.horizontalGradient(listOf(Violet, Aqua))
                                    else
                                        Brush.horizontalGradient(listOf(Surface3, Surface3))
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (currentSlide < totalSlides - 1) {
                            if (currentSlide == 3) ensureNickname()
                            currentSlide++
                        } else {
                            onFinished()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Violet,
                        contentColor = Ink
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Text(
                        text = if (currentSlide == totalSlides - 1) "START GUARDING" else "CONTINUE",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/* ─────────────── Slides ─────────────── */

@Composable
private fun StorySlide(slide: Slide) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .border(2.dp, Brush.linearGradient(slide.colors), CircleShape)
                .background(slide.colors.first().copy(alpha = 0.05f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = slide.icon,
                    contentDescription = null,
                    tint = slide.colors.first(),
                    modifier = Modifier.size(52.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = slide.tagline,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = slide.colors.last()
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = slide.title,
            color = TextBright,
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.5).sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = slide.description,
            color = TextMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun NicknameSlide(sharedPrefs: android.content.SharedPreferences) {
    var nameInput by remember {
        mutableStateOf(sharedPrefs.getString("user_nickname", "") ?: "")
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .border(2.dp, Brush.linearGradient(listOf(Violet, Aqua)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Profile",
                tint = Violet,
                modifier = Modifier.size(52.dp)
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "What should we call you?",
            color = TextBright,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Stored only on this device — used to personalize your dashboard.",
            color = TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        OutlinedTextField(
            value = nameInput,
            onValueChange = {
                nameInput = it
                sharedPrefs.edit().putString("user_nickname", it).apply()
            },
            placeholder = { Text("Your nickname", color = TextFaint) },
            singleLine = true,
            textStyle = TextStyle(color = TextBright, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Violet,
                unfocusedBorderColor = HairLine,
                cursorColor = Violet,
                focusedContainerColor = Surface1,
                unfocusedContainerColor = Surface1
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        )
    }
}

@Composable
private fun PermissionsSlide() {
    val context = LocalContext.current

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
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        Text(
            text = "Grant system access",
            color = TextBright,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "These four switches let ReelGuard watch for scrolls and enforce your limits.",
            color = TextMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionRow(
                title = "Accessibility service",
                description = "Detects feed scrolls locally.",
                granted = accGranted,
                icon = Icons.Default.Accessibility,
                onClick = { Permissions.openAccessibilitySettings(context) }
            )
            PermissionRow(
                title = "Display over other apps",
                description = "Shows counter bubble & lock overlay.",
                granted = ovlGranted,
                icon = Icons.Default.Layers,
                onClick = { Permissions.openOverlaySettings(context) }
            )
            PermissionRow(
                title = "Usage access",
                description = "Verifies focus sessions.",
                granted = usageGranted,
                icon = Icons.Default.QueryStats,
                onClick = { Permissions.openUsageStatsSettings(context) }
            )
            PermissionRow(
                title = "Unrestricted battery",
                description = "Keeps the guard alive in background.",
                granted = battGranted,
                icon = Icons.Default.BatteryChargingFull,
                onClick = { Permissions.openBatterySettings(context) }
            )
        }
    }
}
