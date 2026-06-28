package com.steptracker.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton

/**
 * First-run consent gate. The user must read the Health & AI disclaimers,
 * confirm the app is non-medical, and accept the Terms of Service and
 * Privacy Policy before reaching the app. After accepting we request the
 * activity-recognition and notification permissions.
 */
class OnboardingActivity : AppCompatActivity() {

    private val PERM_RC = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        val cb = findViewById<CheckBox>(R.id.cbConsent)
        val btnAgree = findViewById<MaterialButton>(R.id.btnAgree)
        val btnDecline = findViewById<MaterialButton>(R.id.btnDecline)

        cb.setOnCheckedChangeListener { _, checked -> btnAgree.isEnabled = checked }

        findViewById<MaterialButton>(R.id.btnReadTerms).setOnClickListener {
            startActivity(LegalDocActivity.terms(this))
        }
        findViewById<MaterialButton>(R.id.btnReadPrivacy).setOnClickListener {
            startActivity(LegalDocActivity.privacy(this))
        }

        btnAgree.setOnClickListener {
            if (!cb.isChecked) return@setOnClickListener
            LegalConsent.accept(this)
            requestOnboardingPermissions()
        }

        btnDecline.setOnClickListener { finishAndRemoveTask() }

        // Declining via system back also exits — the app can't be used without consent.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishAndRemoveTask() }
        })
    }

    private fun onboardingPerms(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun requestOnboardingPermissions() {
        val perms = onboardingPerms()
        if (perms.isEmpty()) { goToMain(); return }
        ActivityCompat.requestPermissions(this, perms, PERM_RC)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        // Proceed regardless of grant outcome; the app degrades gracefully and
        // re-prompts for activity permission when the user starts tracking.
        if (rc == PERM_RC) goToMain()
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
