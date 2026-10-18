package com.personal.voicemail

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class VoicemailApplication : Application() {

    companion object {
        const val TAG = "VoicemailApp"
        const val CHANNEL_INCALL_ID = "channel_live_voicemail_incall"
        const val CHANNEL_VOICEMAIL_NOTIFICATIONS_ID = "channel_voicemail_recordings"

        private lateinit var instance: VoicemailApplication

        fun getAppContext(): Context = instance.applicationContext

        lateinit var shizukuController: com.personal.voicemail.service.ShizukuAudioController
            private set
        lateinit var voicemailRepository: com.personal.voicemail.data.VoicemailRepository
            private set
        lateinit var transcriber: com.personal.voicemail.recognition.LiveVoicemailTranscriber
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Bypass Hidden API Restrictions for Android 12 (API 31) through Android 15 (API 35)
        exemptHiddenApis()

        // 2. Initialize Process-Wide Shared Singletons
        shizukuController = com.personal.voicemail.service.ShizukuAudioController.getInstance(this)
        voicemailRepository = com.personal.voicemail.data.VoicemailRepository.getInstance(this)
        transcriber = com.personal.voicemail.recognition.LiveVoicemailTranscriber.getInstance(this)

        // 3. Initialize High-Priority Notification Channels for InCallService and Voicemail Alerts
        createNotificationChannels()

        // 4. Guarantee default greeting WAV file is synthesized on disk
        try {
            val greeting = com.personal.voicemail.audio.GreetingGenerator.getActiveGreetingFile(this)
            Log.i(TAG, "Greeting WAV initialized at: ${greeting.absolutePath} (${greeting.length()} bytes)")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize greeting WAV", t)
        }

        // 5. Trigger spoken voice greeting TTS synthesis in background
        com.personal.voicemail.audio.GreetingGenerator.generateVoiceGreetingTts(this)

        Log.i(TAG, "VoicemailApplication initialized. PID: ${android.os.Process.myPid()}, UID: ${android.os.Process.myUid()}")
    }

    private fun exemptHiddenApis() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val success = HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "HiddenApiBypass applied successfully in Application process: $success")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to apply HiddenApiBypass in Application process", t)
            }
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return

        // Channel for Incoming Call Full-Screen Intent and Foreground InCallService
        val inCallChannel = NotificationChannel(
            CHANNEL_INCALL_ID,
            getString(R.string.notification_channel_incall),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Maintains the active call and live screening status bar notifications"
            lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
            setSound(null, null) // InCallService handles telephony audio routing directly
            enableVibration(false)
        }

        // Channel for New Voicemail Transcript Notifications
        val voicemailChannel = NotificationChannel(
            CHANNEL_VOICEMAIL_NOTIFICATIONS_ID,
            getString(R.string.notification_channel_voicemail),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts the user when a caller completes a live voicemail recording"
            enableVibration(true)
        }

        notificationManager.createNotificationChannel(inCallChannel)
        notificationManager.createNotificationChannel(voicemailChannel)
    }
}
