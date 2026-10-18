package com.personal.voicemail.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.personal.voicemail.ui.theme.CallAcceptGreen
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.GlassSurface
import com.personal.voicemail.ui.theme.Sim1Color
import com.personal.voicemail.ui.theme.Sim2Color
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.ui.theme.TextSecondary

data class SimAccountOption(
    val slotIndex: Int,
    val displayName: String,
    val phoneAccountHandle: PhoneAccountHandle?
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadTab(
    initialNumber: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var enteredNumber by remember { mutableStateOf(initialNumber) }
    var simOptions by remember { mutableStateOf<List<SimAccountOption>>(emptyList()) }

    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotBlank()) {
            enteredNumber = initialNumber
        }
    }

    LaunchedEffect(Unit) {
        simOptions = querySimAccounts(context)
    }

    val keypadRows = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to "")
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        // Display Area: Typed Phone Number with Backspace
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = enteredNumber,
                    fontSize = if (enteredNumber.length > 12) 26.sp else 34.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )

                if (enteredNumber.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            if (enteredNumber.isNotEmpty()) {
                                enteredNumber = enteredNumber.dropLast(1)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .combinedClickable(
                                onClick = {
                                    if (enteredNumber.isNotEmpty()) {
                                        enteredNumber = enteredNumber.dropLast(1)
                                    }
                                },
                                onLongClick = {
                                    enteredNumber = ""
                                }
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Backspace,
                            contentDescription = "Backspace",
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // T9 Numeric Dialpad Grid
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            for (row in keypadRows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for ((digit, sub) in row) {
                        DialpadKey(
                            digit = digit,
                            subtext = sub,
                            onClick = { enteredNumber += digit },
                            onLongClick = {
                                if (digit == "0") {
                                    enteredNumber += "+"
                                }
                            }
                        )
                    }
                }
            }
        }

        // Bottom Call Triggers: Dual-SIM Dial Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (simOptions.size >= 2) {
                // Dual SIM selector buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    for (sim in simOptions.take(2)) {
                        val isSim1 = sim.slotIndex == 0
                        val color = if (isSim1) Sim1Color else Sim2Color

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(28.dp))
                                .background(color)
                                .combinedClickable(
                                    onClick = {
                                        if (enteredNumber.isNotBlank()) {
                                            placeOutgoingCall(context, enteredNumber, sim.phoneAccountHandle)
                                        }
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SIM ${sim.slotIndex + 1}",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                // Single Call Button
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(CallAcceptGreen)
                        .combinedClickable(
                            onClick = {
                                if (enteredNumber.isNotBlank()) {
                                    placeOutgoingCall(
                                        context,
                                        enteredNumber,
                                        simOptions.firstOrNull()?.phoneAccountHandle
                                    )
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Place Call",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialpadKey(
    digit: String,
    subtext: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .background(GlassSurface)
            .border(1.dp, GlassBorder, CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = digit,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            if (subtext.isNotEmpty()) {
                Text(
                    text = subtext,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

private fun querySimAccounts(context: Context): List<SimAccountOption> {
    val results = ArrayList<SimAccountOption>()
    val telecomManager = context.getSystemService(TelecomManager::class.java)
    val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)

    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        return listOf(SimAccountOption(0, "SIM 1", null))
    }

    try {
        val activeSubs = subscriptionManager?.activeSubscriptionInfoList ?: emptyList<SubscriptionInfo>()
        val callHandles = telecomManager?.callCapablePhoneAccounts ?: emptyList<PhoneAccountHandle>()

        if (activeSubs.isNotEmpty()) {
            for (sub in activeSubs) {
                val matchedHandle = callHandles.firstOrNull { it.id == sub.subscriptionId.toString() || it.id == sub.iccId }
                val name = sub.displayName?.toString()?.ifBlank { sub.carrierName?.toString() } ?: "SIM ${sub.simSlotIndex + 1}"
                results.add(
                    SimAccountOption(
                        slotIndex = sub.simSlotIndex,
                        displayName = name,
                        phoneAccountHandle = matchedHandle
                    )
                )
            }
        } else {
            results.add(SimAccountOption(0, "SIM 1", callHandles.firstOrNull()))
        }
    } catch (e: Exception) {
        results.add(SimAccountOption(0, "SIM 1", null))
    }

    return results.sortedBy { it.slotIndex }
}

private fun placeOutgoingCall(
    context: Context,
    number: String,
    accountHandle: PhoneAccountHandle?
) {
    val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return
    val uri = Uri.fromParts("tel", number, null)
    val extras = Bundle().apply {
        if (accountHandle != null) {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
        }
    }

    try {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            telecomManager.placeCall(uri, extras)
        }
    } catch (e: Exception) {
        android.util.Log.e("DialpadTab", "Failed to place call to $number", e)
    }
}
