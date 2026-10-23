package com.personal.voicemail.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Direct-Boot aware persistent configuration helper for system readiness,
 * battery survival exemptions, and OEM setup states.
 *
 * Uses [Context.createDeviceProtectedStorageContext] so preferences can be accessed
 * immediately after device restart before the user unlocks the lockscreen.
 */
object SetupPreferences {
    private const val TAG = "SetupPreferences"
    private const val PREFS_NAME = "voicemail_setup_prefs"

    const val KEY_BATTERY_EXEMPT_CONFIRMED = "battery_exempt_confirmed"
    const val KEY_ACCESSIBILITY_CONFIRMED = "accessibility_confirmed"
    const val KEY_DIALER_CONFIRMED = "dialer_confirmed"
    const val KEY_RUNTIME_PERMS_CONFIRMED = "runtime_perms_confirmed"

    fun getPrefs(context: Context): SharedPreferences {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isBatteryExemptConfirmed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_BATTERY_EXEMPT_CONFIRMED, false)
    }

    fun setBatteryExemptConfirmed(context: Context, confirmed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BATTERY_EXEMPT_CONFIRMED, confirmed).apply()
    }

    fun isAccessibilityConfirmed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ACCESSIBILITY_CONFIRMED, false)
    }

    fun setAccessibilityConfirmed(context: Context, confirmed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ACCESSIBILITY_CONFIRMED, confirmed).apply()
    }

    fun isDialerConfirmed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DIALER_CONFIRMED, false)
    }

    fun setDialerConfirmed(context: Context, confirmed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DIALER_CONFIRMED, confirmed).apply()
    }

    fun isRuntimePermsConfirmed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_RUNTIME_PERMS_CONFIRMED, false)
    }

    fun setRuntimePermsConfirmed(context: Context, confirmed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RUNTIME_PERMS_CONFIRMED, confirmed).apply()
    }

    /**
     * Opens OEM-specific autostart and background power management settings
     * (specifically targeting Vivo FuntouchOS / iQOO / ColorOS / MIUI).
     */
    fun openOemBackgroundSettings(context: Context): Boolean {
        val intents = listOf(
            // Vivo & iQOO High Background Power Consumption
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.HighPowerManagerActivity")),
            // Vivo & iQOO Autostart & Background Startup
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")),
            // Xiaomi / MIUI Autostart
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
            // Oppo / Realme Autostart
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
            Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
            // Standard Battery Optimization Settings
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            // Fallback App Details
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Successfully launched OEM background settings: ${intent.component ?: intent.action}")
                return true
            } catch (t: Throwable) {
                // Try next candidate
            }
        }
        return false
    }
}
