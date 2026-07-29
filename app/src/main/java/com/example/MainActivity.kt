package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ReelRepository
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as ReelGuardApplication).repository
        val sharedPrefs = getSharedPreferences(ReelRepository.PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            MyApplicationTheme {
                var isOnboardingCompleted by remember {
                    mutableStateOf(sharedPrefs.getBoolean("onboarding_complete", false))
                }

                if (!isOnboardingCompleted) {
                    OnboardingScreen(
                        onFinished = {
                            sharedPrefs.edit().putBoolean("onboarding_complete", true).apply()
                            isOnboardingCompleted = true
                        }
                    )
                } else {
                    AuroraScaffold(repository)
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    INSIGHTS("Insights", Icons.Default.Insights),
    CONTROLS("Controls", Icons.Default.Tune)
}

/**
 * Main scaffold with a floating pill navigation bar instead of the
 * default full-width Material NavigationBar.
 */
@Composable
private fun AuroraScaffold(repository: ReelRepository) {
    var selected by remember { mutableStateOf(Tab.INSIGHTS) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        // Screen content
        Box(modifier = Modifier.fillMaxSize()) {
            when (selected) {
                Tab.INSIGHTS -> DashboardScreen(repository)
                Tab.CONTROLS -> SettingsScreen(repository)
            }
        }

        // Floating pill navigation
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(50))
                .background(Surface2.copy(alpha = 0.94f))
                .border(1.dp, HairLine, RoundedCornerShape(50))
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Tab.values().forEach { tab ->
                val isSelected = selected == tab
                val bg by animateColorAsState(
                    targetValue = if (isSelected) Violet.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                    animationSpec = tween(250),
                    label = "tab_bg"
                )
                val tint by animateColorAsState(
                    targetValue = if (isSelected) Violet else TextFaint,
                    animationSpec = tween(250),
                    label = "tab_tint"
                )

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { selected = tab }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tab.label,
                            color = tint,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
