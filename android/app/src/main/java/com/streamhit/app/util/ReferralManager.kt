package com.streamhit.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.streamhit.app.data.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

object ReferralManager {

    private const val PREFS_NAME = "streamhit_referral_prefs"
    private const val KEY_REFERRAL_CODE = "my_referral_code"
    private const val KEY_PREMIUM_UNTIL_MS = "premium_until_ms"
    private const val KEY_TOTAL_REFERRALS = "total_referrals"
    private const val KEY_HAS_CLAIMED = "has_claimed"
    private const val KEY_HAS_PROMPTED = "has_prompted_first_launch"
    private const val KEY_PERSISTENT_DEVICE_ID = "persistent_device_id"

    @Volatile
    var cachedDeviceId: String = ""
        private set

    var myReferralCode by mutableStateOf("")
        private set

    var isPremium by mutableStateOf(false)
        private set

    var premiumDaysLeft by mutableIntStateOf(0)
        private set

    var premiumUntilMs: Long = 0L
        private set

    var totalReferrals by mutableIntStateOf(0)
        private set

    var hasClaimed by mutableStateOf(false)
        private set

    var hasPromptedFirstLaunch by mutableStateOf(false)
        private set

    /**
     * Retrieves a persistent unique hardware/device ID.
     * Works consistently across app reinstalls on Phone, Android TV, and Google TV.
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(context: Context): String {
        if (cachedDeviceId.isNotBlank()) return cachedDeviceId
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var id = Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            if (id.isNullOrBlank() || id == "9774d56d682e549c") {
                id = prefs.getString(KEY_PERSISTENT_DEVICE_ID, null)
                if (id.isNullOrBlank()) {
                    id = "DEV_" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
                    prefs.edit().putString(KEY_PERSISTENT_DEVICE_ID, id).apply()
                }
            } else {
                prefs.edit().putString(KEY_PERSISTENT_DEVICE_ID, id).apply()
            }
            cachedDeviceId = id
            return id
        } catch (_: Exception) {
            val fallback = "DEV_" + Build.FINGERPRINT.hashCode().toString().replace("-", "")
            cachedDeviceId = fallback
            return fallback
        }
    }

    /**
     * Checks if current user has an active Ad-Free VIP Premium pass.
     */
    fun isUserPremium(): Boolean {
        return (premiumUntilMs > System.currentTimeMillis())
    }

    /**
     * Initialize local cached referral and premium status, then sync with server.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
        try {
            getDeviceId(appContext)
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            myReferralCode = prefs.getString(KEY_REFERRAL_CODE, "") ?: ""
            premiumUntilMs = prefs.getLong(KEY_PREMIUM_UNTIL_MS, 0L)
            totalReferrals = prefs.getInt(KEY_TOTAL_REFERRALS, 0)
            hasClaimed = prefs.getBoolean(KEY_HAS_CLAIMED, false)
            hasPromptedFirstLaunch = prefs.getBoolean(KEY_HAS_PROMPTED, false)

            updatePremiumState()
        } catch (_: Exception) {}

        syncWithServer(appContext)
    }

    private fun updatePremiumState() {
        val now = System.currentTimeMillis()
        if (premiumUntilMs > now) {
            isPremium = true
            val diffMs = premiumUntilMs - now
            premiumDaysLeft = Math.ceil(diffMs / 86400000.0).toInt().coerceAtLeast(1)
        } else {
            isPremium = false
            premiumDaysLeft = 0
        }
    }

    fun markFirstLaunchPrompted(context: Context) {
        hasPromptedFirstLaunch = true
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_HAS_PROMPTED, true).apply()
        } catch (_: Exception) {}
    }

    /**
     * Sync device ID with server to restore referral code, premium pass, and stats.
     */
    fun syncWithServer(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val deviceId = getDeviceId(context)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = RetrofitClient.apiService.syncReferral(
                    mapOf("device_id" to deviceId, "device_model" to deviceModel)
                )
                if (res.isSuccessful) {
                    val body = res.body()
                    val code = body?.get("referral_code")?.toString() ?: ""
                    val isPrem = (body?.get("is_premium") as? Boolean) ?: false
                    val daysLeft = (body?.get("premium_days_left") as? Number)?.toInt() ?: 0
                    val premUntilSec = (body?.get("premium_until") as? Number)?.toDouble() ?: 0.0
                    val totRef = (body?.get("total_referrals") as? Number)?.toInt() ?: 0
                    val claimed = (body?.get("has_claimed") as? Boolean) ?: false

                    val untilMs = (premUntilSec * 1000).toLong()

                    CoroutineScope(Dispatchers.Main).launch {
                        if (code.isNotBlank()) myReferralCode = code
                        premiumUntilMs = untilMs
                        isPremium = isPrem || (untilMs > System.currentTimeMillis())
                        premiumDaysLeft = daysLeft
                        totalReferrals = totRef
                        hasClaimed = claimed
                        updatePremiumState()

                        try {
                            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.edit()
                                .putString(KEY_REFERRAL_CODE, myReferralCode)
                                .putLong(KEY_PREMIUM_UNTIL_MS, premiumUntilMs)
                                .putInt(KEY_TOTAL_REFERRALS, totalReferrals)
                                .putBoolean(KEY_HAS_CLAIMED, hasClaimed)
                                .apply()
                        } catch (_: Exception) {}

                        onComplete?.invoke(true)
                    }
                } else {
                    CoroutineScope(Dispatchers.Main).launch { onComplete?.invoke(false) }
                }
            } catch (_: Exception) {
                CoroutineScope(Dispatchers.Main).launch { onComplete?.invoke(false) }
            }
        }
    }

    /**
     * Claim another user's referral code to get 3 Days of Ad-Free VIP Premium.
     */
    fun claimReferral(context: Context, inputCode: String, onResult: (Boolean, String) -> Unit) {
        val deviceId = getDeviceId(context)
        val cleanCode = inputCode.trim().uppercase()
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        if (cleanCode.length != 6) {
            onResult(false, "Referral code must be 6 characters.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = RetrofitClient.apiService.claimReferral(
                    mapOf(
                        "device_id" to deviceId,
                        "referral_code" to cleanCode,
                        "device_model" to deviceModel
                    )
                )
                if (res.isSuccessful) {
                    val body = res.body()
                    val codeInt = (body?.get("code") as? Number)?.toInt() ?: 1
                    if (codeInt == 0) {
                        val message = body?.get("message")?.toString() ?: "3 Days VIP Premium activated!"
                        val premUntilSec = (body?.get("premium_until") as? Number)?.toDouble() ?: 0.0
                        val untilMs = (premUntilSec * 1000).toLong()

                        CoroutineScope(Dispatchers.Main).launch {
                            premiumUntilMs = untilMs
                            hasClaimed = true
                            hasPromptedFirstLaunch = true
                            updatePremiumState()

                            try {
                                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putLong(KEY_PREMIUM_UNTIL_MS, premiumUntilMs)
                                    .putBoolean(KEY_HAS_CLAIMED, true)
                                    .putBoolean(KEY_HAS_PROMPTED, true)
                                    .apply()
                            } catch (_: Exception) {}

                            onResult(true, message)
                        }
                    } else {
                        val error = body?.get("error")?.toString() ?: "Failed to claim referral."
                        CoroutineScope(Dispatchers.Main).launch {
                            onResult(false, error)
                        }
                    }
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        onResult(false, "Network error. Please try again.")
                    }
                }
            } catch (e: Exception) {
                CoroutineScope(Dispatchers.Main).launch {
                    onResult(false, e.localizedMessage ?: "Connection error.")
                }
            }
        }
    }
}

