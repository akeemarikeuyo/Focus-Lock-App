package com.focuslockapp.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Keeps the coach conversation across app restarts (last [MAX_MESSAGES] messages). */
object ChatHistory {

    const val THINKING = "Coach is thinking…"
    private const val OLD_THINKING = "Thinking..."
    private const val PREFS = "ChatHistory"
    private const val KEY = "MESSAGES"
    private const val MAX_MESSAGES = 60

    fun load(context: Context): List<ChatMessage> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                ChatMessage(o.getString("t"), o.getBoolean("u"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.filter { it.text != THINKING && it.text != OLD_THINKING }.takeLast(MAX_MESSAGES).forEach {
            array.put(JSONObject().put("t", it.text).put("u", it.isUser))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
