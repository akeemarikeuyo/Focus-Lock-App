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
        // No permission pop-ups on open any more: setup is asked only when the user first taps
        // Activate Lock (see setupLauncher), and the status card shows if anything is missing.
    }

    /** Setup checklist; when it returns OK both required permissions are granted. */
    private var startLockAfterSetup = false
    private val setupLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val shouldStart = startLockAfterSetup
        startLockAfterSetup = false
        if (result.resultCode == RESULT_OK && shouldStart && SetupPermissions.isReady(this)) {
            startBlockingService()
            firebaseAnalytics.logEvent("focus_mode_started", null)
        }
        refreshStats()
    }

    private fun openSetup(thenStartLock: Boolean) {
        startLockAfterSetup = thenStartLock
        val intent = Intent(this, SetupActivity::class.java)
        if (!thenStartLock) intent.putExtra(SetupActivity.EXTRA_BUTTON_TEXT, "Done")
        setupLauncher.launch(intent)
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
        val ready = SetupPermissions.isReady(this)
        val (title, colorRes) = when {
            lockOn && !ready -> "Blocking paused" to R.color.brand_danger
            blockingNow -> "Blocking now" to R.color.status_on
            lockOn -> "Scheduled" to R.color.status_scheduled
            !ready -> "Setup needed" to R.color.status_scheduled
            else -> "Lock is off" to R.color.status_off
        }
        val detail = when {
            lockOn && !ready -> "A permission was turned off. Tap to fix."
            !lockOn && !ready -> "${SetupPermissions.doneCount(this)} of 2 quick steps done · tap to finish"
            else -> FocusState.statusDetail(this)
        }
        findViewById<TextView>(R.id.tvStatusTitle)?.text = title
        findViewById<TextView>(R.id.tvStatusDetail)?.text = detail
        findViewById<View>(R.id.viewStatusDot)?.background?.mutate()?.setTint(getColor(colorRes))
        findViewById<View>(R.id.cardStatus)?.setOnClickListener {
            if (!SetupPermissions.isReady(this)) openSetup(thenStartLock = false)
        }

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

    /**
     * Optional, asked once: phone makers (Samsung especially) put apps to sleep after a few
     * days unused, which can delay locks and the morning reminder. Play policy prefers sending
     * users to the setting over requesting the exemption directly.
     */
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
                checkRepeat.isChecked = FocusState.prefs(this).getBoolean(FocusState.KEY_REPEAT_DAILY, false)
            } else {
                FocusState.prefs(this).edit().putBoolean(FocusState.KEY_REPEAT_DAILY, checkRepeat.isChecked).apply()
            }
        }

        btnStart.setOnClickListener {
            if (appItems.none { it.isSelected }) {
                // Ask for apps first, so nobody does setup only to hit "select an app".
                Toast.makeText(this, "Select at least one app to lock first.", Toast.LENGTH_SHORT).show()
            } else if (!SetupPermissions.isReady(this)) {
                openSetup(thenStartLock = true)
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
        checkRepeat.isChecked = prefs.getBoolean("REPEAT_DAILY", false)
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
        afterActivationFollowUp()
    }

    /**
     * Optional asks, spread out: at most ONE per lock activation, in this order —
     * notifications (one tap), Quick Settings tile (one tap), battery (only by the 3rd lock,
     * once the app has proven itself). Nothing is asked on first open any more.
     */
    private fun afterActivationFollowUp() {
        if (askNotificationsOnce()) return
        if (suggestQuickSettingsTileOnce()) return
        maybeSuggestUnrestrictedBattery()
    }

    /** Android 13+ one-tap system prompt, asked once, right after the first lock. */
    private fun askNotificationsOnce(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        val prefs = FocusState.prefs(this)
        if (prefs.getBoolean("REQUESTED_NOTIF_PERMISSION", false)) return false
        prefs.edit().putBoolean("REQUESTED_NOTIF_PERMISSION", true).apply()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        return true
    }

    /**
     * Tiles never appear in Quick Settings on their own — they sit in the Edit list. Offer it
     * once: a one-tap system prompt on Android 13+, a short how-to before. Returns true if shown.
     */
    private fun suggestQuickSettingsTileOnce(): Boolean {
        val prefs = FocusState.prefs(this)
        if (prefs.getBoolean("TILE_PROMPT_SHOWN", false)) return false
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
        return true
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

}