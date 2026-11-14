package com.focuslockapp.myapplication

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class CoachResponse {
    data class Message(val text: String) : CoachResponse()
    data class ScheduleRequest(
        val reason: String,
        val startDate: String?,
        val startTime: String?,
        val endDate: String?,
        val endTime: String?,
    ) : CoachResponse()
}

object FocusCoach {

    private val scheduleBlockFunction = FunctionDeclaration(
        name = "schedule_focus_block",
        description = "Call this when the user wants to schedule, block, or lock distracting apps for a " +
            "period of time (e.g. to study, sleep, work, or focus). Do not call this for normal conversation.",
        parameters = mapOf(
            "reason" to Schema.string("Short label for the block, e.g. Sleep, Work, Gym, Study, Focus"),
            "start_date" to Schema.string("Start date in YYYY-MM-DD format"),
            "start_time" to Schema.string("Start time in HH:MM 24-hour format"),
            "end_date" to Schema.string("End date in YYYY-MM-DD format"),
            "end_time" to Schema.string("End time in HH:MM 24-hour format"),
        ),
        optionalParameters = listOf("start_date", "start_time"),
    )

    // Requests go through Firebase AI Logic: the Gemini key stays in the Firebase project,
    // never in the app, and each call is verified with App Check.
    private val model by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            // gemini-3.8-flash / gemini-flash-latest hung until server timeout for this project
            // (Nov 2026); gemini-3.5-flash answers in ~9s and handles the scheduling tool correctly.
            modelName = "gemini-3.5-flash",
            // Cap reply length so each request costs a predictable, small amount. Thinking is off:
            // thinking tokens count toward maxOutputTokens and caused empty MAX_TOKENS replies.
            generationConfig = generationConfig {
                maxOutputTokens = 300
                thinkingConfig = thinkingConfig { thinkingBudget = 0 }
            },
            tools = listOf(Tool.functionDeclarations(listOf(scheduleBlockFunction))),
            // Fail fast instead of leaving the user on "Thinking..." for minutes.
            requestOptions = RequestOptions(timeoutInMillis = 30_000),
        )
    }

    suspend fun getAdvice(
        userMessage: String,
        userName: String,
        blockedCount: Int,
        streakDays: Int,
        recentMessages: List<ChatMessage>,
    ): CoachResponse {
        return withContext(Dispatchers.IO) {
            try {
                val now = Date()
                val currentDate = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.US).format(now)
                // The time is needed for requests like "block for 2 hours" or "until midnight".
                val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(now)

                val conversation = if (recentMessages.isEmpty()) "(none)" else
                    recentMessages.joinToString("\n") { m ->
                        (if (m.isUser) "User: " else "FocusCoach: ") + m.text.take(300)
                    }

                val prompt = """
                    System: You are "FocusCoach".
                    CURRENT DATE: $currentDate
                    CURRENT TIME: $currentTime (24-hour, user's local time)
                    User Name: $userName
                    Distractions blocked so far: $blockedCount
                    Current focus streak: $streakDays day(s)

                    INSTRUCTIONS:
                    1. If the user chats normally, reply with short, punchy advice.
                    2. If the user asks to schedule or block apps, call the schedule_focus_block tool
                       instead of replying with text. Infer the 'reason' from their message; if unsure, use 'Focus'.
                       Only set start_date/start_time if the user asked for a later start; otherwise leave them out.
                    3. Use the recent conversation for context, but answer the latest User Input.

                    RECENT CONVERSATION:
                    $conversation

                    User Input: "$userMessage"
                """.trimIndent()

                val response = model.generateContent(prompt)

                val scheduleCall = response.functionCalls.firstOrNull { it.name == "schedule_focus_block" }
                if (scheduleCall != null) {
                    val args = scheduleCall.args
                    CoachResponse.ScheduleRequest(
                        reason = args.str("reason") ?: "Focus",
                        startDate = args.str("start_date"),
                        startTime = args.str("start_time"),
                        endDate = args.str("end_date"),
                        endTime = args.str("end_time"),
                    )
                } else {
                    CoachResponse.Message(response.text ?: "Focus.")
                }
            } catch (t: Throwable) {
                // Log details for debugging, but don't show raw API errors to the user.
                Log.w("FocusCoach", "Gemini request failed", t)
                CoachResponse.Message("I couldn't reach my coaching brain right now. Try again in a bit.")
            }
        }
    }

    private fun Map<String, JsonElement>.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}
