package com.focuslockapp.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

class BlockedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Remove animation so the red screen appears INSTANTLY
        overridePendingTransition(0, 0)

        setContentView(R.layout.activity_blocked)
        // Always dark red, so always light status/nav bar icons (even in light mode).
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val durationMinutes = remainingMinutes()
        val hours = durationMinutes / 60
        val mins = durationMinutes % 60
        findViewById<TextView>(R.id.tvBlockedRemaining).text = when {
            durationMinutes >= 24 * 60 -> "∞" // "always on until stopped" schedule
            else -> buildString {
                if (hours > 0) append("${hours}h ")
                if (mins > 0 || hours == 0) append("${mins}m")
            }.trim()
        }

        // Which lock is this? A coach lock carries its own name ("Study", "Sleep"...).
        val modeName = if (FocusState.isAiLockActive(this)) {
            FocusState.prefs(this).getString(FocusState.KEY_AI_REASON, null) ?: "Focus"
        } else "Focus"
        findViewById<TextView>(R.id.tvBlockedMode).text = "${modeName.uppercase()} MODE"

        findViewById<TextView>(R.id.tvBlockedQuote).text = "“${BLOCK_QUOTES.random()}”"
        findViewById<View>(R.id.btnBlockedHome).setOnClickListener { goHome() }

        // --- DISABLE BACK BUTTON (MODERN WAY) ---
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back would return to the blocked app — only "Back to home" leaves this screen.
            }
        })

        // Leave enough time to read the quote, then go home automatically.
        handler.postDelayed({ goHome() }, 4000)
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        val BLOCK_QUOTES = listOf(
            "Great work requires deep focus. Cheap dopamine does not.",
            "Your future self is watching what you do right now.",
            "You don't need more time. You need fewer distractions.",
            "Discipline is choosing what you want most over what you want now.",
            "One more scroll won't fix anything. Get back to it.",
            "Small, focused hours compound.",
            "The algorithm can wait. Your goals can't.",
            "Comfort is the enemy of progress.",
        )
    }


    /**
     * Time left on whichever lock ends LAST. A coach lock uses its exact end time; manual focus
     * mode counts down to its daily end time. (Previously this always used the manual end
     * time, so a coach lock ending at 18:00 could show "11h remaining".)
     */
    private fun remainingMinutes(): Int {
        val prefs = FocusState.prefs(this)
        val now = System.currentTimeMillis()
        var minutes = 0

        if (FocusState.isAiLockActive(this, now)) {
            val endMillis = prefs.getLong(FocusState.KEY_AI_END, now)
            minutes = ((endMillis - now + 59_999) / 60_000).toInt()
        }

        if (FocusState.isManualBlockingNow(this)) {
            val endHour = prefs.getInt(FocusState.KEY_END_HOUR, FocusState.DEFAULT_END_HOUR)
            val endMin = prefs.getInt(FocusState.KEY_END_MIN, 0)
            val calendar = Calendar.getInstance()
            val currentInMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
            var manualMinutes = (endHour * 60 + endMin) - currentInMinutes
            if (manualMinutes <= 0) manualMinutes += 24 * 60 // crosses midnight
            minutes = maxOf(minutes, manualMinutes)
        }
        return minutes
    }

    private fun goHome() {
        if (isFinishing) return
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(startMain)

        // Kill this activity so it doesn't stay in the back stack
        finishAndRemoveTask()
    }
}