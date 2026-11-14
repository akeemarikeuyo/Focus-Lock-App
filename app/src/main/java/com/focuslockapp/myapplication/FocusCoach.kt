package com.focuslockapp.myapplication

import android.content.Context
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.defineFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val scheduleBlockFunction = defineFunction(
        name = "schedule_focus_block",
        description = "Call this when the user wants to schedule, block, or lock distracting apps for a " +
            "period of time (e.g. to study, sleep, work, or focus). Do not call this for normal conversation.",
        parameters = listOf(
            Schema.str("reason", "Short label for the block, e.g. Sleep, Work, Gym, Study, Focus"),
            Schema.str("start_date", "Start date in YYYY-MM-DD format"),
            Schema.str("start_time", "Start time in HH:MM 24-hour format"),
            Schema.str("end_date", "End date in YYYY-MM-DD format"),
            Schema.str("end_time", "End time in HH:MM 24-hour format"),
        ),
        requiredParameters = listOf("reason", "end_date", "end_time"),
    )

    suspend fun getAdvice(context: Context, userMessage: String, userName: String, blockedCount: Int): CoachResponse {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Safely grab the key from the XML Vault using the context
                val apiKey = context.getString(R.string.gemini_api_key)

                val model = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey,
                    tools = listOf(Tool(functionDeclarations = listOf(scheduleBlockFunction))),
                )

                // 3. Get Today's Date
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val currentDate = sdf.format(Date())

                // 4. The Smart Prompt
                val prompt = """
                    System: You are "FocusCoach".
                    CURRENT DATE: $currentDate
                    User Name: $userName

                    INSTRUCTIONS:
                    1. If the user chats normally, reply with short, punchy advice.
                    2. If the user asks to schedule or block apps, call the schedule_focus_block tool
                       instead of replying with text. Infer the 'reason' from their message; if unsure, use 'Focus'.

                    User Input: "$userMessage"
                """.trimIndent()

                val response = model.generateContent(prompt)

                val scheduleCall = response.functionCalls.firstOrNull { it.name == "schedule_focus_block" }
                if (scheduleCall != null) {
                    CoachResponse.ScheduleRequest(
                        reason = scheduleCall.args["reason"] ?: "Focus",
                        startDate = scheduleCall.args["start_date"],
                        startTime = scheduleCall.args["start_time"],
                        endDate = scheduleCall.args["end_date"],
                        endTime = scheduleCall.args["end_time"],
                    )
                } else {
                    CoachResponse.Message(response.text ?: "Focus.")
                }

            } catch (t: Throwable) {
                CoachResponse.Message("Error: I heard you, but I couldn't process that. (${t.localizedMessage})")
            }
        }
    }
}
