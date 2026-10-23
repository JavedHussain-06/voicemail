package com.personal.voicemail.ui.screens

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.personal.voicemail.VoicemailApplication
import com.personal.voicemail.service.ShizukuAudioController
import com.personal.voicemail.service.VoicemailAccessibilityService
import com.personal.voicemail.ui.components.GlassmorphicCard
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.CallAcceptGreen
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.ui.theme.TextSecondary
import com.personal.voicemail.util.SetupPreferences

/**
 * Checks all 4 core readiness requirements:
 * 1. Default Phone App (ROLE_DIALER)
 * 2. Background Battery Survival
 * 3. Telephony / Contacts / Audio Runtime Permissions
 * 4. Background Audio Guardian (Accessibility)
 */
fun checkAllSystemReadiness(context: Context): Boolean {
    return checkDefaultDialerRole(context) &&
            checkBatteryOptimizationExempt(context) &&
            checkRuntimePermissions(context) &&
            checkAccessibilityServiceEnabled(context)
}

/**
 * Checks if the APK has permanent digital telephony privileges (CAPTURE_AUDIO_OUTPUT + MODIFY_AUDIO_ROUTING).
 */
fun checkDigitalTelephonyPrivilege(context: Context): Boolean {
    return VoicemailApplication.shizukuController.hasPermanentDigitalTelephonyPrivilege()
}

/**
 * Inline readiness banner displayed at the top of the main screen.
 * Automatically hides when all setup criteria are met.
 */
