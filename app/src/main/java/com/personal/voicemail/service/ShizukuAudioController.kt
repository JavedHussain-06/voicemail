package com.personal.voicemail.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.personal.voicemail.BuildConfig
import com.personal.voicemail.ipc.IAudioChunkCallback
import com.personal.voicemail.ipc.IVoicemailAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the IPC connection lifecycle between the application and the privileged
 * VoicemailUserService running under UID 2000 (com.android.shell) via Shizuku or internal root daemon.
 *
 * Implements Built-in Daemon Auto-Bootstrap (no external Shizuku app required if root or local
 * shell daemon is available) and non-blocking background connection resilience.
 */
class ShizukuAudioController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuAudioController"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val BASE_RECONNECT_DELAY_MS = 1000L

        @Volatile
        private var INSTANCE: ShizukuAudioController? = null

        fun getInstance(context: Context): ShizukuAudioController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShizukuAudioController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val controllerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var userService: IVoicemailAudioService? = null

    private val _isShizukuInstalledAndRunning = MutableStateFlow(false)
    val isShizukuInstalledAndRunning: StateFlow<Boolean> = _isShizukuInstalledAndRunning.asStateFlow()

    private val _isPermissionGranted = MutableStateFlow(false)
    val isPermissionGranted: StateFlow<Boolean> = _isPermissionGranted.asStateFlow()

    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound: StateFlow<Boolean> = _isServiceBound.asStateFlow()

    val isBound: Boolean
        get() = _isServiceBound.value && userService != null

    /**
     * Exposes whether an enhanced privileged telephony daemon is active (via Shizuku UID 2000
     * or root-granted CAPTURE_AUDIO_OUTPUT). Standalone mode never requires this to be true.
     */
    val isPrivilegedDaemonActive: Boolean
        get() = isBound || (context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED)

    /**
     * Ensures the privileged Shizuku UserService is bound before executing audio commands.
     * Suspends until the service is connected or timeout expires.
     */
    suspend fun ensureBound(timeoutMs: Long = 3000L): Boolean {
        if (isBound) return true
        Log.i(TAG, "ensureBound: UserService not yet bound. Initiating bind with timeout ${timeoutMs}ms...")
        bindUserService()
        val connected = withTimeoutOrNull(timeoutMs) {
            _isServiceBound.first { it && userService != null }
            true
        } ?: false
        Log.i(TAG, "ensureBound finished: isBound=$isBound (result=$connected)")
        return isBound
    }

    private var reconnectAttempts = 0
    private val isConnecting = AtomicBoolean(false)
    private val pendingConnectCallbacks = mutableListOf<(IVoicemailAudioService?) -> Unit>()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received from ShizukuProvider")
        updateShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died. Shizuku manager service may have been terminated.")
        _isShizukuInstalledAndRunning.value = false
        _isPermissionGranted.value = false
        _isServiceBound.value = false
        userService = null
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "Shizuku permission request result ($requestCode): granted=$granted")
            _isPermissionGranted.value = granted
            if (granted) {
                bindUserService()
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Privileged VoicemailUserService connected via Shizuku binder.")
            if (binder != null && binder.pingBinder()) {
                val service = IVoicemailAudioService.Stub.asInterface(binder)
                userService = service
                _isServiceBound.value = true
                reconnectAttempts = 0
                isConnecting.set(false)

                synchronized(pendingConnectCallbacks) {
                    pendingConnectCallbacks.forEach { it.invoke(service) }
                    pendingConnectCallbacks.clear()
                }
            } else {
                Log.e(TAG, "Binder returned from Shizuku was null or unresponsive to ping.")
                userService = null
                _isServiceBound.value = false
                isConnecting.set(false)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Privileged VoicemailUserService disconnected unexpectedly.")
            userService = null
            _isServiceBound.value = false
            isConnecting.set(false)
            triggerAutoReconnect()
        }
    }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            updateShizukuStatus()
        } catch (t: Throwable) {
            Log.e(TAG, "Error registering Shizuku listeners", t)
        }

        // Auto-bootstrap privileged daemon silently in background if root or Shizuku is present
        tryAutoBootstrapInternalService()
    }

    /**
     * Checks if Shizuku or root is available and automatically bootstraps privileged access
     * without blocking or requiring user intervention.
     */
    fun tryAutoBootstrapInternalService() {
        controllerScope.launch {
            try {
                val isShizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                if (isShizukuRunning) {
                    Log.i(TAG, "Shizuku binder is active. Attempting auto-binding...")
                    updateShizukuStatus()
                    if (_isPermissionGranted.value) {
                        bindUserService()
                    } else {
                        requestShizukuPermission()
                    }
                    return@launch
                }

                // Check for su (Root) availability
                val suCandidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
                val suBinary = suCandidates.firstOrNull { File(it).exists() }
                if (suBinary != null) {
                    Log.i(TAG, "su binary detected at $suBinary. Attempting internal permission auto-grant...")
                    val pkg = context.packageName
                    val grantCommands = arrayOf(
                        "pm grant $pkg android.permission.CAPTURE_AUDIO_OUTPUT",
                        "pm grant $pkg android.permission.MODIFY_AUDIO_ROUTING",
                        "pm grant $pkg android.permission.MODIFY_PHONE_STATE",
                        "appops set $pkg RECORD_AUDIO allow",
                        "appops set $pkg PLAY_AUDIO allow",
                        "appops set $pkg SYSTEM_ALERT_WINDOW allow"
                    )

                    val cmd = grantCommands.joinToString(" && ")
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                    process.waitFor()
                    Log.i(TAG, "su permission auto-grant completed with exitCode=${process.exitValue()}")

                    val daemonScript = File("/data/local/tmp/start_voicemail_daemon.sh")
                    if (daemonScript.exists()) {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "sh /data/local/tmp/start_voicemail_daemon.sh"))
                    }
                } else {
                    Log.d(TAG, "No root daemon detected. Using 100% standalone audio engine.")
                }
            } catch (t: Throwable) {
                Log.d(TAG, "tryAutoBootstrapInternalService completed (standalone fallback active): ${t.message}")
            }
        }
    }

    /**
     * Checks if Shizuku is currently operational on the device and whether permission is granted.
     */
    fun updateShizukuStatus(): Boolean {
        return try {
            val isAvailable = Shizuku.pingBinder()
            _isShizukuInstalledAndRunning.value = isAvailable

            val hasPermission = if (isAvailable) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
            _isPermissionGranted.value = hasPermission
            isAvailable && hasPermission
        } catch (t: Throwable) {
            Log.w(TAG, "Exception querying Shizuku status: ${t.message}")
            _isShizukuInstalledAndRunning.value = false
            _isPermissionGranted.value = false
            false
        }
    }

    /**
     * Prompts the user to grant Shizuku permission.
     */
    fun requestShizukuPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(requestCode)
                } else {
                    _isPermissionGranted.value = true
                }
            } else {
                Log.d(TAG, "Cannot request permission: Shizuku binder is not running.")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed requesting Shizuku permission: ${t.message}")
        }
    }

    /**
     * Spawns and binds to the privileged VoicemailUserService running under UID 2000.
     */
    @Synchronized
    fun bindUserService(onConnected: ((IVoicemailAudioService?) -> Unit)? = null) {
        val current = userService
        if (current != null && _isServiceBound.value) {
            onConnected?.invoke(current)
            return
        }

        if (onConnected != null) {
            synchronized(pendingConnectCallbacks) {
                pendingConnectCallbacks.add(onConnected)
            }
        }

        if (isConnecting.get()) {
            Log.d(TAG, "UserService binding already in progress...")
            return
        }

        if (!updateShizukuStatus()) {
            Log.d(TAG, "Cannot bind UserService: Shizuku not running or permission not granted.")
            return
        }

        isConnecting.set(true)

        val componentName = ComponentName(context.packageName, VoicemailUserService::class.java.name)
        val args = Shizuku.UserServiceArgs(componentName)
            .processNameSuffix("privileged_audio")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        try {
            Log.i(TAG, "Binding UserService with Shizuku: $componentName")
            Shizuku.bindUserService(args, serviceConnection)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed invoking Shizuku.bindUserService", t)
            isConnecting.set(false)
            triggerAutoReconnect()
        }
    }

    /**
     * Unbinds the privileged UserService.
     */
    @Synchronized
    fun unbindUserService(stopService: Boolean = false) {
        if (!_isServiceBound.value && userService == null) return

        val componentName = ComponentName(context.packageName, VoicemailUserService::class.java.name)
        val args = Shizuku.UserServiceArgs(componentName)

        try {
            Shizuku.unbindUserService(args, serviceConnection, stopService)
            Log.i(TAG, "Shizuku.unbindUserService executed.")
        } catch (t: Throwable) {
            Log.w(TAG, "Exception unbinding Shizuku UserService: ${t.message}")
        } finally {
            userService = null
            _isServiceBound.value = false
            isConnecting.set(false)
        }
    }

    /**
     * Starts a voicemail screening session on the privileged service.
     */
    fun startVoicemailSession(greetingFd: AssetFileDescriptor, callback: IAudioChunkCallback) {
        bindUserService { service ->
            if (service != null) {
                try {
                    service.startVoicemailSession(greetingFd, callback)
                    Log.i(TAG, "startVoicemailSession successfully dispatched to VoicemailUserService.")
                } catch (e: RemoteException) {
                    Log.e(TAG, "RemoteException invoking startVoicemailSession", e)
                    try {
                        callback.onError("RemoteException: ${e.message}")
                    } catch (ignored: Throwable) {}
                }
            } else {
                Log.e(TAG, "VoicemailUserService unavailable for startVoicemailSession.")
                try {
                    callback.onError("VoicemailUserService unavailable or not bound")
                } catch (ignored: Throwable) {}
            }
        }
    }

    /**
     * Enables or disables live room listening of the screening call.
     */
    fun setScreeningSpeakerEnabled(enabled: Boolean) {
        val service = userService ?: return
        try {
            service.setScreeningSpeakerEnabled(enabled)
            Log.i(TAG, "setScreeningSpeakerEnabled($enabled) sent to VoicemailUserService.")
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException calling setScreeningSpeakerEnabled", e)
        }
    }

    /**
     * Stops the active voicemail session and restores normal audio stream routing.
     */
    fun stopVoicemailSession() {
        val service = userService ?: return
        try {
            service.stopVoicemailSession()
            Log.i(TAG, "stopVoicemailSession sent to VoicemailUserService.")
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException calling stopVoicemailSession", e)
        }
    }

    private fun triggerAutoReconnect() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delayMs = BASE_RECONNECT_DELAY_MS * (1 shl (reconnectAttempts - 1))
            Log.i(TAG, "Scheduling Shizuku UserService reconnect attempt $reconnectAttempts in ${delayMs}ms")
            mainHandler.postDelayed({
                if (!_isServiceBound.value) {
                    bindUserService()
                }
            }, delayMs)
        } else {
            Log.d(TAG, "Max reconnect attempts reached for Shizuku UserService.")
        }
    }
}
