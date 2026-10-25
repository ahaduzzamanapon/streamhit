package com.streamhit.app.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.streamhit.app.MainActivity
import com.streamhit.app.R
import com.streamhit.app.StreamfitApp
import com.streamhit.app.data.api.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class AppFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AppFirebaseMessaging"
        const val EXTRA_DETAIL_PATH = "EXTRA_DETAIL_PATH"
        const val EXTRA_SUBJECT_ID = "EXTRA_SUBJECT_ID"
        const val EXTRA_TARGET_URL = "EXTRA_TARGET_URL"

        fun registerTokenWithServer(context: Context, token: String) {
            val prefs = context.getSharedPreferences("nxly_fcm", Context.MODE_PRIVATE)
            val savedToken = prefs.getString("fcm_token", null)
            if (savedToken == token) return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val deviceId = com.streamhit.app.data.analytics.AppAnalyticsManager.cachedDeviceId
                        ?: com.streamhit.app.data.analytics.AppAnalyticsManager.getDeviceId(context)
                    val model = com.streamhit.app.data.analytics.AppAnalyticsManager.getDeviceModel()
                    val os = com.streamhit.app.data.analytics.AppAnalyticsManager.getOsVersion()

                    val payload = mapOf(
                        "token" to token,
                        "device_id" to deviceId,
                        "model" to model,
                        "os" to os
                    )
                    val resp = RetrofitClient.apiService.registerFcmToken(payload)
                    if (resp.isSuccessful) {
                        prefs.edit().putString("fcm_token", token).apply()
                        Log.d(TAG, "FCM Token registered with backend successfully")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register FCM token with server: ${e.message}")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New Firebase Cloud Messaging Token generated: $token")
        registerTokenWithServer(applicationContext, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.data["title"]
            ?: remoteMessage.notification?.title
            ?: "NXLY Stream"

        val body = remoteMessage.data["body"]
            ?: remoteMessage.data["message"]
            ?: remoteMessage.notification?.body
            ?: "New entertainment is waiting for you!"

        val imageUrl = remoteMessage.data["image"]
            ?: remoteMessage.data["cover"]
            ?: remoteMessage.data["poster"]
            ?: remoteMessage.notification?.imageUrl?.toString()

        val detailPath = remoteMessage.data["detail_path"]
            ?: remoteMessage.data["detailPath"]
            ?: ""

        val subjectId = remoteMessage.data["subject_id"]
            ?: remoteMessage.data["subjectId"]
            ?: ""

        val targetUrl = remoteMessage.data["url"] ?: ""

        sendPushNotification(title, body, imageUrl, detailPath, subjectId, targetUrl)
    }

    private fun sendPushNotification(
        title: String,
        body: String,
        imageUrl: String?,
        detailPath: String,
        subjectId: String,
        targetUrl: String
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_DETAIL_PATH, detailPath)
            putExtra(EXTRA_SUBJECT_ID, subjectId)
            putExtra(EXTRA_TARGET_URL, targetUrl)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, StreamfitApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.app_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        // If an image/cover URL was provided, fetch and render BigPictureStyle
        if (!imageUrl.isNullOrBlank()) {
            val bitmap = downloadImageBitmap(imageUrl)
            if (bitmap != null) {
                notificationBuilder.setLargeIcon(bitmap)
                notificationBuilder.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .setBigContentTitle(title)
                        .setSummaryText(body)
                )
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun downloadImageBitmap(urlStr: String): Bitmap? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.connect()
            val input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (_: Exception) {
            null
        }
    }
}
