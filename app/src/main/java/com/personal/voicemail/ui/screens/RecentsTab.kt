package com.personal.voicemail.ui.screens

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.personal.voicemail.ui.theme.AccentBlue
import com.personal.voicemail.ui.theme.DarkBackground
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
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var callLogs by remember { mutableStateOf<List<CallLogEntry>>(emptyList()) }
    var filterMissedOnly by remember { mutableStateOf(false) }

    fun refreshLogs() {
        coroutineScope.launch {
            callLogs = fetchCallLogs(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshLogs()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                refreshLogs()
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshLogs()
            }
        }

        runCatching {
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                observer
            )
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            runCatching { context.contentResolver.unregisterContentObserver(observer) }
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    val filteredList = if (filterMissedOnly) {
        callLogs.filter { it.type == CallLog.Calls.MISSED_TYPE || it.type == CallLog.Calls.REJECTED_TYPE }
    } else {
        callLogs
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // iOS Top Segmented Control (All | Missed)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1C1C1E))
                    .padding(2.dp)
            ) {
                // "All" segment
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (!filterMissedOnly) Color(0xFF636366) else Color.Transparent)
                        .clickable { filterMissedOnly = false }
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "All",
                        fontSize = 13.sp,
                        fontWeight = if (!filterMissedOnly) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (!filterMissedOnly) Color.White else Color(0xFF8E8E93)
                    )
                }

                // "Missed" segment
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (filterMissedOnly) Color(0xFF636366) else Color.Transparent)
                        .clickable { filterMissedOnly = true }
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Missed",
                        fontSize = 13.sp,
                        fontWeight = if (filterMissedOnly) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (filterMissedOnly) Color.White else Color(0xFF8E8E93)
                    )
                }
            }
        }

        // iOS Large Navigation Title
        Text(
            text = "Recents",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        if (filteredList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1C1C1E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = if (filterMissedOnly) "No Missed Calls" else "No Recents",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredList, key = { _, call -> call.id }) { index, call ->
                    IOSRecentsRow(
                        entry = call,
                        onClick = { onCallNumber(call.number) },
                        onInfoClick = { onCallNumber(call.number) }
                    )
                    // Indented hairline separator like iOS
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = Color(0x38545458)
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
private fun IOSRecentsRow(
    entry: CallLogEntry,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val isMissed = entry.type == CallLog.Calls.MISSED_TYPE || entry.type == CallLog.Calls.REJECTED_TYPE
    val isOutgoing = entry.type == CallLog.Calls.OUTGOING_TYPE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Contact Name / Number + Call Type
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            val titleText = entry.name ?: entry.number
            Text(
                text = titleText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isMissed) Color(0xFFFF453A) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                if (isOutgoing) {
                    Icon(
                        imageVector = Icons.Default.CallMade,
                        contentDescription = null,
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier
                            .size(13.dp)
                            .padding(end = 4.dp)
                    )
                }

                val subtext = if (!entry.name.isNullOrBlank()) {
                    if (isMissed) "${entry.number} • Missed Call" else entry.number
                } else {
                    when (entry.type) {
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing Call"
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "Missed Call"
                        else -> "Incoming Call"
                    }
                }

                Text(
                    text = subtext,
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Right Column: Date/Time + iOS Info Button (i)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = formatLogDate(entry.date),
                fontSize = 14.sp,
                color = Color(0xFF8E8E93)
            )

            Spacer(modifier = Modifier.width(10.dp))

            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "Details",
                    tint = AccentBlue,
                    modifier = Modifier.size(22.dp)
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
        val diffDays = (now.time - dateMs) / (1000 * 60 * 60 * 24)
        if (diffDays < 7) {
            SimpleDateFormat("EEEE", Locale.getDefault()).format(date)
        } else {
            SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(date)
        }
    }
}
