package com.focuslockapp.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // A reboot or an app update kills the blocker and clears every AlarmManager alarm.
        // Without this, restarting the phone was an easy way to escape a lock.
        // (The 9am reminder is a Firebase push now, so there is no reminder alarm to restore.)
        MainActivity.cancelLegacyReminderAlarm(context)

        // Starts the blocker if a window is active now, otherwise re-arms the wake alarm.
        FocusState.syncService(context)
        FocusState.updateAutoStop(context)
    }
}
