package com.personal.voicemail.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VoicemailDao {

    @Query("SELECT * FROM voicemails ORDER BY timestamp DESC")
    fun getAllVoicemails(): Flow<List<VoicemailEntity>>

    @Query("SELECT * FROM voicemails ORDER BY timestamp DESC")
    suspend fun getAllVoicemailsSync(): List<VoicemailEntity>

    @Query("SELECT * FROM voicemails WHERE id = :id LIMIT 1")
    suspend fun getVoicemailById(id: Long): VoicemailEntity?

    @Query("SELECT COUNT(*) FROM voicemails WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(voicemail: VoicemailEntity): Long

    @Update
    suspend fun update(voicemail: VoicemailEntity)

    @Delete
    suspend fun delete(voicemail: VoicemailEntity)

    @Query("DELETE FROM voicemails WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE voicemails SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("UPDATE voicemails SET transcript = :transcript WHERE id = :id")
    suspend fun updateTranscript(id: Long, transcript: String)
}
