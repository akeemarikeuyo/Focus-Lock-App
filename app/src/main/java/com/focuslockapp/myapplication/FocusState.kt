package com.focuslockapp.myapplication

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for lock state, shared by the home screen, the coach,
 * the boot receiver, the auto-stop alarm and the Quick Settings tile.
 *
 * Two independent locks can be on at the same time:
 *  - Manual focus mode: the user's selected apps, inside the daily start/end window.
 *  - Coach lock: started from the AI coach, blocks social apps (plus the selected apps)
 *    between LOCK_START_TIME and LOCK_END_TIME.
 */
object FocusState {

    const val PREFS = "FocusPrefs"

    const val KEY_SERVICE_ACTIVE = "SERVICE_ACTIVE"
    const val KEY_SAVED_APPS = "saved_apps"
    const val KEY_START_HOUR = "START_HOUR"
    const val KEY_START_MIN = "START_MIN"
    const val KEY_END_HOUR = "END_HOUR"
    const val KEY_END_MIN = "END_MIN"
    const val KEY_REPEAT_DAILY = "REPEAT_DAILY"
    const val KEY_ACTIVATION_DATE = "MANUAL_BLOCK_ACTIVATION_DATE"

    const val KEY_AI_LOCKED = "IS_LOCKED"
    const val KEY_AI_START = "LOCK_START_TIME"
    const val KEY_AI_END = "LOCK_END_TIME"
    const val KEY_AI_REASON = "LOCK_REASON"

    const val KEY_BLOCK_COUNT = "BLOCK_COUNT"
    private const val KEY_STREAK = "STREAK_DAYS"
    private const val KEY_LAST_FOCUS_DAY = "LAST_FOCUS_DAY"

    const val DEFAULT_START_HOUR = 9
    const val DEFAULT_END_HOUR = 17

    private const val AUTO_STOP_REQUEST_CODE = 201
    private const val WAKE_REQUEST_CODE = 202

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- state queries

    fun isManualActive(context: Context) = prefs(context).getBoolean(KEY_SERVICE_ACTIVE, false)

