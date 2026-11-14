package com.focuslockapp.myapplication
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private val messageList = ArrayList<ChatMessage>()

    private lateinit var etInput: EditText
    private lateinit var btnSend: Button

    private val GOOGLE_SCRIPT_URL by lazy { getString(R.string.sheets_webhook_url) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        MobileAds.initialize(this) {}
        val mAdView = findViewById<AdView>(R.id.adViewChat)
        mAdView.loadAd(AdRequest.Builder().build())

        recyclerView = findViewById(R.id.recyclerViewChat)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)

        adapter = ChatAdapter(messageList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Restore the previous conversation; only greet when there is nothing to show
        // or onboarding (name/email) isn't finished.
        val history = ChatHistory.load(this)
        messageList.addAll(history)
        adapter.notifyDataSetChanged()
        if (messageList.isNotEmpty()) recyclerView.scrollToPosition(messageList.size - 1)

        if (history.isEmpty() || !isOnboarded()) checkOnboardingStatus()

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        btnSend.setOnClickListener {
            val msg = etInput.text.toString().trim()
            if (msg.isNotEmpty()) {
                handleUserMessage(msg)
                etInput.text.clear()
            }
        }

        setUpQuickReplies()
        updateCoachExtras()
    }

    private fun isOnboarded(): Boolean {
        val p = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        return p.getString("USER_NAME", null) != null && p.getString("USER_EMAIL", null) != null
    }

    /** One-tap prompts that show new users what the coach can do. */
    private fun setUpQuickReplies() {
        val group = findViewById<ChipGroup>(R.id.chipGroupQuickReplies)
        listOf(
            "🔒 Block socials for 1 hour",
            "😩 I can't focus",
            "📚 Plan my study session",
            "🌙 Lock my apps tonight from 10pm to 7am",
        ).forEach { text ->
            val chip = Chip(this).apply {
                this.text = text
                isCheckable = false
                // Match the chat colours (Material's default chip tint is purple).
                setChipBackgroundColorResource(R.color.chat_surface)
                setChipStrokeColorResource(R.color.divider)
                setTextColor(getColor(R.color.text_primary))
                setOnClickListener {
                    // Ignore taps while a reply is loading (same rule as the Send button).
                    if (btnSend.isEnabled) handleUserMessage(text)
                }
            }
            group.addView(chip)
        }
    }

    /** Quick replies + "N messages left" only make sense once name/email onboarding is done. */
    private fun updateCoachExtras() {
        val visible = if (isOnboarded()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.quickReplies).visibility = visible
        val tvLeft = findViewById<TextView>(R.id.tvMessagesLeft)
        tvLeft.visibility = visible
        val left = CoachRateLimiter.remainingToday(this)
        tvLeft.text = when (left) {
            0 -> "No coach messages left today. Back tomorrow 💪"
            1 -> "1 coach message left today"
            else -> "$left coach messages left today"
        }
    }

    private fun checkOnboardingStatus() {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val userName = prefs.getString("USER_NAME", null)
        val userEmail = prefs.getString("USER_EMAIL", null)

        if (userName == null) {
            appendChat("FocusCoach", "Hello! I am your personal accountability partner. What should I call you?")
        } else if (userEmail == null) {
            appendChat("FocusCoach", "Welcome back, $userName! To send you progress updates, what is your email address?")
        } else {
            appendChat("FocusCoach", "Hi $userName! I'm ready. What's distracting you right now?")
        }
    }

    private fun handleUserMessage(msg: String) {
        appendChat("Me", msg)

        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val lowerMsg = msg.lowercase().trim()

        if (lowerMsg == "reset profile" || lowerMsg == "change name" || lowerMsg == "reset") {
            prefs.edit().remove("USER_NAME").remove("USER_EMAIL").apply()
            clearConversation()
            appendChat("FocusCoach", "🔄 Profile reset! Let's start over.")
            appendChat("FocusCoach", "Hello! I am your personal accountability partner. What should I call you?")
            updateCoachExtras()
            return
        }

        if (lowerMsg == "clear chat" || lowerMsg == "clear") {
            clearConversation()
            appendChat("FocusCoach", "🧹 Chat cleared. What's distracting you right now?")
            return
        }

        val userName = prefs.getString("USER_NAME", null)
        val userEmail = prefs.getString("USER_EMAIL", null)

        lifecycleScope.launch {
            if (userName == null) {
                val cleanName = extractNameFromText(msg)
                prefs.edit().putString("USER_NAME", cleanName).apply()
                appendChat("FocusCoach", "Nice to meet you, $cleanName! What is your email address?")
                return@launch
            }

            if (userEmail == null) {
                if (isValidEmail(msg)) {
                    prefs.edit().putString("USER_EMAIL", msg).apply()
                    sendToGoogleSheet(userName, msg)
                    appendChat("FocusCoach", "All set! Tell me what you want to achieve today, or tap one of the ideas below.")
                    updateCoachExtras()
                } else {
                    appendChat("FocusCoach", "That doesn't look like a valid email.")
                }
                return@launch
            }

            val blockedCount = FocusState.blockCount(this@ChatActivity)
            val streak = FocusState.streakDays(this@ChatActivity)

            val limit = CoachRateLimiter.check(this@ChatActivity, msg)
            if (limit is CoachRateLimiter.Result.Denied) {
                appendChat("FocusCoach", limit.reason)
                return@launch
            }
            CoachRateLimiter.recordRequest(this@ChatActivity)
            updateCoachExtras()

            // Recent conversation (before this message) so the coach remembers context.
            val recent = messageList.dropLast(1).filter { it.text != ChatHistory.THINKING }.takeLast(6)

            appendChat("FocusCoach", ChatHistory.THINKING)
            btnSend.isEnabled = false

            val response = try {
                FocusCoach.getAdvice(msg, userName, blockedCount, streak, recent)
            } finally {
                btnSend.isEnabled = true
            }

            when (response) {
                is CoachResponse.ScheduleRequest -> {
                    replaceLastMessage("Got it. Checking your schedule...")
                    handleSchedulingCommand(response)
                }
                is CoachResponse.Message -> replaceLastMessage(response.text)
            }
        }
    }

    private fun handleSchedulingCommand(request: CoachResponse.ScheduleRequest) {
        try {
            val modeName = request.reason.replaceFirstChar { it.uppercase() }
            val now = System.currentTimeMillis()
            val dayMs = 24 * 60 * 60 * 1000L

            // START: honour a requested start time (e.g. "from 8pm"); a missing or past
            // start means "now". Previously the start time was ignored and every lock began
            // immediately, even though the dialog showed the requested start.
            var startMillis = parseDateTime(request.startDate, request.startTime)?.timeInMillis ?: now
            if (startMillis < now) startMillis = now

            // END: required. Without an explicit date, roll forward to the next occurrence
            // after the start (e.g. "until 2am" tonight means tomorrow 02:00).
            val endCal = parseDateTime(request.endDate, request.endTime)
            if (endCal == null) {
                appendChat("FocusCoach", "When should the lock end? Try something like \"until 6pm\".")
                return
            }
            var endMillis = endCal.timeInMillis
            if (request.endDate == null) {
                while (endMillis <= startMillis) endMillis += dayMs
            }
            if (endMillis <= startMillis) {
                appendChat("FocusCoach", "That end time is already in the past. When should the lock end?")
                return
            }

            val startsLater = startMillis - now > 60_000
            val startLabel = if (startsLater) formatWhen(startMillis) else "Now"
            val confirmMessage = """
                🟢 START:
                $startLabel

                🔴 END:
                ${formatWhen(endMillis)}

                (Apps will unlock automatically at the End time).
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("Confirm $modeName Mode? 🔒")
                .setMessage(confirmMessage)
                .setPositiveButton("YES, LOCK IT") { _, _ ->
                    val lock = PendingCoachLock(startMillis, endMillis, modeName, startsLater)
                    if (SetupPermissions.isReady(this)) {
                        startCoachLock(lock)
                    } else {
                        // Blocking can't work without the 2 setup permissions — do setup first,
                        // then start this exact lock when the user comes back.
                        pendingCoachLock = lock
                        appendChat("FocusCoach", "One moment: Focus Lock needs 2 quick permissions before it can block apps.")
                        setupLauncher.launch(Intent(this, SetupActivity::class.java))
                    }
                }
                .setNegativeButton("Cancel") { _, _ ->
                    appendChat("FocusCoach", "Okay, I cancelled the block.")
                }
                .show()

        } catch (e: Exception) {
            appendChat("FocusCoach", "I tried to schedule that, but I got confused. Try again?")
        }
    }

    private data class PendingCoachLock(val start: Long, val end: Long, val mode: String, val startsLater: Boolean)
    private var pendingCoachLock: PendingCoachLock? = null

    private val setupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val lock = pendingCoachLock
        pendingCoachLock = null
        if (lock != null && result.resultCode == RESULT_OK && SetupPermissions.isReady(this)) {
            startCoachLock(lock)
        } else if (lock != null) {
            appendChat("FocusCoach", "No problem. Finish the 2 quick steps whenever you're ready and ask me again.")
        }
    }

    private fun startCoachLock(lock: PendingCoachLock) {
        FocusState.startAiLock(this, lock.start, lock.end, lock.mode)
        if (lock.startsLater) {
            appendChat("FocusCoach", "⏰ Scheduled. ${lock.mode} Mode starts ${formatWhen(lock.start)} and ends ${formatWhen(lock.end)}.")
        } else {
            appendChat("FocusCoach", "🔒 DONE. ${lock.mode} Mode is active until ${formatWhen(lock.end)}.")
        }
    }

    /** "YYYY-MM-DD" + "HH:MM" from the coach → Calendar (today if no date). Null if no usable time. */
    private fun parseDateTime(date: String?, time: String?): Calendar? {
        val parts = time?.trim()?.split(":") ?: return null
        if (parts.size < 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().take(2).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        val cal = Calendar.getInstance()
        if (!date.isNullOrBlank()) {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date.trim())?.let { cal.time = it }
            } catch (e: Exception) {
                // Unparseable date: keep today.
            }
        }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    /** e.g. "today at 6:00 PM", "tomorrow at 2:00 AM", "Mon 17 Nov at 8:00 PM". */
    private fun formatWhen(millis: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(target.time)
        return when {
            sameDay(target, today) -> "today at $time"
            sameDay(target, tomorrow) -> "tomorrow at $time"
            else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(target.time) + " at $time"
        }
    }

    private suspend fun sendToGoogleSheet(name: String, email: String) {
        withContext(Dispatchers.IO) {
            try {
                val url = URL(GOOGLE_SCRIPT_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                val json = JSONObject().put("name", name).put("email", email)
                conn.outputStream.write(json.toString().toByteArray())
                conn.responseCode
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun appendChat(sender: String, message: String) {
        val isUser = sender == "Me"
        messageList.add(ChatMessage(message, isUser))
        adapter.notifyItemInserted(messageList.size - 1)
        recyclerView.scrollToPosition(messageList.size - 1)
        ChatHistory.save(this, messageList)
    }

    /** Swaps the "Thinking..." bubble for the real reply. */
    private fun replaceLastMessage(text: String) {
        val lastIndex = messageList.size - 1
        if (lastIndex < 0) return
        messageList[lastIndex] = ChatMessage(text, false)
        adapter.notifyItemChanged(lastIndex)
        recyclerView.scrollToPosition(lastIndex)
        ChatHistory.save(this, messageList)
    }

    private fun clearConversation() {
        ChatHistory.clear(this)
        val size = messageList.size
        messageList.clear()
        adapter.notifyItemRangeRemoved(0, size)
    }

    private fun isValidEmail(email: String): Boolean {
        return Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun extractNameFromText(input: String): String {
        return input.replace("call me ", "", true)
            .replace("my name is ", "", true).trim()
    }
}