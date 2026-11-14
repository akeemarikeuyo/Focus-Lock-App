package com.focuslockapp.myapplication

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings tile. Tap when off: starts focus mode with the saved apps and times.
 * Tap when on: opens the app, because stopping a lock still goes through the ad.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FocusTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val locked = FocusState.isManualActive(this) || FocusState.isAiLockPendingOrActive(this)
        if (locked) {
            openApp()
            return
        }
        // No apps chosen yet: send the user to the app to pick some.
        if (!FocusState.startManualFocusFromSaved(this)) openApp()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val manual = FocusState.isManualActive(this)
        val ai = FocusState.isAiLockPendingOrActive(this)
        tile.state = if (manual || ai) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Focus Lock"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                ai -> "Coach lock"
                manual -> "On"
                else -> "Off"
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