    /** Coach lock is set and has not ended yet (it may still be waiting for its start time). */
    fun isAiLockPendingOrActive(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_AI_LOCKED, false) && now < p.getLong(KEY_AI_END, 0)
    }

    /** Coach lock is currently blocking. */
    fun isAiLockActive(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_AI_LOCKED, false) &&
            now >= p.getLong(KEY_AI_START, 0) && now < p.getLong(KEY_AI_END, 0)
    }

    fun shouldServiceRun(context: Context) = isManualActive(context) || isAiLockPendingOrActive(context)

    /** Manual focus mode is on AND we are inside its start/end window right now. */
    fun isManualBlockingNow(context: Context): Boolean {
        if (!isManualActive(context)) return false
        val p = prefs(context)
        val startHour = p.getInt(KEY_START_HOUR, DEFAULT_START_HOUR)
        val startMin = p.getInt(KEY_START_MIN, 0)
        val endHour = p.getInt(KEY_END_HOUR, DEFAULT_END_HOUR)
        val endMin = p.getInt(KEY_END_MIN, 0)
        val start = startHour * 60 + startMin
        val end = endHour * 60 + endMin

        if (start == end) return true // e.g. 12:00 AM - 12:00 AM: always on until stopped
        if (!isWithinRepeatWindow(p, start > end)) return false

        val now = Calendar.getInstance()
        val current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (start < end) current in start until end else current >= start || current < end
    }

    /**
     * Short detail line for the home status card, e.g. "Until 6:40 pm" or
     * "Starts tomorrow at 6:10 pm · ends 6:40 pm".
     */
    fun statusDetail(context: Context): String {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val timeFmt = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())

        if (isAiLockActive(context, now)) {
            val reason = p.getString(KEY_AI_REASON, "Focus")
            return "$reason Mode until " + timeFmt.format(Date(p.getLong(KEY_AI_END, now)))
        }
        val sH = p.getInt(KEY_START_HOUR, DEFAULT_START_HOUR); val sM = p.getInt(KEY_START_MIN, 0)
        val eH = p.getInt(KEY_END_HOUR, DEFAULT_END_HOUR); val eM = p.getInt(KEY_END_MIN, 0)
        fun hm(h: Int, m: Int) = timeFmt.format(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        }.time)

        if (isManualBlockingNow(context)) {
            return if (sH * 60 + sM == eH * 60 + eM) "Until you stop it" else "Until " + hm(eH, eM)
        }
        if (isAiLockPendingOrActive(context, now)) {
            return "Coach lock starts at " + timeFmt.format(Date(p.getLong(KEY_AI_START, now)))
        }
        if (isManualActive(context)) {
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val day = if (sH * 60 + sM > nowMin) "today" else "tomorrow"
            return "Starts $day at ${hm(sH, sM)} · ends ${hm(eH, eM)}"
        }
        return "Pick apps and a time, then tap Activate Lock"
    }

    /** Human-readable status for the toast and the ongoing notification. */
    fun statusText(context: Context): String {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val timeFmt = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
        fun hm(h: Int, m: Int) = timeFmt.format(Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
        }.time)

        if (isAiLockActive(context, now)) {
            return "Coach lock: blocking until " + timeFmt.format(Date(p.getLong(KEY_AI_END, now)))
        }
        if (isManualBlockingNow(context)) {
            val start = p.getInt(KEY_START_HOUR, DEFAULT_START_HOUR) * 60 + p.getInt(KEY_START_MIN, 0)
            val end = p.getInt(KEY_END_HOUR, DEFAULT_END_HOUR) * 60 + p.getInt(KEY_END_MIN, 0)
            return if (start == end) "Blocking until you stop it"
            else "Blocking until " + hm(p.getInt(KEY_END_HOUR, DEFAULT_END_HOUR), p.getInt(KEY_END_MIN, 0))
        }
        if (isAiLockPendingOrActive(context, now)) {
            return "Coach lock scheduled: starts at " + timeFmt.format(Date(p.getLong(KEY_AI_START, now)))
        }
        if (isManualActive(context)) {
            // e.g. "Scheduled: starts tomorrow at 6:45 pm · ends 6:55 pm"
            return "Scheduled: " + statusDetail(context).replaceFirstChar { it.lowercase() }
        }
        return "Focus mode is off"
    }

    /**
     * With Repeat Daily off, the session only applies on the day it was started (plus the
     * next morning for an overnight window that crosses midnight).
     */
    fun isWithinRepeatWindow(p: SharedPreferences, overnight: Boolean): Boolean {
        if (p.getBoolean(KEY_REPEAT_DAILY, true)) return true
        val activationDate = p.getString(KEY_ACTIVATION_DATE, null) ?: return false
        if (activationDate == dayString(0)) return true
        return overnight && activationDate == dayString(-1)
    }

    // ---------------------------------------------------------------- manual focus mode

    fun startManualFocus(
        context: Context,
        apps: Set<String>,
        startHour: Int, startMin: Int,
        endHour: Int, endMin: Int,
        repeatDaily: Boolean,
    ) {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        // Save everything the service needs, so it can be restarted later (reboot, app
        // update, Quick Settings tile) from prefs alone and get the same times.
        prefs(context).edit()
            .putStringSet(KEY_SAVED_APPS, apps)
            .putInt(KEY_START_HOUR, startHour).putInt(KEY_START_MIN, startMin)
            .putInt(KEY_END_HOUR, endHour).putInt(KEY_END_MIN, endMin)
            .putBoolean(KEY_REPEAT_DAILY, repeatDaily)
            .putString(KEY_ACTIVATION_DATE, today)
            .putBoolean(KEY_SERVICE_ACTIVE, true)
            .commit()

        updateAutoStop(context)
        markFocusDay(context)
        syncService(context)
    }

    /** Re-starts manual focus from the times and apps already saved (used by the tile). */
    fun startManualFocusFromSaved(context: Context): Boolean {
        val p = prefs(context)
        val apps = p.getStringSet(KEY_SAVED_APPS, emptySet()) ?: emptySet()
        if (apps.isEmpty()) return false
        startManualFocus(
            context, apps,
            p.getInt(KEY_START_HOUR, DEFAULT_START_HOUR), p.getInt(KEY_START_MIN, 0),
            p.getInt(KEY_END_HOUR, DEFAULT_END_HOUR), p.getInt(KEY_END_MIN, 0),
            p.getBoolean(KEY_REPEAT_DAILY, true),
        )
        return true
    }

    /** Stop button: turns off manual focus mode and any coach lock. */
    fun stopAll(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_SERVICE_ACTIVE, false)
            .putBoolean(KEY_AI_LOCKED, false)
            .putLong(KEY_AI_END, 0)
            .commit()
        cancelAutoStop(context)
        cancelWake(context)
        context.stopService(Intent(context, BlockerService::class.java))
        refreshTile(context)
    }

    /** End of a one-off (non-repeating) manual session. Leaves a running coach lock alone. */
    fun endManualSession(context: Context) {
        prefs(context).edit().putBoolean(KEY_SERVICE_ACTIVE, false).commit()
        syncService(context)
    }

    // ---------------------------------------------------------------- coach lock

    fun startAiLock(context: Context, startMillis: Long, endMillis: Long, reason: String) {
        prefs(context).edit()
            .putBoolean(KEY_AI_LOCKED, true)
            .putLong(KEY_AI_START, startMillis)
            .putLong(KEY_AI_END, endMillis)
            .putString(KEY_AI_REASON, reason)
            .commit()
        if (startMillis <= System.currentTimeMillis()) markFocusDay(context)
        syncService(context)
    }

    // ---------------------------------------------------------------- service + alarms

    fun isBlockingNow(context: Context) = isAiLockActive(context) || isManualBlockingNow(context)

    /**
     * The blocker (and its ongoing notification) runs ONLY while something is being blocked.
     * Between windows it is stopped and an alarm wakes it at the next start time — previously
     * a repeating 6:45–6:55 pm lock kept a "Scheduled…" notification up all day.
     *
     * Call this whenever lock state may have changed: Start, coach lock, app open, reboot,
     * app update, the wake alarm, and when a window ends inside the service.
     */
    fun syncService(context: Context) {
        expireFinishedLocks(context)
        if (isBlockingNow(context)) {
            cancelWake(context)
            startBlockerService(context)
        } else if (shouldServiceRun(context) && !canSleepBetweenWindows(context)) {
            // No "Alarms & reminders" permission: we can't be woken exactly at the start
            // time, so keep the blocker running (with a "Scheduled" notification) instead of
            // asking the user for one more permission.
            startBlockerService(context)
        } else {
            context.stopService(Intent(context, BlockerService::class.java))
            scheduleNextWake(context)
        }
        refreshTile(context)
    }

    /** Exact alarms let the blocker sleep between windows. Optional — never prompted for. */
    fun canSleepBetweenWindows(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    /** Turns off a finished coach lock, and a one-off (non-repeating) session whose day is over. */
    fun expireFinishedLocks(context: Context) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (p.getBoolean(KEY_AI_LOCKED, false) && now >= p.getLong(KEY_AI_END, 0)) {
            p.edit().putBoolean(KEY_AI_LOCKED, false).apply()
        }
        if (isManualActive(context) && !p.getBoolean(KEY_REPEAT_DAILY, true) && !isManualBlockingNow(context)) {
            val start = p.getInt(KEY_START_HOUR, DEFAULT_START_HOUR) * 60 + p.getInt(KEY_START_MIN, 0)
            val end = p.getInt(KEY_END_HOUR, DEFAULT_END_HOUR) * 60 + p.getInt(KEY_END_MIN, 0)
            val nextStartDay = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nextManualStartMillis(context)))
            // One-off session: if the next window would begin on a later day, it's finished.
            if (start != end && nextStartDay != p.getString(KEY_ACTIVATION_DATE, null)) {
                p.edit().putBoolean(KEY_SERVICE_ACTIVE, false).apply()
                cancelAutoStop(context)
            }
        }
    }

    /** Next time today's (or tomorrow's) manual window begins. */
    fun nextManualStartMillis(context: Context): Long {
        val p = prefs(context)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt(KEY_START_HOUR, DEFAULT_START_HOUR))
            set(Calendar.MINUTE, p.getInt(KEY_START_MIN, 0))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun scheduleNextWake(context: Context) {
        val now = System.currentTimeMillis()
        val candidates = mutableListOf<Long>()
        if (isManualActive(context)) candidates += nextManualStartMillis(context)
        if (isAiLockPendingOrActive(context, now)) {
            val aiStart = prefs(context).getLong(KEY_AI_START, now)
            if (aiStart > now) candidates += aiStart
        }
        val next = candidates.minOrNull()
        if (next == null) {
            cancelWake(context)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wakeIntent(context))
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, wakeIntent(context))
            }
        } catch (e: SecurityException) {
            Log.w("FocusState", "Could not schedule wake alarm", e)
        }
    }

    private fun cancelWake(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(wakeIntent(context))
    }

    private fun wakeIntent(context: Context) = PendingIntent.getBroadcast(
        context, WAKE_REQUEST_CODE, Intent(context, FocusWakeReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun startBlockerService(context: Context) {
        val intent = Intent(context, BlockerService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // e.g. background-start restrictions; the lock state is saved, so the next
            // app open or reboot will start the service.
            Log.w("FocusState", "Could not start BlockerService", e)
        }
    }

    /**
     * One-off sessions end at the end time. Repeating sessions must NOT be stopped there,
     * otherwise "Repeat daily" only ever works for one day.
     */
    fun updateAutoStop(context: Context) {
        val p = prefs(context)
        if (!isManualActive(context) || p.getBoolean(KEY_REPEAT_DAILY, true)) {
            cancelAutoStop(context)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val stopAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, p.getInt(KEY_END_HOUR, DEFAULT_END_HOUR))
            set(Calendar.MINUTE, p.getInt(KEY_END_MIN, 0))
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, stopAt.timeInMillis, autoStopIntent(context))
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, stopAt.timeInMillis, autoStopIntent(context))
            }
        } catch (e: SecurityException) {
            Log.w("FocusState", "Could not schedule auto-stop", e)
        }
    }

    private fun cancelAutoStop(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(autoStopIntent(context))
    }

    private fun autoStopIntent(context: Context) = PendingIntent.getBroadcast(
        context, AUTO_STOP_REQUEST_CODE, Intent(context, AutoStopReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun refreshTile(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                TileService.requestListeningState(context, ComponentName(context, FocusTileService::class.java))
            } catch (e: Exception) {
                // Tile not added by the user; nothing to refresh.
            }
        }
    }

    // ---------------------------------------------------------------- stats

    /** Lifetime count — no longer reset when a session starts. */
    fun recordBlock(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_BLOCK_COUNT, p.getInt(KEY_BLOCK_COUNT, 0) + 1).apply()
        markFocusDay(context)
    }

    fun blockCount(context: Context) = prefs(context).getInt(KEY_BLOCK_COUNT, 0)

    /** Counts consecutive days on which a lock was active. */
    fun markFocusDay(context: Context) {
        val p = prefs(context)
        val today = dayString(0)
        val last = p.getString(KEY_LAST_FOCUS_DAY, null)
        if (last == today) return
        val streak = if (last == dayString(-1)) p.getInt(KEY_STREAK, 0) + 1 else 1
        p.edit().putString(KEY_LAST_FOCUS_DAY, today).putInt(KEY_STREAK, streak).apply()
    }

    /** Current streak; 0 if the last focus day was before yesterday. */
    fun streakDays(context: Context): Int {
        val p = prefs(context)
        val last = p.getString(KEY_LAST_FOCUS_DAY, null)
        return if (last == dayString(0) || last == dayString(-1)) p.getInt(KEY_STREAK, 0) else 0
    }

    private fun dayString(offsetDays: Int): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offsetDays) }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    }
}
