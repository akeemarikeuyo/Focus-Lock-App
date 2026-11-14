package com.focuslockapp.myapplication

import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds (emulator / Android Studio) can't pass Play Integrity, so they use a debug
 * token instead. On first run, Logcat prints "Enter this debug secret into the allow list
 * in the Firebase Console" — add that token under App Check > Apps > Manage debug tokens.
 */
object AppCheckSetup {
    fun install() {
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
    }
}
