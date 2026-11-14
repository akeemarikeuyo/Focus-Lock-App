package com.focuslockapp.myapplication

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.firebase.FirebaseApp

class FocusLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // Must run before any Firebase AI call so every request carries an App Check token.
        AppCheckSetup.install()

        // Debug builds only: mark the developer's own phones as AdMob test devices so tapping or
        // viewing ads while testing can't be flagged as invalid traffic. Release builds rely on
        // the test devices registered in the AdMob console instead (Google asks that this code
        // not ship in production).
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder()
                    .setTestDeviceIds(ADMOB_TEST_DEVICES)
                    .build()
            )
        }

        // Daily reminder: every install joins the FCM topic automatically. Users opt out by
        // turning off the "Daily Focus Reminders" channel in the app's notification settings.
        createReminderChannel()
        FocusMessagingService.subscribe()
        if (isDebuggable) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener {
                Log.d("FocusMessaging", "FCM token (debug only): $it")
            }
        }
    }

    /** Must exist before the first push arrives, or Android files it under "Miscellaneous". */
    private fun createReminderChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            FocusMessagingService.REMINDER_CHANNEL_ID,
            "Daily Focus Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "One motivational check-in each morning" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private companion object {
        // Hashed IDs printed by the Ads SDK in Logcat ("Use RequestConfiguration...setTestDeviceIds").
        val ADMOB_TEST_DEVICES = listOf(
            "83A3300D3E84981FC69695E19F504C95", // Galaxy S24 Ultra (SM-S928B)
        )
    }
}
