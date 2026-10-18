package com.personal.voicemail.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.personal.voicemail.ui.components.GlassmorphicCard
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.CallAcceptGreen
import com.personal.voicemail.ui.theme.CallEndRed
import com.personal.voicemail.ui.theme.GlassBorder
import com.personal.voicemail.ui.theme.GlassSurface
import com.personal.voicemail.ui.theme.TextPrimary
import com.personal.voicemail.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CallLogEntry(
    val id: Long,
    val number: String,
    val name: String?,
    val date: Long,
    val durationSec: Long,
    val type: Int,
    val simAccountId: String?
)

@Composable
fun RecentsTab(
    onCallNumber: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var callLogs by remember { mutableStateOf<List<CallLogEntry>>(emptyList()) }
    var filterMissedOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        callLogs = fetchCallLogs(context)
    }

    val filteredList = if (filterMissedOnly) {
        callLogs.filter { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
    } else {
        callLogs
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Header Row with Filter Segment (All vs Missed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Recents",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            // Segmented Switch
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassSurface)
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (!filterMissedOnly) Color(0x4DFFFFFF) else Color.Transparent)
                        .clickable { filterMissedOnly = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "All",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (!filterMissedOnly) Color.White else TextSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (filterMissedOnly) Color(0x4DFFFFFF) else Color.Transparent)
                        .clickable { filterMissedOnly = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Missed",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (filterMissedOnly) Color.White else TextSecondary
                    )
                }
            }
        }

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(GlassSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (filterMissedOnly) "No Missed Calls" else "No Recent Calls",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredList, key = { it.id }) { call ->
                    CallLogItemCard(
                        entry = call,
                        onClick = { onCallNumber(call.number) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun CallLogItemCard(
    entry: CallLogEntry,
    onClick: () -> Unit
) {
    val isMissed = entry.type == CallLog.Calls.MISSED_TYPE || entry.type == CallLog.Calls.REJECTED_TYPE
    val icon = when (entry.type) {
        CallLog.Calls.OUTGOING_TYPE -> Icons.Default.CallMade
        CallLog.Calls.MISSED_TYPE -> Icons.Default.CallMissed
        CallLog.Calls.REJECTED_TYPE -> Icons.Default.CallMissed
        else -> Icons.Default.CallReceived
    }
    val iconTint = when (entry.type) {
        CallLog.Calls.OUTGOING_TYPE -> AccentBlue
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CallEndRed
        else -> CallAcceptGreen
    }

    GlassmorphicCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        cornerRadius = 16.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = entry.name ?: entry.number,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isMissed) CallEndRed else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!entry.name.isNullOrBlank()) {
                        Text(
                            text = entry.number,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatLogDate(entry.date),
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = "Callback",
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private suspend fun fetchCallLogs(context: Context): List<CallLogEntry> = withContext(Dispatchers.IO) {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
        return@withContext emptyList()
    }

    val result = ArrayList<CallLogEntry>()
    val projection = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.TYPE,
        CallLog.Calls.PHONE_ACCOUNT_ID
    )

    try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT 100"
        )?.use { cursor ->
            val colId = cursor.getColumnIndex(CallLog.Calls._ID)
            val colNumber = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val colName = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val colDate = cursor.getColumnIndex(CallLog.Calls.DATE)
            val colDuration = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val colType = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val colSimId = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(colId)
                val number = cursor.getString(colNumber) ?: "Unknown"
                val name = cursor.getString(colName)
                val date = cursor.getLong(colDate)
                val duration = cursor.getLong(colDuration)
                val type = cursor.getInt(colType)
                val simId = if (colSimId != -1) cursor.getString(colSimId) else null

                result.add(
                    CallLogEntry(
                        id = id,
                        number = number,
                        name = name,
                        date = date,
                        durationSec = duration,
                        type = type,
                        simAccountId = simId
                    )
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("RecentsTab", "Error reading call logs", e)
    }

    result
}

private fun formatLogDate(dateMs: Long): String {
    val date = Date(dateMs)
    val now = Date()
    val cal1 = java.util.Calendar.getInstance().apply { time = date }
    val cal2 = java.util.Calendar.getInstance().apply { time = now }

    return if (cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
        cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
    } else {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(date)
    }
}
