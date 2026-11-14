package com.focuslockapp.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * One checklist screen for the two permissions blocking needs, shown only when the user first
 * tries to lock something (not on app open). Each step ticks itself when the user comes back
 * from Settings. Returns RESULT_OK when both are granted and the user taps the main button.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var btnUsage: MaterialButton
    private lateinit var btnOverlay: MaterialButton
    private lateinit var btnContinue: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        btnUsage = findViewById(R.id.btnStepUsage)
        btnOverlay = findViewById(R.id.btnStepOverlay)
        btnContinue = findViewById(R.id.btnSetupContinue)
        intent.getStringExtra(EXTRA_BUTTON_TEXT)?.let { btnContinue.text = it }

        findViewById<View>(R.id.btnSetupBack).setOnClickListener { finish() }
        btnUsage.setOnClickListener { openUsageAccess() }
        btnOverlay.setOnClickListener { openOverlay() }
        btnContinue.setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val usage = SetupPermissions.hasUsageAccess(this)
        val overlay = SetupPermissions.hasOverlay(this)

        markStep(btnUsage, findViewById(R.id.tvStepUsageIcon), usage, "1️⃣")
        markStep(btnOverlay, findViewById(R.id.tvStepOverlayIcon), overlay, "2️⃣")

        val done = listOf(usage, overlay).count { it }
        findViewById<TextView>(R.id.tvSetupProgress).text =
            if (done == 2) "All set! ✅" else "$done of 2 done"
        btnContinue.isEnabled = done == 2
        btnContinue.alpha = if (done == 2) 1f else 0.5f
    }

    private fun markStep(button: MaterialButton, icon: TextView, granted: Boolean, number: String) {
        icon.text = if (granted) "✅" else number
        button.text = if (granted) "Done" else "Turn on"
        button.isEnabled = !granted
    }

    private fun openUsageAccess() {
        hint()
        // Many phones jump straight to our entry with the package URI; fall back to the list.
        val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).setData(Uri.parse("package:$packageName"))
        if (!tryStart(direct)) tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun openOverlay() {
        hint()
        val direct = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).setData(Uri.parse("package:$packageName"))
        if (!tryStart(direct)) tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }

    private fun hint() {
        Toast.makeText(this, "Find \"Focus Lock App\", switch it on, then come back", Toast.LENGTH_LONG).show()
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent); true
    } catch (e: Exception) {
        false
    }

    companion object {
        const val EXTRA_BUTTON_TEXT = "button_text"
    }
}
