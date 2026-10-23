package com.personal.voicemail.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.personal.voicemail.ui.screens.CustomGreetingDialog
import com.personal.voicemail.ui.screens.DialpadTab
import com.personal.voicemail.ui.screens.RecentsTab
import com.personal.voicemail.ui.screens.SystemReadinessBanner
import com.personal.voicemail.ui.screens.SystemReadinessDialog
import com.personal.voicemail.ui.screens.VoicemailInboxTab
import com.personal.voicemail.ui.screens.checkAllSystemReadiness
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.DarkBackground
import com.personal.voicemail.ui.theme.PersonalVoicemailTheme
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.util.SetupPreferences

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
    val context = LocalContext.current
    val tabs = MainTab.values()

    var isAllSystemReady by remember { mutableStateOf(checkAllSystemReadiness(context)) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showSystemSetupDialog by remember { mutableStateOf(false) }
    var showCustomGreetingDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAllSystemReady = checkAllSystemReadiness(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding(),
        topBar = {
            // Persistent Top Header Bar with Triple-Dot (⋮) Overflow Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isAllSystemReady) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x26FF9500))
                            .clickable { showSystemSetupDialog = true }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF9500),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Setup Incomplete",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFF9500)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Box {
                    IconButton(
                        onClick = { showOptionsMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options and Settings",
                            tint = Color(0xFF8E8E93)
                        )
                    }

                    DropdownMenu(
                        expanded = showOptionsMenu,
                        onDismissRequest = { showOptionsMenu = false },
                        modifier = Modifier.background(Color(0xFF1C1C1E))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Permissions & System Setup", color = TextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showOptionsMenu = false
                                showSystemSetupDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Custom Voice Greeting", color = TextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showOptionsMenu = false
                                showCustomGreetingDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Vivo / OEM Power Settings", color = TextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.BatteryAlert,
                                    contentDescription = null,
                                    tint = Color(0xFF34C759),
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showOptionsMenu = false
                                SetupPreferences.openOemBackgroundSettings(context)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Test Voicemail Pipeline", color = TextPrimary, fontSize = 14.sp) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = Color(0xFF5856D6),
                                    modifier = Modifier.size(20.dp)
                                )
                            },
                            onClick = {
                                showOptionsMenu = false
                                context.sendBroadcast(
                                    Intent(com.personal.voicemail.service.VoicemailSelfTestReceiver.ACTION_SELF_TEST)
                                        .setPackage(context.packageName)
                                )
                                Toast.makeText(context, "Running Voicemail Pipeline Self-Test...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xF0121214))
                    .border(
                        width = 0.5.dp,
                        color = Color(0x33545458),
                        shape = RectangleShape
                    )
                    .navigationBarsPadding()
            ) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
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
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isSelected) AccentBlue else Color(0xFF8E8E93)
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentBlue,
                                selectedTextColor = AccentBlue,
                                unselectedIconColor = Color(0xFF8E8E93),
                                unselectedTextColor = Color(0xFF8E8E93),
                                indicatorColor = Color.Transparent
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
            // Inline System Readiness Banner (Auto-hides completely if allReady == true)
            SystemReadinessBanner(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
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

    // Modal dialog for Permissions & System Setup
    if (showSystemSetupDialog) {
        SystemReadinessDialog(
            onDismissRequest = {
                showSystemSetupDialog = false
                isAllSystemReady = checkAllSystemReadiness(context)
            },
            onOpenGreeting = {
                showSystemSetupDialog = false
                showCustomGreetingDialog = true
            }
        )
    }

    // Modal dialog for Custom Voice Greeting
    if (showCustomGreetingDialog) {
        CustomGreetingDialog(
            onDismiss = {
                showCustomGreetingDialog = false
            }
        )
    }
}
