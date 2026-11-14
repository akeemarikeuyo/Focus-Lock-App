package com.focuslockapp.myapplication

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Daily reminders come from Firebase Cloud Messaging (topic [TOPIC]) instead of a local alarm:
 * local alarms got dropped once Android/Samsung put the app to sleep after ~2 days unused.
 *
 * When the app is in the background the system shows the notification by itself (using the
 * default channel/icon from the manifest). This service only handles the app-in-foreground case.
 */
class FocusMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: "FocusCoach 🤖"
        val body = message.notification?.body ?: message.data["body"] ?: return
        showReminder(title, body)
    }

    override fun onNewToken(token: String) {
        // Topic subscriptions survive token refresh, but re-subscribing is harmless and cheap.
        subscribe()
    }

    private fun showReminder(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(200, notification)
        } catch (e: SecurityException) {
            Log.w("FocusMessaging", "Notification permission missing", e)
        }
    }

    companion object {
        const val TOPIC = "daily_reminder"
        const val REMINDER_CHANNEL_ID = "DailyReminderChannel"

        fun subscribe() {
            FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
                .addOnFailureListener { Log.w("FocusMessaging", "Topic subscribe failed (will retry next launch)", it) }
        }
    }
}
