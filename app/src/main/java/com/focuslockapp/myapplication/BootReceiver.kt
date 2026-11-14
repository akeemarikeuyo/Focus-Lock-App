package com.focuslockapp.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A reboot cancels all AlarmManager alarms, and an app update (auto or manual)
        // also clears them — either way the daily reminder chain goes silent until
        // someone reopens the app, unless we reschedule it here.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MainActivity.rescheduleAlarms(context)
        }
    }
}