@Composable
fun SystemReadinessBanner(
    modifier: Modifier = Modifier,
    forceShow: Boolean = false
) {
    val context = LocalContext.current
    var isDefaultDialer by remember { mutableStateOf(checkDefaultDialerRole(context)) }
    var isBatteryExempt by remember { mutableStateOf(checkBatteryOptimizationExempt(context)) }
    var isRuntimePermsGranted by remember { mutableStateOf(checkRuntimePermissions(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityServiceEnabled(context)) }
    var isShizukuReady by remember { mutableStateOf(VoicemailApplication.shizukuController.updateShizukuStatus()) }
    var isDigitalPrivilegeGranted by remember { mutableStateOf(checkDigitalTelephonyPrivilege(context)) }

    val allReady = isDefaultDialer && isBatteryExempt && isRuntimePermsGranted && isAccessibilityEnabled

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefaultDialer = checkDefaultDialerRole(context)
                isBatteryExempt = checkBatteryOptimizationExempt(context)
                isRuntimePermsGranted = checkRuntimePermissions(context)
                isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
                isShizukuReady = VoicemailApplication.shizukuController.updateShizukuStatus()
                isDigitalPrivilegeGranted = checkDigitalTelephonyPrivilege(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        isDefaultDialer = checkDefaultDialerRole(context)
        isBatteryExempt = checkBatteryOptimizationExempt(context)
        isRuntimePermsGranted = checkRuntimePermissions(context)
        isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
        isShizukuReady = VoicemailApplication.shizukuController.updateShizukuStatus()
        isDigitalPrivilegeGranted = checkDigitalTelephonyPrivilege(context)
    }

    // Auto-hide banner when setup is complete (unless forceShow is requested)
    if (allReady && !forceShow) {
        return
    }

    SystemReadinessCardContent(
        modifier = modifier,
        isDefaultDialer = isDefaultDialer,
        isBatteryExempt = isBatteryExempt,
        isRuntimePermsGranted = isRuntimePermsGranted,
        isAccessibilityEnabled = isAccessibilityEnabled,
        isShizukuReady = isShizukuReady,
        isDigitalPrivilegeGranted = isDigitalPrivilegeGranted,
        onRefreshStatus = {
            isDefaultDialer = checkDefaultDialerRole(context)
            isBatteryExempt = checkBatteryOptimizationExempt(context)
            isRuntimePermsGranted = checkRuntimePermissions(context)
            isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
            isShizukuReady = VoicemailApplication.shizukuController.updateShizukuStatus()
            isDigitalPrivilegeGranted = checkDigitalTelephonyPrivilege(context)
        }
    )
}

/**
 * Full modal dialog opened from the top-right triple-dot menu, allowing the user
 * to inspect, configure, and re-verify system status, voice greeting, and self-tests.
 */
@Composable
fun SystemReadinessDialog(
    onDismissRequest: () -> Unit,
    onOpenGreeting: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isDefaultDialer by remember { mutableStateOf(checkDefaultDialerRole(context)) }
    var isBatteryExempt by remember { mutableStateOf(checkBatteryOptimizationExempt(context)) }
    var isRuntimePermsGranted by remember { mutableStateOf(checkRuntimePermissions(context)) }
    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityServiceEnabled(context)) }
    var isShizukuReady by remember { mutableStateOf(VoicemailApplication.shizukuController.updateShizukuStatus()) }
    var isDigitalPrivilegeGranted by remember { mutableStateOf(checkDigitalTelephonyPrivilege(context)) }

    fun refresh() {
        isDefaultDialer = checkDefaultDialerRole(context)
        isBatteryExempt = checkBatteryOptimizationExempt(context)
        isRuntimePermsGranted = checkRuntimePermissions(context)
        isAccessibilityEnabled = checkAccessibilityServiceEnabled(context)
        isShizukuReady = VoicemailApplication.shizukuController.updateShizukuStatus()
        isDigitalPrivilegeGranted = checkDigitalTelephonyPrivilege(context)
    }

    LaunchedEffect(Unit) { refresh() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(
                onClick = onDismissRequest,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "System Setup & Readiness",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SystemReadinessCardContent(
                    modifier = Modifier.fillMaxWidth(),
                    isDefaultDialer = isDefaultDialer,
                    isBatteryExempt = isBatteryExempt,
                    isRuntimePermsGranted = isRuntimePermsGranted,
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    isShizukuReady = isShizukuReady,
                    isDigitalPrivilegeGranted = isDigitalPrivilegeGranted,
                    alwaysExpanded = true,
                    onRefreshStatus = { refresh() },
                    onOpenGreeting = onOpenGreeting
                )
            }
        },
        containerColor = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun SystemReadinessCardContent(
    modifier: Modifier = Modifier,
    isDefaultDialer: Boolean,
    isBatteryExempt: Boolean,
    isRuntimePermsGranted: Boolean,
    isAccessibilityEnabled: Boolean,
    isShizukuReady: Boolean,
    isDigitalPrivilegeGranted: Boolean = false,
    alwaysExpanded: Boolean = false,
    onRefreshStatus: () -> Unit,
    onOpenGreeting: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(alwaysExpanded) }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshStatus()
    }

    val permsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshStatus()
    }

    val totalItems = 4
    val completedCount = (if (isDefaultDialer) 1 else 0) +
            (if (isBatteryExempt) 1 else 0) +
            (if (isRuntimePermsGranted) 1 else 0) +
            (if (isAccessibilityEnabled) 1 else 0)

    val isAllReady = completedCount == totalItems

    GlassmorphicCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !alwaysExpanded) { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isAllReady) Color(0x3334C759)
                                else Color(0x33FF9500)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isAllReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (isAllReady) CallAcceptGreen else Color(0xFFFF9500),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = if (isAllReady) "Voicemail Engine Ready" else "Setup Incomplete ($completedCount/$totalItems)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (isAllReady) {
                                if (isDigitalPrivilegeGranted) "All permissions active • Permanent digital telephony active"
                                else "All core services active • Standalone acoustic mode ready"
                            } else "Action required to screen calls and record voicemails",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                if (!alwaysExpanded) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded || alwaysExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Item 1: Default Dialer Role
                    ReadinessItem(
                        title = "Default Phone App (ROLE_DIALER)",
                        subtitle = "Required to intercept incoming calls and replace stock dialer",
                        isComplete = isDefaultDialer,
                        icon = Icons.Default.PhoneInTalk,
                        onAction = {
                            requestDefaultDialerRole(context, roleLauncher)
                        }
                    )

                    // Item 2: Background Audio Guardian (Accessibility)
                    ReadinessItem(
                        title = "Background Audio Guardian (Accessibility)",
                        subtitle = if (isAccessibilityEnabled) "Service active • Microphone unlocked while locked" else "Tap to enable VoicemailAccessibilityService in Accessibility settings",
                        isComplete = isAccessibilityEnabled,
                        icon = Icons.Default.Accessibility,
                        onAction = {
                            openAccessibilitySettings(context)
                        }
                    )

                    // Item 3: Battery Optimization Exemption
                    ReadinessItem(
                        title = "Background Battery Survival",
                        subtitle = "Prevents Vivo FuntouchOS / OEM ROMs from killing InCallService",
                        isComplete = isBatteryExempt,
                        icon = Icons.Default.BatteryAlert,
                        onAction = {
                            requestBatteryExemption(context)
                        }
                    )

                    // Item 4: Telephony & Call Log Permissions
                    ReadinessItem(
                        title = "Call Log, Phone & Contacts",
                        subtitle = "Required for dialpad, dual-SIM detection, and caller lookup",
                        isComplete = isRuntimePermsGranted,
                        icon = Icons.Default.PhoneInTalk,
                        onAction = {
                            val perms = mutableListOf(
                                android.Manifest.permission.CALL_PHONE,
                                android.Manifest.permission.READ_PHONE_STATE,
                                android.Manifest.permission.READ_CALL_LOG,
                                android.Manifest.permission.WRITE_CALL_LOG,
                                android.Manifest.permission.READ_CONTACTS,
                                android.Manifest.permission.RECORD_AUDIO
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permsLauncher.launch(perms.toTypedArray())
                        }
                    )

                    // Item 5: Audio Engine Status & Permanent Digital Telephony Privilege
                    val item5Title = when {
                        isDigitalPrivilegeGranted -> "Audio Engine: Permanent Digital Telephony (Active)"
                        isShizukuReady -> "Audio Engine: Enhanced Shizuku Mode (UID 2000)"
                        else -> "Audio Engine: Standalone Acoustic Fallback"
                    }
                    val item5Subtitle = when {
                        isDigitalPrivilegeGranted -> "CAPTURE_AUDIO_OUTPUT & MODIFY_AUDIO_ROUTING permanent in packages.xml • Zero bleed digital injection"
                        isShizukuReady -> "UID 2000 active. Tap 'Auto-Grant' to permanently grant CAPTURE_AUDIO_OUTPUT to APK across reboots"
                        else -> "Running unprivileged acoustic mode. Tap to auto-grant digital privilege via Shizuku/Root or copy ADB command"
                    }
                    val item5ButtonText = if (isShizukuReady) "Auto-Grant" else "Grant / ADB"

                    ReadinessItem(
                        title = item5Title,
                        subtitle = item5Subtitle,
                        isComplete = isDigitalPrivilegeGranted,
                        icon = Icons.Default.PhoneInTalk,
                        buttonText = item5ButtonText,
                        onAction = {
                            VoicemailApplication.shizukuController.grantPermanentStandalonePermissions { success ->
                                onRefreshStatus()
                                if (success) {
                                    Toast.makeText(context, "Permanent Digital Telephony Privileges Granted!", Toast.LENGTH_LONG).show()
                                } else if (isShizukuReady) {
                                    Toast.makeText(context, "Attempted grant via Shizuku. Please check Shizuku status.", Toast.LENGTH_SHORT).show()
                                } else {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = ClipData.newPlainText("Voicemail ADB Command", ShizukuAudioController.ADB_ONE_TIME_GRANT_COMMAND)
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied 1-time ADB command to clipboard! Run in terminal via ADB or Shizuku.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )

                    // 1-Tap Copy ADB Command button (visible when permanent digital privileges not yet held)
                    if (!isDigitalPrivilegeGranted) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = ClipData.newPlainText("Voicemail ADB Command", ShizukuAudioController.ADB_ONE_TIME_GRANT_COMMAND)
                                clipboard?.setPrimaryClip(clip)
                                Toast.makeText(context, "Copied 1-time ADB grant command to clipboard!", Toast.LENGTH_LONG).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x26007AFF)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Copy 1-Time Permanent ADB Grant Command",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentBlue
                            )
                        }
                    }

                    // Vivo / OEM Background & Autostart Settings shortcut
                    Button(
                        onClick = {
                            SetupPreferences.openOemBackgroundSettings(context)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x2634C759)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = CallAcceptGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Vivo / OEM Autostart & Power Settings",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CallAcceptGreen
                        )
                    }

                    // Custom Voice Greeting Action
                    if (onOpenGreeting != null) {
                        Button(
                            onClick = onOpenGreeting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33007AFF)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Configure Custom Voice Greeting",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AccentBlue
                            )
                        }
                    }

                    // Self-Test Pipeline Button
                    Button(
                        onClick = {
                            context.sendBroadcast(
                                Intent(com.personal.voicemail.service.VoicemailSelfTestReceiver.ACTION_SELF_TEST)
                                    .setPackage(context.packageName)
                            )
                            Toast.makeText(context, "Running Voicemail Pipeline Self-Test...", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x335856D6)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = Color(0xFF5856D6),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Test Voicemail Pipeline (Self-Test)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF5856D6)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadinessItem(
    title: String,
    subtitle: String,
    isComplete: Boolean,
    icon: ImageVector,
    buttonText: String = "Enable",
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1AFFFFFF))
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isComplete) CallAcceptGreen else Color(0xFFFF9500),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (isComplete) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0x3334C759)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = CallAcceptGreen,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(text = buttonText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Live, strictly accurate check for default dialer role.
 * Never returns true from persistent preferences if the system reports false.
 */
fun checkDefaultDialerRole(context: Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java)
    val isHeld = if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
        true
    } else {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecomManager?.defaultDialerPackage == context.packageName
    }
    SetupPreferences.setDialerConfirmed(context, isHeld)
    return isHeld
}

