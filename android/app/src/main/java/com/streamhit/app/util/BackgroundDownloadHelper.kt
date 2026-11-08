package com.streamhit.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

object BackgroundDownloadHelper {

    private const val PREFS_NAME = "streamhit_bg_download_prefs"
    private const val KEY_PROMPTED_INITIAL = "has_prompted_bg_permission"

    /**
     * Checks if battery optimizations are currently ignored for this app.
     * When true, Android system won't kill background download threads or services.
     */
    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if notification permission is granted (Android 13+ / API 33+).
     */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * True if all essential background download permissions are active.
     */
    fun isBackgroundDownloadReady(context: Context): Boolean {
        return isBatteryOptimizationIgnored(context) && isNotificationPermissionGranted(context)
    }

    /**
     * Checks whether we already asked user for background permission initially.
     */
    fun hasPromptedInitialPermission(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PROMPTED_INITIAL, false)
    }

    /**
     * Marks initial prompt as shown.
     */
    fun setPromptedInitialPermission(context: Context, prompted: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PROMPTED_INITIAL, prompted).apply()
    }

    /**
     * Request system dialog to ignore battery optimizations (direct system whitelist prompt).
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
            // Fallback to general battery optimization screen
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                openAppDetailsSettings(context)
            }
        }
    }

    /**
     * Opens App Details Settings where user can toggle battery or permissions manually if needed.
     */
    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }
}

