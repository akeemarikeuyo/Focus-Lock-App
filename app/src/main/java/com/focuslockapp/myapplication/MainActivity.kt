package com.focuslockapp.myapplication

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
// --- FIREBASE IMPORTS FIXED ---
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var appList: ListView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnStartTime: Button
    private lateinit var btnEndTime: Button
    private lateinit var checkRepeat: CompoundButton
    private lateinit var tvAttemptsCount: TextView

    private lateinit var mAdView: AdView
    private var mInterstitialAd: InterstitialAd? = null
    private var isStopPending = false
    private val adWaitHandler = Handler(Looper.getMainLooper())
    private var adWaitRunnable: Runnable? = null

    private lateinit var firebaseAnalytics: FirebaseAnalytics

    data class AppItem(
        val name: String,
        val packageName: String,
        var isSelected: Boolean,
        val isSocial: Boolean,
        val icon: Drawable,
    )
    private val appItems = ArrayList<AppItem>()
    private var displayedItems: List<AppItem> = emptyList()
    private lateinit var appListAdapter: AppListAdapter

    private val socialPackages = listOf(
        "instagram", "tiktok", "zhiliaoapp", "musically",
        "facebook", "twitter", "snapchat",
        "whatsapp", "telegram", "youtube", "netflix", "pinterest",
        "reddit", "discord", "wechat"
    )

    private var startHour = 9; private var startMin = 0
    private var endHour = 17; private var endMin = 0

    companion object {
        private const val AD_WAIT_TIMEOUT_MS = 6000L

        /**
         * The 9am reminder now comes from Firebase Cloud Messaging (see FocusMessagingService).
         * Older versions set a local 9am alarm that re-armed itself daily — and silently died
         * once the app was put to sleep. Cancel it so nobody gets the reminder twice.
         */
        fun cancelLegacyReminderAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = PendingIntent.getBroadcast(
                context, 100, Intent(context, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: return
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. FORCE FIREBASE INITIALIZATION FIRST
        try {
            FirebaseApp.initializeApp(this)
            firebaseAnalytics = FirebaseAnalytics.getInstance(this)
            // Log a test event immediately to verify connection
            val bundle = Bundle()
            bundle.putString(FirebaseAnalytics.Param.ITEM_NAME, "App Launched")
            firebaseAnalytics.logEvent(FirebaseAnalytics.Event.APP_OPEN, bundle)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContentView(R.layout.activity_main)

        // 2. Initialize Ads
        try {
            MobileAds.initialize(this) {}
            val adViewCheck = findViewById<AdView>(R.id.adView)
            if (adViewCheck != null) {
                mAdView = adViewCheck
                val bannerRequest = AdRequest.Builder().build()
                mAdView.loadAd(bannerRequest)
            }
            loadPunishmentAd()
        } catch (e: Exception) { e.printStackTrace() }

        // 3. Share Button Logic
        val shareCard = findViewById<CardView>(R.id.cardDistractionShare)
        shareCard?.setOnClickListener {
            val distractionsBlocked = FocusState.blockCount(this)
            val streak = FocusState.streakDays(this)
            val playStoreLink = "https://play.google.com/store/apps/details?id=$packageName"
            val streakText = if (streak >= 2) " and I'm on a $streak-day focus streak 🔥" else ""
            val shareMessage = "I've blocked $distractionsBlocked distractions with Focus Lock App🔒$streakText. Can you beat my focus score?\n\n$playStoreLink"

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareMessage)
                type = "text/plain"
            }
            startActivity(Intent.createChooser(sendIntent, "Flex your Focus Score"))

            // Log Share Event to Firebase
            firebaseAnalytics.logEvent("share_score_clicked", null)
        }

        initUI()

        try {
            cancelLegacyReminderAlarm(this)
            enableBootReceiver()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enableBootReceiver() {
        val receiver = ComponentName(this, BootReceiver::class.java)
        packageManager.setComponentEnabledSetting(
            receiver,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }

    // Re-checks the status card while the screen is open (e.g. "Scheduled" -> "Blocking now").
    private val uiRefreshHandler = Handler(Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            uiRefreshHandler.postDelayed(this, 30_000)
        }
    }

    override fun onResume() {
        super.onResume()
        // If a lock should be running but its service was killed, bring it back.
        FocusState.syncService(this)
        uiRefreshHandler.removeCallbacks(uiRefreshRunnable)
        uiRefreshHandler.post(uiRefreshRunnable)
        checkAndPromptPermissions()
    }

    override fun onPause() {
        super.onPause()
        uiRefreshHandler.removeCallbacks(uiRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        adWaitRunnable?.let { adWaitHandler.removeCallbacks(it) }
    }

    /** Stats card, status card, and which main button is shown. */
    private fun refreshStats() {
        if (!::tvAttemptsCount.isInitialized) return
        val count = FocusState.blockCount(this)
        tvAttemptsCount.text = "$count"
        findViewById<TextView>(R.id.tvAttemptsTitle)?.text =
            if (count == 1) "distraction blocked" else "distractions blocked"
        val streak = FocusState.streakDays(this)
        findViewById<TextView>(R.id.tvStreak)?.text = when (streak) {
            0 -> "Start a focus session to begin a streak"
            1 -> "🔥 1-day focus streak"
            else -> "🔥 $streak-day focus streak"
        }

        // Status card
        val blockingNow = FocusState.isAiLockActive(this) || FocusState.isManualBlockingNow(this)
        val lockOn = FocusState.shouldServiceRun(this)
        val (title, colorRes) = when {
            blockingNow -> "Blocking now" to R.color.status_on
            lockOn -> "Scheduled" to R.color.status_scheduled
            else -> "Lock is off" to R.color.status_off
        }
        findViewById<TextView>(R.id.tvStatusTitle)?.text = title
        findViewById<TextView>(R.id.tvStatusDetail)?.text = FocusState.statusDetail(this)
        findViewById<View>(R.id.viewStatusDot)?.background?.mutate()?.setTint(getColor(colorRes))

        // One main button: Activate when off, Stop (with ad hint) when on
        btnStart.visibility = if (lockOn) View.GONE else View.VISIBLE
        btnStop.visibility = if (lockOn) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tvStopHint)?.visibility = if (lockOn) View.VISIBLE else View.GONE

        // Schedule can't be edited while manual focus mode is on — make that visible.
        val editable = !FocusState.isManualActive(this)
        val alpha = if (editable) 1f else 0.45f
        btnStartTime.alpha = alpha
        btnEndTime.alpha = alpha
        checkRepeat.alpha = alpha
        updateSelectedCount()
    }

    private fun updateSelectedCount() {
        val count = appItems.count { it.isSelected }
        findViewById<TextView>(R.id.tvSelectedCount)?.text = when (count) {
            0 -> "No apps selected"
            1 -> "1 app selected"
            else -> "$count apps selected"
        }
    }

    /** True (and tells the user) if focus mode is on, so its schedule can't be edited. */
    private fun blockIfLocked(): Boolean {
        if (!FocusState.isManualActive(this)) return false
        Toast.makeText(this, "Stop the lock first to change apps or times.", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun checkAndPromptPermissions() {
        // 1. Check Overlay (Draw over other apps)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showInstructionalDialog(
                "Step 1: Appear on Top",
                "Focus Lock App needs to block distractions. Find 'Focus Lock App' in the list and switch it ON.",
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName"
            )
            return
        }

        // 2. Check Usage Stats (Detecting what app is open)
        if (!hasUsageStatsPermission()) {
            showInstructionalDialog(
                "Step 2: Detect Distractions",
                "Find 'Focus Lock App' in the list (usually under 'F') and allow access so we can block social media.",
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                null // Usage stats doesn't always support direct package URI, so we coach the user
            )
            return
        }

        // 3. Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                val prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
                val alreadyAskedOnce = prefs.getBoolean("REQUESTED_NOTIF_PERMISSION", false)
                // If we've asked before and the system no longer offers a rationale, the user
                // permanently denied it — the only way back is the app's notification settings.
                if (alreadyAskedOnce && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    showInstructionalDialog(
                        "Step 3: Turn Reminders Back On",
                        "Notifications are off, so your daily check-in and focus alerts can't reach you. Enable them in Settings.",
                        Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        null
                    )
                } else {
                    prefs.edit().putBoolean("REQUESTED_NOTIF_PERMISSION", true).apply()
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
                return
            }
        }

        // 4. Exact Alarms (Android 12+): the blocker sleeps between lock windows and is woken
        // by an alarm at the next start time. Without this permission that alarm is inexact,
        // so a lock could start minutes late.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                showInstructionalDialog(
                    "Step 4: Start Locks On Time",
                    "Focus Lock App needs 'Alarms & reminders' access so your scheduled locks " +
                        "start and end exactly on time.",
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:$packageName"
                )
                return
            }
        }

        // 5. Battery (asked once, optional): phone makers — Samsung especially — put apps to
        // sleep after a few days unused, which delays locks and reminders. Play policy prefers
        // sending users to the setting over requesting the exemption directly.
        maybeSuggestUnrestrictedBattery()
    }

    private fun maybeSuggestUnrestrictedBattery() {
        val prefs = FocusState.prefs(this)
        if (prefs.getBoolean("BATTERY_PROMPT_SHOWN", false)) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("BATTERY_PROMPT_SHOWN", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Keep your locks reliable 🔋")
            .setMessage(
                "Your phone may put Focus Lock to sleep to save battery, which can delay locks " +
                    "and your morning reminder.\n\nOn the next screen, tap Battery and choose " +
                    "Unrestricted."
            )
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", packageName, null))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun showInstructionalDialog(title: String, message: String, action: String, uriString: String?) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setCancelable(false)

        // We use a custom icon to help them recognize the app in the list
        builder.setIcon(R.mipmap.ic_launcher)

        builder.setPositiveButton("Take Me There") { _, _ ->
            // Show a "Long" toast that stays on screen while the settings open
            Toast.makeText(this, "Scroll to find Focus Lock Lock 🔒", Toast.LENGTH_LONG).show()

            val intent = Intent(action)

            // STRESS TEST: Attempting to Deep Link to our specific app settings
            if (uriString != null) {
                intent.data = android.net.Uri.parse(uriString)
            } else if (action == Settings.ACTION_USAGE_ACCESS_SETTINGS) {
                // Some devices support this to highlight the app automatically
                try {
                    intent.data = android.net.Uri.fromParts("package", packageName, null)
                } catch (e: Exception) {
                    // Fallback to general list if deep link is rejected
                }
            } else if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                // This screen is targeted via an extra, not a data URI
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }

            try {
                startActivity(intent)
            } catch (e: Exception) {
                // Ultimate fallback: Just open settings
                startActivity(Intent(action))
            }
        }

        builder.setNegativeButton("Later") { dialog, _ ->
            dialog.dismiss()
            Toast.makeText(this, "App won't work without this permission.", Toast.LENGTH_SHORT).show()
        }

        builder.show()
    }

    private fun initUI() {
        appList = findViewById(R.id.appList)
        btnStart = findViewById(R.id.btnStartBlock)
        btnStop = findViewById(R.id.btnStopBlock)
        btnStartTime = findViewById(R.id.btnStartTime)
        btnEndTime = findViewById(R.id.btnEndTime)
        checkRepeat = findViewById(R.id.checkRepeat)
        tvAttemptsCount = findViewById(R.id.tvAttemptsCount)

        findViewById<Button>(R.id.btnSelectSocial)?.setOnClickListener {
            if (blockIfLocked()) return@setOnClickListener
            val isSelecting = (it as Button).text.toString() == "Select Socials"
            for (item in appItems) {
                if (item.isSocial && !item.packageName.contains("whatsapp")) {
                    item.isSelected = isSelecting
                }
            }
            appListAdapter.notifyDataSetChanged()
            updateSelectedCount()
            it.text = if (isSelecting) "Unselect Socials" else "Select Socials"
        }

        findViewById<EditText>(R.id.etAppSearch)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterAppList(s?.toString().orEmpty())
            }
        })

        findViewById<View>(R.id.btnCoach)?.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        loadSavedSettings()
        btnStartTime.setOnClickListener { if (!blockIfLocked()) showTimePicker(true) }
        btnEndTime.setOnClickListener { if (!blockIfLocked()) showTimePicker(false) }

        checkRepeat.setOnClickListener {
            // Changing the schedule mid-session would be a free way out of the lock.
            if (blockIfLocked()) {
                checkRepeat.isChecked = FocusState.prefs(this).getBoolean(FocusState.KEY_REPEAT_DAILY, true)
            } else {
                FocusState.prefs(this).edit().putBoolean(FocusState.KEY_REPEAT_DAILY, checkRepeat.isChecked).apply()
            }
        }

        btnStart.setOnClickListener {
            if (!hasUsageStatsPermission() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this))) {
                checkAndPromptPermissions()
            } else {
                startBlockingService()
                firebaseAnalytics.logEvent("focus_mode_started", null)
            }
        }

        btnStop.setOnClickListener { showAdAndStop() }
        loadInstalledApps()
    }

    private fun loadPunishmentAd() {
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(this, "ca-app-pub-4473657267431851/7118665351", adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    mInterstitialAd = null
                    // If the user is waiting on this load to unlock, don't leave them stuck —
                    // unlock now instead of silently giving up (see cancelAdWaitAndUnlock).
                    if (isStopPending) cancelAdWaitAndUnlock("Couldn't load an ad — unlocking anyway.")
                }
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    mInterstitialAd = interstitialAd
                    if (isStopPending) {
                        isStopPending = false
                        adWaitRunnable?.let { adWaitHandler.removeCallbacks(it) }
                        adWaitRunnable = null
                        showAdAndStop()
                    }
                }
            })
    }

    /** Stops the block immediately, cancelling any pending ad-wait timeout, with a toast explaining why. */
    private fun cancelAdWaitAndUnlock(message: String) {
        isStopPending = false
        adWaitRunnable?.let { adWaitHandler.removeCallbacks(it) }
        adWaitRunnable = null
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        stopBlockingService()
    }

    private fun showAdAndStop() {
        if (mInterstitialAd != null) {
            mInterstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    stopBlockingService()
                    mInterstitialAd = null
                    loadPunishmentAd()
                }
                override fun onAdFailedToShowFullScreenContent(adError: AdError) { stopBlockingService(); mInterstitialAd = null }
            }
            mInterstitialAd?.show(this)
        } else {
            isStopPending = true
            // Never make the user wait indefinitely for an ad — give up and unlock after a few seconds.
            adWaitRunnable?.let { adWaitHandler.removeCallbacks(it) }
            val runnable = Runnable {
                if (isStopPending) cancelAdWaitAndUnlock("Ad took too long to load — unlocking anyway.")
            }
            adWaitRunnable = runnable
            adWaitHandler.postDelayed(runnable, AD_WAIT_TIMEOUT_MS)
            Toast.makeText(this, "Loading Ad... Please wait ⏳", Toast.LENGTH_SHORT).show()
            loadPunishmentAd()
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
        startHour = prefs.getInt("START_HOUR", 9)
        startMin = prefs.getInt("START_MIN", 0)
        endHour = prefs.getInt("END_HOUR", 17)
        endMin = prefs.getInt("END_MIN", 0)
        checkRepeat.isChecked = prefs.getBoolean("REPEAT_DAILY", true)
        updateTimeButton(btnStartTime, startHour, startMin, true)
        updateTimeButton(btnEndTime, endHour, endMin, false)
    }

    private fun saveTimeSettings() {
        getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE).edit().apply {
            putInt("START_HOUR", startHour)
            putInt("START_MIN", startMin)
            putInt("END_HOUR", endHour)
            putInt("END_MIN", endMin)
            apply()
        }
    }

    private fun showTimePicker(isStart: Boolean) {
        val listener = TimePickerDialog.OnTimeSetListener { _, hour, minute ->
            if (isStart) { startHour = hour; startMin = minute; updateTimeButton(btnStartTime, startHour, startMin, true) }
            else { endHour = hour; endMin = minute; updateTimeButton(btnEndTime, endHour, endMin, false) }
            saveTimeSettings()
        }
        TimePickerDialog(this, listener, if(isStart) startHour else endHour, if(isStart) startMin else endMin, false).show()
    }

    private fun updateTimeButton(btn: Button, hour: Int, min: Int, isStart: Boolean) {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, min) }
        btn.text = (if (isStart) "Start: " else "End: ") + SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    private fun startBlockingService() {
        val selectedApps = appItems.filter { it.isSelected }.map { it.packageName }.toSet()
        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "Select at least one app to lock first.", Toast.LENGTH_SHORT).show()
            return
        }
        // Saves apps + times + repeat (so a reboot/update/tile restart behaves the same),
        // starts the service, and schedules auto-stop only for one-off sessions — a
        // repeating session must keep running for the next day's window.
        FocusState.startManualFocus(
            this, selectedApps,
            startHour, startMin, endHour, endMin,
            checkRepeat.isChecked,
        )
        refreshStats()
        // Say whether blocking has started or is waiting for the start time — "Active!"
        // alone made a 6:50 pm window look broken when tested at 6:13 pm.
        val title = if (FocusState.isManualBlockingNow(this)) "Focus Mode active 🔒" else "Focus Mode scheduled 🔒"
        Toast.makeText(this, "$title\n${FocusState.statusText(this)}", Toast.LENGTH_LONG).show()
        suggestQuickSettingsTileOnce()
    }

    /**
     * Tiles never appear in Quick Settings on their own — they sit in the Edit list. After the
     * first Start, offer it once: a one-tap system prompt on Android 13+, a short how-to before.
     */
    private fun suggestQuickSettingsTileOnce() {
        val prefs = FocusState.prefs(this)
        if (prefs.getBoolean("TILE_PROMPT_SHOWN", false)) return
        prefs.edit().putBoolean("TILE_PROMPT_SHOWN", true).apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                getSystemService(android.app.StatusBarManager::class.java)?.requestAddTileService(
                    ComponentName(this, FocusTileService::class.java),
                    "Focus Lock",
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                    mainExecutor,
                ) { /* added, already there, or dismissed — nothing else to do */ }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            AlertDialog.Builder(this)
                .setTitle("Tip: start focus in one tap ⚡")
                .setMessage(
                    "Add Focus Lock to your Quick Settings:\n\n" +
                        "1. Swipe down twice from the top of the screen.\n" +
                        "2. Tap the ✏️ Edit (pencil) button.\n" +
                        "3. Drag the Focus Lock tile into your tiles."
                )
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    private fun stopBlockingService() {
        FocusState.stopAll(this)
        refreshStats()
        Toast.makeText(this, "Lock stopped", Toast.LENGTH_SHORT).show()
    }

    private fun loadInstalledApps() {
        val savedApps = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE).getStringSet("saved_apps", emptySet()) ?: emptySet()

        appListAdapter = AppListAdapter()
        appList.adapter = appListAdapter
        appList.setOnItemClickListener { _, _, position, _ ->
            // The running lock uses the apps saved at Start; editing now would only confuse.
            if (blockIfLocked()) return@setOnItemClickListener
            displayedItems.getOrNull(position)?.let { it.isSelected = !it.isSelected }
            appListAdapter.notifyDataSetChanged()
            updateSelectedCount()
        }

        lifecycleScope.launch {
            val loadedItems = withContext(Dispatchers.IO) {
                val pm = packageManager
                // Launcher apps only — works with the manifest's <queries> block, so the app
                // no longer needs the Play-restricted QUERY_ALL_PACKAGES permission.
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launchable = pm.queryIntentActivities(launcherIntent, 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                val items = ArrayList<AppItem>()
                for (info in launchable) {
                    val pkg = info.packageName
                    if (pkg == packageName || pkg == "com.android.settings" || pkg == "com.android.phone" || pkg.contains("launcher")) continue
                    val name = info.loadLabel(pm)?.toString() ?: pkg
                    val icon = info.loadIcon(pm) ?: pm.defaultActivityIcon
                    val isSocial = socialPackages.any { pkg.contains(it) }
                    items.add(AppItem(name, pkg, savedApps.contains(pkg), isSocial, icon))
                }
                items.sortWith(compareByDescending<AppItem> { it.isSelected }.thenByDescending { it.isSocial }.thenBy { it.name })
                items
            }
            appItems.clear()
            appItems.addAll(loadedItems)
            filterAppList(findViewById<EditText>(R.id.etAppSearch)?.text?.toString().orEmpty())
            updateSelectedCount()
        }
    }

    private fun filterAppList(query: String) {
        displayedItems = if (query.isBlank()) {
            appItems
        } else {
            appItems.filter { it.name.contains(query, ignoreCase = true) }
        }
        appListAdapter.notifyDataSetChanged()
    }

    private inner class AppListAdapter : BaseAdapter() {
        override fun getCount() = displayedItems.size
        override fun getItem(position: Int) = displayedItems[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            val item = displayedItems[position]

            val ivIcon = view.findViewById<ImageView>(R.id.ivAppIcon)
            val tvName = view.findViewById<TextView>(R.id.tvAppName)
            val cbSelected = view.findViewById<CheckBox>(R.id.cbAppSelected)
            val tvSocialTag = view.findViewById<TextView>(R.id.tvSocialTag)

            ivIcon.setImageDrawable(item.icon)
            tvName.text = item.name
            // Social apps get a small tag instead of blue text (which looked like a link
            // and was unreadable black-on-dark in dark mode).
            tvSocialTag.visibility = if (item.isSocial) View.VISIBLE else View.GONE
            cbSelected.isChecked = item.isSelected

            return view
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        else appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}