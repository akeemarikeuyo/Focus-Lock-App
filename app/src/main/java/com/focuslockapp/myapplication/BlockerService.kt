package com.focuslockapp.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
// 🔥 ADDED IMPORT
import com.google.firebase.analytics.FirebaseAnalytics
import java.text.SimpleDateFormat
import java.util.*

class BlockerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var blockedPackages: List<String> = ArrayList()
    private var isRunning = false

    // Foreground-app detection state: seeded once from a stats snapshot, then
    // kept current via cheap incremental UsageEvents queries (see checkAndBlock).
    private var currentForegroundApp: String? = null
    private var lastEventCheckTime = 0L

    // 🔥 ADDED VARIABLE
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    // Default times (match the home screen's defaults)
    private var startHour = FocusState.DEFAULT_START_HOUR; private var startMin = 0
    private var endHour = FocusState.DEFAULT_END_HOUR; private var endMin = 0
    private var lastMarkedFocusDay: String? = null
    private var repeatDaily = true
    private var manualBlockActivationDate: String? = null

    // 🔴 THE AUTOMATIC KILL LIST
    private val SOCIAL_PACKAGES = listOf(
        "com.instagram.android",       // Instagram
        "com.facebook.katana",         // Facebook
        "com.zhiliaoapp.musically",    // TikTok
        "com.ss.android.ugc.trill",    // TikTok (Global)
        "com.snapchat.android",        // Snapchat
        "com.twitter.android",         // X (Twitter)
        "com.reddit.frontpage",        // Reddit
        "com.google.android.youtube",  // YouTube
        "com.netflix.mediaclient",     // Netflix
        "com.discord"                  // Discord
    )

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // 🔥 ADDED INITIALIZATION
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        loadConfigFromPrefs()

        // Seed the current foreground app once from a stats snapshot so a service
        // (re)start doesn't miss an app the user is already sitting inside of —
        // after this, checkAndBlock() tracks foreground changes via UsageEvents only.
        currentForegroundApp = queryCurrentForegroundAppFromStats()
        lastEventCheckTime = System.currentTimeMillis()
    }

    private fun queryCurrentForegroundAppFromStats(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 60000, time)
        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()

        // Everything is saved to prefs before the service is started (see FocusState), so the
        // service restarts identically after a reboot, an app update or a tile tap.
        loadConfigFromPrefs()

        if (!isRunning) {
            isRunning = true
            handler.post(checkRunnable)
        }

        return START_STICKY
    }

    private fun loadConfigFromPrefs() {
        val prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
        val savedSet = prefs.getStringSet("saved_apps", emptySet()) ?: emptySet()
        blockedPackages = savedSet.toList()

        startHour = prefs.getInt("START_HOUR", FocusState.DEFAULT_START_HOUR)
        startMin = prefs.getInt("START_MIN", 0)
        endHour = prefs.getInt("END_HOUR", FocusState.DEFAULT_END_HOUR)
        endMin = prefs.getInt("END_MIN", 0)
        repeatDaily = prefs.getBoolean("REPEAT_DAILY", true)
        manualBlockActivationDate = prefs.getString("MANUAL_BLOCK_ACTIVATION_DATE", null)
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            // We run logic every 500ms
            checkAndBlock()

            if (isRunning) handler.postDelayed(this, 500)
        }
    }

    private fun checkAndBlock() {
        updateCurrentForegroundApp()

        val now = System.currentTimeMillis()

        // --- 1. IS ANYTHING BEING BLOCKED RIGHT NOW? ---
        val isAiActive = FocusState.isAiLockActive(this, now)
        val isManualActive = FocusState.isManualBlockingNow(this)
        if (!isAiActive && !isManualActive) {
            // Window over (or not started): shut down — the notification goes away — and let
            // FocusState set an alarm for the next start time. It also expires finished locks.
            FocusState.syncService(this)
            return
        }
        refreshStatusNotification(now)

        markFocusDayOnce()

        val currentApp = currentForegroundApp ?: return
        // Never block ourselves
        if (currentApp == packageName) return

        // --- 2. THE ACTIVE BLOCK LIST ---
        // Selected apps are blocked by either lock; the coach lock also adds social apps.
        val effectiveBlockedApps = HashSet<String>(blockedPackages)
        if (isAiActive) effectiveBlockedApps.addAll(SOCIAL_PACKAGES)

        // --- 3. BLOCK THE APP ---
        if (effectiveBlockedApps.contains(currentApp)) {
            FocusState.recordBlock(this)

            // We send the specific app name so you know WHICH app they tried to open
            val bundle = Bundle()
            bundle.putString("app_name", currentApp)
            firebaseAnalytics.logEvent("distraction_blocked", bundle)

            // Launch Block Screen
            val blockIntent = Intent(this, BlockedActivity::class.java)
            blockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(blockIntent)

            // Our block screen is now in front. Without this, the next 500 ms checks would still
            // see the blocked app (its foreground event arrives first) and count it 2-3 times.
            currentForegroundApp = packageName
        }
    }

    private fun markFocusDayOnce() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (lastMarkedFocusDay == today) return
        lastMarkedFocusDay = today
        FocusState.markFocusDay(this)
    }

    /**
     * Advances [currentForegroundApp] using only the events that happened since the
     * last check, instead of re-querying and re-sorting a rolling stats window every
     * cycle. [currentForegroundApp] was already seeded once in onCreate().
     */
    @Suppress("DEPRECATION") // MOVE_TO_FOREGROUND is required for minSdk 24; ACTIVITY_RESUMED (same value) only exists from API 29
    private fun updateCurrentForegroundApp() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        val usageEvents = usageStatsManager.queryEvents(lastEventCheckTime, now)
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentForegroundApp = event.packageName
            }
        }

        lastEventCheckTime = now
    }

    private val channelId = "FocusServiceChannel"
    private var lastStatusText: String? = null
    private var lastStatusCheck = 0L

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Focus Mode Running", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val status = FocusState.statusText(this)
        lastStatusText = status
        startForeground(1, buildNotification(status))
    }

    /**
     * Keeps the ongoing notification honest: "Scheduled: blocking starts at 6:50 PM" vs
     * "Blocking until 6:30 AM". Checked every 5 s; only re-posted when the text changes.
     */
    private fun refreshStatusNotification(now: Long) {
        if (now - lastStatusCheck < 5_000) return
        lastStatusCheck = now
        val status = FocusState.statusText(this)
        if (status == lastStatusText) return
        lastStatusText = status
        getSystemService(NotificationManager::class.java)?.notify(1, buildNotification(status))
    }

    private fun buildNotification(status: String) = NotificationCompat.Builder(this, channelId)
        .setContentTitle("Focus Lock")
        .setContentText(status)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            android.app.PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(checkRunnable)
    }
}