package com.focuslockapp.myapplication

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The only two permissions blocking can't work without. Everything else (notifications,
 * exact alarms, battery) is optional and asked later, one at a time, never during setup.
 */
object SetupPermissions {

    /** Lets Focus Lock see which app is in front. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Lets Focus Lock show the block screen over other apps. */
    fun hasOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isReady(context: Context) = hasUsageAccess(context) && hasOverlay(context)

    fun doneCount(context: Context) = listOf(hasUsageAccess(context), hasOverlay(context)).count { it }
}
