package com.personal.voicemail.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.personal.voicemail.service.TelecomCallManager
import com.personal.voicemail.ui.theme.PersonalVoicemailTheme

/**
 * Full-screen lockscreen activity displaying incoming calls and real-time Live Voicemail.
 * Configured with flags to wake the screen and present above Android keyguards.
 */
class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Lockscreen Presentation & Display Turn-On
        configureLockscreenBypass()

        setContent {
            PersonalVoicemailTheme {
                val screeningState by TelecomCallManager.screeningState.collectAsState()

                LaunchedEffect(screeningState) {
                    if (screeningState == TelecomCallManager.CallScreeningState.IDLE) {
                        finishAndRemoveTask()
                    }
                }

                InCallScreen(
                    onDismiss = { finishAndRemoveTask() }
                )
            }
        }
    }

    private fun configureLockscreenBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent accidental back press from hanging up an active call
        // User must tap the explicit End Call button or minimize to background
        moveTaskToBack(true)
    }
}
