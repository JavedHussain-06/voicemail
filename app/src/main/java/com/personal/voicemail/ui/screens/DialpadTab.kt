package com.personal.voicemail.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.CallAcceptGreen
import com.personal.voicemail.ui.theme.DarkBackground

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
    val clipboardManager = LocalClipboardManager.current
    var enteredNumber by remember { mutableStateOf(initialNumber) }
    var simOptions by remember { mutableStateOf<List<SimAccountOption>>(emptyList()) }
    var selectedSimIndex by remember { mutableIntStateOf(0) }

    val callPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val selectedSim = simOptions.getOrNull(selectedSimIndex) ?: simOptions.firstOrNull()
            placeOutgoingCall(context, enteredNumber, selectedSim?.phoneAccountHandle)
        } else {
            Toast.makeText(context, "Call permission required to place phone calls", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(initialNumber) {
        if (initialNumber.isNotBlank()) {
            enteredNumber = initialNumber
        }
    }

    LaunchedEffect(Unit) {
        simOptions = querySimAccounts(context)
    }

    val selectedSim = simOptions.getOrNull(selectedSimIndex) ?: simOptions.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {

        // Top Area: Dialed Number Display, Sub-actions & Dual SIM pill
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Dialed Number
            val fontSize = when {
                enteredNumber.length > 14 -> 24.sp
                enteredNumber.length > 10 -> 30.sp
                else -> 38.sp
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = enteredNumber,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Normal,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            // Subtitle action ("Add Number" or blank)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (enteredNumber.isNotBlank()) {
                    Text(
                        text = "Add Number",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentBlue,
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(enteredNumber))
                            Toast.makeText(context, "Number copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // Dual-SIM Selector Pill (Only if 2+ SIMs detected)
            if (simOptions.size >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1C1C1E))
                        .clickable {
                            selectedSimIndex = (selectedSimIndex + 1) % simOptions.size
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${selectedSim?.displayName ?: "SIM 1"} ▾",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }
        }

        // iPhone Dialpad Keypad Grid (Rows 1-4)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Row 1: 1, 2, 3
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IOSKeypadButton(digit = "1", letters = "", onClick = { enteredNumber += "1" })
                IOSKeypadButton(digit = "2", letters = "A B C", onClick = { enteredNumber += "2" })
                IOSKeypadButton(digit = "3", letters = "D E F", onClick = { enteredNumber += "3" })
            }

            // Row 2: 4, 5, 6
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IOSKeypadButton(digit = "4", letters = "G H I", onClick = { enteredNumber += "4" })
                IOSKeypadButton(digit = "5", letters = "J K L", onClick = { enteredNumber += "5" })
                IOSKeypadButton(digit = "6", letters = "M N O", onClick = { enteredNumber += "6" })
            }

            // Row 3: 7, 8, 9
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IOSKeypadButton(digit = "7", letters = "P Q R S", onClick = { enteredNumber += "7" })
                IOSKeypadButton(digit = "8", letters = "T U V", onClick = { enteredNumber += "8" })
                IOSKeypadButton(digit = "9", letters = "W X Y Z", onClick = { enteredNumber += "9" })
            }

            // Row 4: *, 0 (+), #
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IOSKeypadButton(digit = "*", letters = "", isGlyph = true, onClick = { enteredNumber += "*" })
                IOSKeypadButton(
                    digit = "0",
                    letters = "+",
                    onClick = { enteredNumber += "0" },
                    onLongClick = { enteredNumber += "+" }
                )
                IOSKeypadButton(digit = "#", letters = "", isGlyph = true, onClick = { enteredNumber += "#" })
            }

            // Row 5: Action Row (Spacer, Call Button, Backspace)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Empty to match 76dp width
                Box(modifier = Modifier.size(76.dp))

                // Center Column: Iconic iOS Call Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(CallAcceptGreen)
                        .clickable {
                            if (enteredNumber.isNotBlank()) {
                                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                                    placeOutgoingCall(
                                        context = context,
                                        number = enteredNumber,
                                        accountHandle = selectedSim?.phoneAccountHandle
                                    )
                                } else {
                                    callPermissionLauncher.launch(android.Manifest.permission.CALL_PHONE)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Call",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Right Column: Backspace Button
                Box(
                    modifier = Modifier.size(76.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (enteredNumber.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = {
                                        if (enteredNumber.isNotEmpty()) {
                                            enteredNumber = enteredNumber.dropLast(1)
                                        }
                                    },
                                    onLongClick = {
                                        enteredNumber = ""
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = "Delete",
                                tint = Color(0xFF8E8E93),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom space so it rests comfortably above navigation bar
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IOSKeypadButton(
    digit: String,
    letters: String,
    isGlyph: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val backgroundColor = if (isPressed) Color(0xFF48484A) else Color(0xFF2C2C2E)

    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = digit,
                fontSize = if (isGlyph && digit == "*") 36.sp else if (isGlyph) 28.sp else 34.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White,
                lineHeight = if (isGlyph) 32.sp else 36.sp
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF8E8E93),
                    letterSpacing = 1.5.sp
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
