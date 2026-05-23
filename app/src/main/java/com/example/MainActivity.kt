package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as ReelGuardApplication).repository
        val sharedPrefs = getSharedPreferences("reelguard_prefs", Context.MODE_PRIVATE)

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
                    MainNavigationScaffold(repository)
                }
            }
        }
    }
}

@Composable
fun MainNavigationScaffold(repository: com.example.data.ReelRepository) {
    var selectedIndex by remember { mutableStateOf(0) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(PureBlack),
        containerColor = PureBlack,
        bottomBar = {
            NavigationBar(
                containerColor = PureBlack,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .background(PureBlack)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(64.dp)
            ) {
                // Dashboard Tab
                NavigationBarItem(
                    selected = selectedIndex == 0,
                    onClick = { selectedIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Analytics Menu Dashboard Option",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text("Analytics", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        unselectedIconColor = TextGray,
                        unselectedTextColor = TextGray,
                        indicatorColor = Color.Transparent
                    )
                )

                // Settings Tab
                NavigationBarItem(
                    selected = selectedIndex == 1,
                    onClick = { selectedIndex = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Limits Setup Config Settings Option",
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text("Settings", fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ElectricCyan,
                        selectedTextColor = ElectricCyan,
                        unselectedIconColor = TextGray,
                        unselectedTextColor = TextGray,
                        indicatorColor = Color.Transparent
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(PureBlack)
        ) {
            when (selectedIndex) {
                0 -> DashboardScreen(repository)
                1 -> SettingsScreen(repository)
            }
        }
    }
}
