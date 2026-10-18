package com.personal.voicemail.ui.screens

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.personal.voicemail.service.ShizukuAudioController
import com.personal.voicemail.ui.components.GlassmorphicCard
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.CallAcceptGreen
import com.personal.voicemail.ui.theme.CallEndRed
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.ui.theme.TextSecondary

@Composable
fun SystemReadinessBanner(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shizukuController = remember { ShizukuAudioController.getInstance(context) }

    val isShizukuRunning by shizukuController.isShizukuInstalledAndRunning.collectAsState()
    val isShizukuPermGranted by shizukuController.isPermissionGranted.collectAsState()

    var isDefaultDialer by remember { mutableStateOf(checkDefaultDialerRole(context)) }
    var isBatteryExempt by remember { mutableStateOf(checkBatteryOptimizationExempt(context)) }
    var isRuntimePermsGranted by remember { mutableStateOf(checkRuntimePermissions(context)) }
    var isExpanded by remember { mutableStateOf(false) }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDefaultDialer = checkDefaultDialerRole(context)
    }

    val permsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        isRuntimePermsGranted = checkRuntimePermissions(context)
    }

    LaunchedEffect(Unit) {
        shizukuController.updateShizukuStatus()
        isDefaultDialer = checkDefaultDialerRole(context)
        isBatteryExempt = checkBatteryOptimizationExempt(context)
        isRuntimePermsGranted = checkRuntimePermissions(context)
    }

    val allReady = isDefaultDialer && isShizukuRunning && isShizukuPermGranted && isBatteryExempt && isRuntimePermsGranted

    GlassmorphicCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        backgroundColor = if (allReady) Color(0x1F34C759) else Color(0x26FF9500)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (allReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (allReady) CallAcceptGreen else Color(0xFFFF9500),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (allReady) "System Ready for Live Voicemail" else "Setup Required for Live Voicemail",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = if (allReady) "Dialer active • UID 2000 connected" else "Tap to review required system permissions",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded || !allReady,
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

                    // Item 2: Shizuku Privileged Shell (UID 2000)
                    ReadinessItem(
                        title = "Shizuku Audio Engine (UID 2000)",
                        subtitle = if (!isShizukuRunning) "Start Shizuku service via ADB or Wireless Debugging" else "Grants CAPTURE_AUDIO_OUTPUT to tap downlink",
                        isComplete = isShizukuRunning && isShizukuPermGranted,
                        icon = Icons.Default.Security,
                        onAction = {
                            shizukuController.requestShizukuPermission()
                        }
                    )

                    // Item 3: Battery Optimization Exemption (FuntouchOS)
                    ReadinessItem(
                        title = "Background Battery Survival",
                        subtitle = "Prevents Vivo FuntouchOS from killing background InCallService",
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
                            permsLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.CALL_PHONE,
                                    android.Manifest.permission.READ_PHONE_STATE,
                                    android.Manifest.permission.READ_CALL_LOG,
                                    android.Manifest.permission.WRITE_CALL_LOG,
                                    android.Manifest.permission.READ_CONTACTS,
                                    android.Manifest.permission.RECORD_AUDIO,
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Button(
                        onClick = {
                            context.sendBroadcast(
                                Intent(com.personal.voicemail.service.VoicemailSelfTestReceiver.ACTION_SELF_TEST)
                                    .setPackage(context.packageName)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x33007AFF)),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    ) {
                        Text(
                            text = "Test Voicemail Pipeline (Self-Test)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AccentBlue
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
                Text(text = "Enable", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun checkDefaultDialerRole(context: Context): Boolean {
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
    return roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
}

private fun requestDefaultDialerRole(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>
) {
    val roleManager = context.getSystemService(RoleManager::class.java) ?: return
    if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        launcher.launch(intent)
    }
}

private fun checkBatteryOptimizationExempt(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestBatteryExemption(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        context.startActivity(intent)
    }
}

private fun checkRuntimePermissions(context: Context): Boolean {
    val permissions = listOf(
        android.Manifest.permission.CALL_PHONE,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.READ_CALL_LOG,
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.RECORD_AUDIO
    )
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
