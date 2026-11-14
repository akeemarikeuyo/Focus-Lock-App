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
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
    private lateinit var checkRepeat: CheckBox
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

        fun rescheduleAlarms(context: Context) {
            setPreciseAlarm(context, 9, 0, 100)
        }

        private fun setPreciseAlarm(context: Context, hour: Int, minute: Int, requestCode: Int) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ReminderReceiver::class.java)

            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                timeInMillis = System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }

            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }

            val alarmInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, pendingIntent)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setAlarmClock(alarmInfo, pendingIntent)
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
                    }
                } else {
                    alarmManager.setAlarmClock(alarmInfo, pendingIntent)
                }
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
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
            val prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
            val distractionsBlocked = prefs.getInt("BLOCK_COUNT", 0)
            val playStoreLink = "https://play.google.com/store/apps/details?id=$packageName"
            val shareMessage = "I just blocked $distractionsBlocked distractions using Focus Lock App🔒. Can you beat my focus score?\n\n$playStoreLink"

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
            rescheduleAlarms(this)
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

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
        val count = prefs.getInt("BLOCK_COUNT", 0)
        if (::tvAttemptsCount.isInitialized) {
            tvAttemptsCount.text = "$count"
        }
        checkAndPromptPermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        adWaitRunnable?.let { adWaitHandler.removeCallbacks(it) }
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

        // 4. Exact Alarms (Android 12+): without this, the daily reminder and auto-stop
        // alarms silently fall back to inexact scheduling, which Doze/App Standby defers
        // or drops for apps that haven't been opened in a while — the reminder then stops
        // firing reliably. setAlarmClock() (used once this is granted) is exempt from that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                showInstructionalDialog(
                    "Step 4: Keep Reminders On Time",
                    "Focus Lock App needs 'Alarms & reminders' access so your daily check-in and " +
                        "auto-unlock keep firing on time, even after the app sits unused for a while.",
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:$packageName"
                )
                return
            }
        }
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
            val isSelecting = (it as Button).text.toString() == "Select Socials"
            for (item in appItems) {
                if (item.isSocial && !item.packageName.contains("whatsapp")) {
                    item.isSelected = isSelecting
                }
            }
            appListAdapter.notifyDataSetChanged()
            it.text = if (isSelecting) "Unselect Socials" else "Select Socials"
        }

        findViewById<EditText>(R.id.etAppSearch)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterAppList(s?.toString().orEmpty())
            }
        })

        findViewById<FloatingActionButton>(R.id.fabChat)?.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        loadSavedSettings()
        btnStartTime.setOnClickListener { showTimePicker(true) }
        btnEndTime.setOnClickListener { showTimePicker(false) }

        checkRepeat.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE).edit().putBoolean("REPEAT_DAILY", isChecked).apply()
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
        val prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("BLOCK_COUNT", 0).apply()
        tvAttemptsCount.text = "0"

        val selectedApps = appItems.filter { it.isSelected }.map { it.packageName }.toSet()
        val activationDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        prefs.edit()
            .putStringSet("saved_apps", selectedApps)
            .putString("MANUAL_BLOCK_ACTIVATION_DATE", activationDate)
            .apply()

        val intent = Intent(this, BlockerService::class.java).apply {
            putStringArrayListExtra("BLOCKED_APPS", ArrayList(selectedApps))
            putExtra("START_HOUR", startHour)
            putExtra("START_MIN", startMin)
            putExtra("END_HOUR", endHour)
            putExtra("END_MIN", endMin)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)

        Toast.makeText(this, "Focus Mode Active! 🔒", Toast.LENGTH_SHORT).show()

        // --- Schedule Auto-Stop ---
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val stopIntent = Intent(this, AutoStopReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 201, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endHour)
            set(Calendar.MINUTE, endMin)
            set(Calendar.SECOND, 0)
        }
        if (stopCalendar.timeInMillis <= System.currentTimeMillis()) stopCalendar.add(Calendar.DAY_OF_YEAR, 1)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, stopCalendar.timeInMillis, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, stopCalendar.timeInMillis, pendingIntent)
            }
        } catch (e: SecurityException) { e.printStackTrace() }
    }

    private fun stopBlockingService() {
        stopService(Intent(this, BlockerService::class.java))
        getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE).edit().putBoolean("IS_LOCKED", false).apply()
        Toast.makeText(this, "Focus Mode Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun loadInstalledApps() {
        val savedApps = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE).getStringSet("saved_apps", emptySet()) ?: emptySet()

        appListAdapter = AppListAdapter()
        appList.adapter = appListAdapter
        appList.setOnItemClickListener { _, _, position, _ ->
            displayedItems.getOrNull(position)?.let { it.isSelected = !it.isSelected }
            appListAdapter.notifyDataSetChanged()
        }

        lifecycleScope.launch {
            val loadedItems = withContext(Dispatchers.IO) {
                val pm = packageManager
                val apps = pm.getInstalledPackages(PackageManager.GET_META_DATA)
                val items = ArrayList<AppItem>()
                for (app in apps) {
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                        val pkg = app.packageName
                        if (pkg == "com.android.settings" || pkg == "com.android.phone" || pkg.contains("launcher")) continue
                        val name = app.applicationInfo?.loadLabel(pm)?.toString() ?: pkg
                        val icon = app.applicationInfo?.loadIcon(pm) ?: pm.defaultActivityIcon
                        val isSocial = socialPackages.any { pkg.contains(it) }
                        items.add(AppItem(name, pkg, savedApps.contains(pkg), isSocial, icon))
                    }
                }
                items.sortWith(compareByDescending<AppItem> { it.isSelected }.thenByDescending { it.isSocial }.thenBy { it.name })
                items
            }
            appItems.clear()
            appItems.addAll(loadedItems)
            filterAppList(findViewById<EditText>(R.id.etAppSearch)?.text?.toString().orEmpty())
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

            ivIcon.setImageDrawable(item.icon)
            tvName.text = item.name
            tvName.setTextColor(if (item.isSocial) Color.parseColor("#448AFF") else Color.BLACK)
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