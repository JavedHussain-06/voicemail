package com.personal.voicemail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Single source of truth for voicemail storage, combining Room persistence
 * with file-system audio management, JSON export/import backup, and immediate StateFlow updates.
 */
class VoicemailRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        private const val TAG = "VoicemailRepository"
        private const val JSON_FILE_NAME = "voicemails.json"
        private const val JSON_BACKUP_NAME = "voicemail_backup.json"

        @Volatile
        private var INSTANCE: VoicemailRepository? = null

        fun getInstance(context: Context): VoicemailRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoicemailRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val repositoryScope = CoroutineScope(ioDispatcher)
    private val database = VoicemailDatabase.getInstance(context)
    private val dao = database.voicemailDao()

    private val _voicemails = MutableStateFlow<List<VoicemailEntity>>(emptyList())
    val voicemails: StateFlow<List<VoicemailEntity>> = _voicemails.asStateFlow()

    val allVoicemails: Flow<List<VoicemailEntity>> = dao.getAllVoicemails()
    val unreadCount: Flow<Int> = dao.getUnreadCount()

    init {
        // Collect Room Flow into _voicemails StateFlow
        repositoryScope.launch {
            dao.getAllVoicemails().collect { list ->
                _voicemails.value = list
            }
        }
    }

    /**
     * Reloads all voicemails from database and JSON storage into memory.
     */
    fun reloadFromDisk() {
        repositoryScope.launch {
            try {
                val dbList = dao.getAllVoicemailsSync()
                if (dbList.isNotEmpty()) {
                    _voicemails.value = dbList
                } else {
                    // Check if JSON file exists
                    val jsonFile = File(context.filesDir, JSON_FILE_NAME)
                    if (jsonFile.exists() && jsonFile.length() > 2) {
                        val parsed = parseVoicemailsFromJson(jsonFile)
                        if (parsed.isNotEmpty()) {
                            parsed.forEach { dao.insert(it) }
                            _voicemails.value = parsed
                        }
                    }
                }
                Log.d(TAG, "reloadFromDisk completed. Total items: ${_voicemails.value.size}")
            } catch (t: Throwable) {
                Log.e(TAG, "Error in reloadFromDisk", t)
            }
        }
    }

    suspend fun getVoicemailById(id: Long): VoicemailEntity? = withContext(ioDispatcher) {
        dao.getVoicemailById(id)
    }

    /**
     * Persists a newly completed voicemail entity to Room, writes to voicemails.json,
     * and immediately updates the StateFlow so the UI updates instantly.
     */
    suspend fun addVoicemail(voicemail: VoicemailEntity): Long = withContext(ioDispatcher) {
        val insertedId = dao.insert(voicemail)
        val entityWithId = voicemail.copy(id = insertedId)
        val currentList = _voicemails.value.toMutableList()
        currentList.removeAll { it.id == insertedId }
        currentList.add(0, entityWithId)
        _voicemails.value = currentList

        Log.i(TAG, "Voicemail added with ID: $insertedId, caller: ${voicemail.phoneNumber}")
        exportToJsonFiles()
        insertedId
    }

    suspend fun saveVoicemail(voicemail: VoicemailEntity): Long = addVoicemail(voicemail)

    suspend fun deleteVoicemail(voicemail: VoicemailEntity) = withContext(ioDispatcher) {
        if (voicemail.audioFilePath.isNotBlank()) {
            val audioFile = File(voicemail.audioFilePath)
            if (audioFile.exists()) {
                val deleted = audioFile.delete()
                Log.i(TAG, "Deleted associated audio file (${audioFile.name}): $deleted")
            }
        }
        dao.delete(voicemail)
        val currentList = _voicemails.value.toMutableList()
        currentList.removeAll { it.id == voicemail.id }
        _voicemails.value = currentList
        exportToJsonFiles()
    }

    suspend fun deleteVoicemailById(id: Long) = withContext(ioDispatcher) {
        val item = dao.getVoicemailById(id)
        if (item != null) {
            deleteVoicemail(item)
        } else {
            dao.deleteById(id)
            val currentList = _voicemails.value.toMutableList()
            currentList.removeAll { it.id == id }
            _voicemails.value = currentList
            exportToJsonFiles()
        }
    }

    suspend fun markAsRead(id: Long) = withContext(ioDispatcher) {
        dao.markAsRead(id)
        val currentList = _voicemails.value.map {
            if (it.id == id) it.copy(isRead = true) else it
        }
        _voicemails.value = currentList
    }

    suspend fun updateTranscript(id: Long, transcript: String) = withContext(ioDispatcher) {
        dao.updateTranscript(id, transcript)
        val currentList = _voicemails.value.map {
            if (it.id == id) it.copy(transcript = transcript) else it
        }
        _voicemails.value = currentList
        exportToJsonFiles()
    }

    /**
     * Exports all voicemails and transcripts to files/voicemails.json and backup.
     */
    private suspend fun exportToJsonFiles() = withContext(ioDispatcher) {
        try {
            val jsonArray = JSONArray()
            val list = dao.getAllVoicemailsSync()

            for (c in list) {
                val obj = JSONObject().apply {
                    put("id", c.id)
                    put("callerName", c.callerName ?: "")
                    put("phoneNumber", c.phoneNumber)
                    put("simSlotIndex", c.simSlotIndex)
                    put("simDisplayName", c.simDisplayName)
                    put("timestamp", c.timestamp)
                    put("durationSeconds", c.durationSeconds)
                    put("transcript", c.transcript)
                    put("audioFilePath", c.audioFilePath)
                    put("isRead", c.isRead)
                    put("isPickedUp", c.isPickedUp)
                }
                jsonArray.put(obj)
            }

            val jsonContent = jsonArray.toString(2).toByteArray(Charsets.UTF_8)

            // Write to files/voicemails.json
            val mainFile = File(context.filesDir, JSON_FILE_NAME)
            FileOutputStream(mainFile).use { fos ->
                fos.write(jsonContent)
                fos.flush()
            }

            // Write backup
            val backupFile = File(context.filesDir, JSON_BACKUP_NAME)
            FileOutputStream(backupFile).use { fos ->
                fos.write(jsonContent)
                fos.flush()
            }

            Log.d(TAG, "Exported ${list.size} voicemails to $JSON_FILE_NAME and $JSON_BACKUP_NAME")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to export JSON backup: ${t.message}")
        }
    }

    private fun parseVoicemailsFromJson(file: File): List<VoicemailEntity> {
        val result = mutableListOf<VoicemailEntity>()
        val text = file.readText(Charsets.UTF_8)
        val array = JSONArray(text)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val entity = VoicemailEntity(
                id = obj.optLong("id", 0L),
                callerName = obj.optString("callerName").takeIf { it.isNotBlank() },
                phoneNumber = obj.optString("phoneNumber"),
                simSlotIndex = obj.optInt("simSlotIndex", 0),
                simDisplayName = obj.optString("simDisplayName", "SIM 1"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                durationSeconds = obj.optInt("durationSeconds", 0),
                transcript = obj.optString("transcript", ""),
                audioFilePath = obj.optString("audioFilePath", ""),
                isRead = obj.optBoolean("isRead", false),
                isPickedUp = obj.optBoolean("isPickedUp", false)
            )
            result.add(entity)
        }
        return result
    }
}
