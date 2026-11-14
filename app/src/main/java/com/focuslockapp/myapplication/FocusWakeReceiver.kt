package com.focuslockapp.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires at the next lock start time (set by FocusState.syncService) and starts the blocker. */
class FocusWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusState.syncService(context)
    }
}
