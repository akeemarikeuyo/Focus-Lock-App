package com.focuslockapp.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires at the end time of a one-off (non-repeating) manual session. Repeating sessions
 * never schedule this alarm — the service just waits for the next day's window.
 */
class AutoStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FocusState.endManualSession(context)
    }
}
