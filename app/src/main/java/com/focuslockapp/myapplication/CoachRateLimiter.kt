package com.focuslockapp.myapplication

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Client-side limits on Focus Coach AI requests.
 *
 * This keeps normal users from burning through the Gemini quota, but it lives on the
 * device, so clearing app data resets it. The real enforcement belongs on the backend.
 */
object CoachRateLimiter {

    const val MAX_REQUESTS_PER_DAY = 20
    const val MIN_SECONDS_BETWEEN_REQUESTS = 10
    const val MAX_MESSAGE_LENGTH = 300

    private const val PREFS = "CoachLimits"
    private const val KEY_DAY = "DAY"
    private const val KEY_COUNT = "COUNT"
    private const val KEY_LAST_REQUEST = "LAST_REQUEST_MS"

    sealed class Result {
        object Allowed : Result()
        data class Denied(val reason: String) : Result()
    }

    fun check(context: Context, message: String): Result {
        if (message.length > MAX_MESSAGE_LENGTH) {
            return Result.Denied(
                "That's a long one! Keep it under $MAX_MESSAGE_LENGTH characters so I can stay sharp."
            )
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val secondsSinceLast = (now - prefs.getLong(KEY_LAST_REQUEST, 0L)) / 1000
        if (secondsSinceLast < MIN_SECONDS_BETWEEN_REQUESTS) {
            val wait = MIN_SECONDS_BETWEEN_REQUESTS - secondsSinceLast
            return Result.Denied("Slow down a little 🙂 Try again in $wait seconds.")
        }

        if (todaysCount(context) >= MAX_REQUESTS_PER_DAY) {
            return Result.Denied(
                "You've used all $MAX_REQUESTS_PER_DAY coach messages for today. " +
                    "Time to put the phone down and focus. See you tomorrow! 💪"
            )
        }

        return Result.Allowed
    }

    /** Call right before a request is sent to the API. */
    fun recordRequest(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_COUNT, todaysCount(context) + 1)
            .putLong(KEY_LAST_REQUEST, System.currentTimeMillis())
            .apply()
    }

    fun remainingToday(context: Context): Int =
        (MAX_REQUESTS_PER_DAY - todaysCount(context)).coerceAtLeast(0)

    private fun todaysCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(KEY_DAY, null) == today()) prefs.getInt(KEY_COUNT, 0) else 0
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
