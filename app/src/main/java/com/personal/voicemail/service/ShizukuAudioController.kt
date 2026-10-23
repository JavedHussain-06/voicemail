package com.personal.voicemail.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.os.Build
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
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ShizukuAudioController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuAudioController"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val BASE_RECONNECT_DELAY_MS = 1000L

        const val ADB_ONE_TIME_GRANT_COMMAND =
            "appops set com.personal.voicemail RECORD_AUDIO allow ; appops set com.personal.voicemail PLAY_AUDIO allow ; appops set com.personal.voicemail PROJECT_MEDIA allow ; appops set com.personal.voicemail CAPTURE_AUDIO_OUTPUT allow ; appops set com.personal.voicemail MODIFY_AUDIO_ROUTING allow ; cmd appops set com.android.shell RECORD_AUDIO allow ; cmd appops set com.android.shell CAPTURE_AUDIO_OUTPUT allow ; cmd appops set com.android.shell MODIFY_AUDIO_ROUTING allow"

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

    fun hasPermanentDigitalTelephonyPrivilege(): Boolean {
        val hasCapture = context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == PackageManager.PERMISSION_GRANTED
        val hasRouting = context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == PackageManager.PERMISSION_GRANTED
        return isBound || (hasCapture && hasRouting)
    }

    val isPrivilegedDaemonActive: Boolean
        get() = isBound || hasPermanentDigitalTelephonyPrivilege()

    suspend fun ensureBound(timeoutMs: Long = 2500L): Boolean {
        if (isBound) return true
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
        Log.i(TAG, "Shizuku binder received")
        updateShizukuStatus()
        grantPermanentStandalonePermissions {
            bindUserService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died.")
        _isShizukuInstalledAndRunning.value = false
        _isPermissionGranted.value = false
        _isServiceBound.value = false
        userService = null
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            _isPermissionGranted.value = granted
            if (granted) {
                grantPermanentStandalonePermissions {
                    bindUserService()
                }
            }
        }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Privileged VoicemailUserService (UID 2000) connected!")
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
                userService = null
                _isServiceBound.value = false
                isConnecting.set(false)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Privileged VoicemailUserService disconnected.")
            userService = null
            _isServiceBound.value = false
            isConnecting.set(false)
            triggerAutoReconnect()
        }
    }

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
            }
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            updateShizukuStatus()
        } catch (t: Throwable) {
            Log.e(TAG, "Error registering Shizuku listeners", t)
        }

        tryAutoBootstrapInternalService()
    }

    fun grantPermanentStandalonePermissions(onCompleted: ((Boolean) -> Unit)? = null) {
        controllerScope.launch(Dispatchers.IO) {
            val success = executePermissionGrantCommands()
            onCompleted?.let { cb ->
                mainHandler.post { cb(success) }
            }
        }
    }

    private fun executePermissionGrantCommands(): Boolean {
        val pkg = context.packageName
        // Semicolon-separated commands so a non-changeable permission never aborts appops grants!
        val cmds = listOf(
            "appops set $pkg RECORD_AUDIO allow",
            "appops set $pkg PLAY_AUDIO allow",
            "appops set $pkg PROJECT_MEDIA allow",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "appops set $pkg CAPTURE_AUDIO_OUTPUT allow",
            "appops set $pkg MODIFY_AUDIO_ROUTING allow",
            "cmd appops set com.android.shell RECORD_AUDIO allow",
            "cmd appops set com.android.shell PLAY_AUDIO allow",
            "cmd appops set com.android.shell CAPTURE_AUDIO_OUTPUT allow",
            "cmd appops set com.android.shell MODIFY_AUDIO_ROUTING allow",
            "pm grant $pkg android.permission.RECORD_AUDIO",
            "pm grant $pkg android.permission.CALL_PHONE",
            "pm grant $pkg android.permission.READ_PHONE_STATE",
            "pm grant $pkg android.permission.READ_CALL_LOG",
            "pm grant $pkg android.permission.WRITE_CALL_LOG",
            "pm grant $pkg android.permission.READ_CONTACTS"
        )
        val grantCmd = cmds.joinToString(" ; ")

        val isShizukuAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (isShizukuAvailable) {
            val hasShizukuPerm = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

            if (hasShizukuPerm) {
                try {
                    val cmdArray = arrayOf("sh", "-c", grantCmd)
                    val process: Process? = runCatching {
                        HiddenApiBypass.invoke(
                            Shizuku::class.java,
                            null,
                            "newProcess",
                            cmdArray,
                            null,
                            null
                        ) as Process
                    }.getOrNull()
                    process?.waitFor()
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed executing Shizuku grant command: ${t.message}")
                }
            }
        }

        try {
            val suProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", grantCmd))
            if (suProcess.waitFor() == 0) return true
        } catch (ignored: Throwable) {}

        return isBound
    }

    fun tryAutoBootstrapInternalService() {
        controllerScope.launch {
            try {
                val isShizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                if (isShizukuRunning) {
                    updateShizukuStatus()
                    if (_isPermissionGranted.value) {
                        grantPermanentStandalonePermissions {
                            bindUserService()
                        }
                    } else {
                        requestShizukuPermission()
                    }
                    return@launch
                }

                val suCandidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
                val hasSuBinary = suCandidates.any { File(it).exists() }
                if (hasSuBinary) {
                    executePermissionGrantCommands()
                }
            } catch (ignored: Throwable) {}
        }
    }

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
            _isShizukuInstalledAndRunning.value = false
            _isPermissionGranted.value = false
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int = 1001) {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(requestCode)
                } else {
                    _isPermissionGranted.value = true
                    grantPermanentStandalonePermissions {
                        bindUserService()
                    }
                }
            }
        } catch (ignored: Throwable) {}
    }

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

        if (isConnecting.get()) return
        if (!updateShizukuStatus()) return

        isConnecting.set(true)

        val componentName = ComponentName(context.packageName, VoicemailUserService::class.java.name)
        val args = Shizuku.UserServiceArgs(componentName)
            .processNameSuffix("privileged_audio")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

        try {
            Shizuku.bindUserService(args, serviceConnection)
        } catch (t: Throwable) {
            isConnecting.set(false)
            triggerAutoReconnect()
        }
    }

    @Synchronized
    fun unbindUserService(stopService: Boolean = false) {
        if (!_isServiceBound.value && userService == null) return
        val componentName = ComponentName(context.packageName, VoicemailUserService::class.java.name)
        val args = Shizuku.UserServiceArgs(componentName)
        try {
            Shizuku.unbindUserService(args, serviceConnection, stopService)
        } catch (ignored: Throwable) {} finally {
            userService = null
            _isServiceBound.value = false
            isConnecting.set(false)
        }
    }

    fun startVoicemailSession(greetingFd: AssetFileDescriptor, callback: IAudioChunkCallback) {
        bindUserService { service ->
            if (service != null) {
                try {
                    service.startVoicemailSession(greetingFd, callback)
                } catch (e: RemoteException) {
                    try { callback.onError("RemoteException: ${e.message}") } catch (ignored: Throwable) {}
                }
            } else {
                try { callback.onError("VoicemailUserService unavailable") } catch (ignored: Throwable) {}
            }
        }
    }

    fun setScreeningSpeakerEnabled(enabled: Boolean) {
        try {
            userService?.setScreeningSpeakerEnabled(enabled)
        } catch (ignored: RemoteException) {}
    }

    fun stopVoicemailSession() {
        try {
            userService?.stopVoicemailSession()
        } catch (ignored: RemoteException) {}
    }

    private fun triggerAutoReconnect() {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts++
            val delayMs = BASE_RECONNECT_DELAY_MS * (1 shl (reconnectAttempts - 1))
            mainHandler.postDelayed({
                if (!_isServiceBound.value) bindUserService()
            }, delayMs)
        }
    }
}
