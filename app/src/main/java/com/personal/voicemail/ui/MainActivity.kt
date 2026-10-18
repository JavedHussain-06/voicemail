package com.personal.voicemail.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.personal.voicemail.ui.screens.DialpadTab
import com.personal.voicemail.ui.screens.RecentsTab
import com.personal.voicemail.ui.screens.SystemReadinessBanner
import com.personal.voicemail.ui.screens.VoicemailInboxTab
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.DarkBackground
import com.personal.voicemail.ui.theme.DarkSurface
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.PersonalVoicemailTheme
import com.personal.voicemail.ui.theme.TextSecondary

enum class MainTab(val title: String, val icon: ImageVector) {
    VOICEMAIL("Voicemail", Icons.Default.Voicemail),
    RECENTS("Recents", Icons.Default.History),
    DIALPAD("Keypad", Icons.Default.Dialpad)
}

class MainActivity : ComponentActivity() {

    private var dialNumberState = mutableStateOf("")
    private var selectedTabState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleDialIntent(intent)

        setContent {
            PersonalVoicemailTheme {
                MainAppScreen(
                    selectedTabIndex = selectedTabState.intValue,
                    dialNumber = dialNumberState.value,
                    onTabSelected = { index -> selectedTabState.intValue = index },
                    onCallFromRecents = { number ->
                        dialNumberState.value = number
                        selectedTabState.intValue = MainTab.DIALPAD.ordinal
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.personal.voicemail.VoicemailApplication.voicemailRepository.reloadFromDisk()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    private fun handleDialIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "tel") {
            val number = data.schemeSpecificPart ?: ""
            if (number.isNotBlank()) {
                dialNumberState.value = number
                selectedTabState.intValue = MainTab.DIALPAD.ordinal
            }
        }
    }
}

@Composable
fun MainAppScreen(
    selectedTabIndex: Int,
    dialNumber: String,
    onTabSelected: (Int) -> Unit,
    onCallFromRecents: (String) -> Unit
) {
    val tabs = MainTab.values()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding(),
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xCC1A1A22))
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = selectedTabIndex == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { onTabSelected(index) },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = tab.title,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.title,
                                    fontSize = 11.sp,
                                    color = if (isSelected) AccentBlue else TextSecondary
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentBlue,
                                selectedTextColor = AccentBlue,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Color(0x33007AFF)
                            )
                        )
                    }
                }
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // System Readiness & Permissions Banner (Top of screen)
            SystemReadinessBanner(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Active Tab Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTabIndex) {
                    MainTab.VOICEMAIL.ordinal -> {
                        VoicemailInboxTab()
                    }
                    MainTab.RECENTS.ordinal -> {
                        RecentsTab(onCallNumber = onCallFromRecents)
                    }
                    MainTab.DIALPAD.ordinal -> {
                        DialpadTab(initialNumber = dialNumber)
                    }
                }
            }
        }
    }
}
