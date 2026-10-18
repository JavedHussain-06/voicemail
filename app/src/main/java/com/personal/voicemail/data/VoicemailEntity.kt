package com.personal.voicemail.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a saved Live Voicemail recording with full transcript and telephony metadata.
 */
@Entity(tableName = "voicemails")
data class VoicemailEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val callerName: String? = null,
    val phoneNumber: String,
    val simSlotIndex: Int = 0,
    val simDisplayName: String = "SIM 1",
    val timestamp: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val transcript: String = "",
    val audioFilePath: String = "",
    val isRead: Boolean = false,
    val isPickedUp: Boolean = false
)