fun requestDefaultDialerRole(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return
    if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        launcher.launch(intent)
    }
}

/**
 * Strict, accurate battery optimization exemption check:
 * 1. Checks live PowerManager.isIgnoringBatteryOptimizations
 * 2. Only during the first 120s after boot, falls back to previously confirmed state
 *    while PowerManager initializes.
 * 3. Does NOT check AppOps OP_RUN_ANY_IN_BACKGROUND (which is allowed by default and gives false positives).
 */
fun checkBatteryOptimizationExempt(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
        SetupPreferences.setBatteryExemptConfirmed(context, true)
        return true
    }

    val isRecentBoot = SystemClock.elapsedRealtime() < 120_000L
    if (isRecentBoot && SetupPreferences.isBatteryExemptConfirmed(context)) {
        return true
    }

    SetupPreferences.setBatteryExemptConfirmed(context, false)
    return false
}

fun requestBatteryExemption(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        SetupPreferences.openOemBackgroundSettings(context)
    }
}

/**
 * Strict live check for all telephony, call log, contacts, audio, and notification runtime permissions.
 */
fun checkRuntimePermissions(context: Context): Boolean {
    val permissions = mutableListOf(
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.WRITE_CALL_LOG,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.RECORD_AUDIO
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val allGranted = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    SetupPreferences.setRuntimePermsConfirmed(context, allGranted)
    return allGranted
}

/**
 * Multi-layer check for Accessibility Service:
 * 1. Live bound instance check ([VoicemailAccessibilityService.instance])
 * 2. AccessibilityManager.getEnabledAccessibilityServiceList
 * 3. Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES unflattened component parsing
 * 4. Boot-grace period (first 60s) fallback if previously verified.
 */
fun checkAccessibilityServiceEnabled(context: Context): Boolean {
    // 1. Live instance bound in RAM
    if (VoicemailAccessibilityService.instance != null) {
        SetupPreferences.setAccessibilityConfirmed(context, true)
        return true
    }

    // 2. Query AccessibilityManager for enabled services
    try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabledList = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val isPresent = enabledList.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo
                serviceInfo?.packageName.equals(context.packageName, ignoreCase = true) &&
                        serviceInfo?.name?.contains("VoicemailAccessibilityService", ignoreCase = true) == true
            }
            if (isPresent) {
                SetupPreferences.setAccessibilityConfirmed(context, true)
                return true
            }
        }
    } catch (ignored: Throwable) {}

    // 3. Parse Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    try {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (!enabledServices.isNullOrBlank()) {
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null &&
                    enabledComponent.packageName.equals(context.packageName, ignoreCase = true) &&
                    enabledComponent.className.contains("VoicemailAccessibilityService", ignoreCase = true)
                ) {
                    SetupPreferences.setAccessibilityConfirmed(context, true)
                    return true
                }
            }
        }
    } catch (ignored: Throwable) {}

    // 4. Fallback only during the first 60 seconds after boot while system binds services
    val isRecentBoot = SystemClock.elapsedRealtime() < 60_000L
    if (isRecentBoot && SetupPreferences.isAccessibilityConfirmed(context)) {
        return true
    }

    SetupPreferences.setAccessibilityConfirmed(context, false)
    return false
}

fun openAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (t: Throwable) {
        android.util.Log.e("SystemReadiness", "Failed opening accessibility settings", t)
    }
}
