package com.steptracker.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** In-app privacy policy (Phase 1 commercial readiness). Text lives in assets. */
class PrivacyPolicyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_policy)
        findViewById<View>(R.id.btnPrivacyBack).setOnClickListener { finish() }

        val body = assets.open("privacy_policy.txt").bufferedReader().use { it.readText() }
        findViewById<TextView>(R.id.tvPrivacyBody).text = body
    }
}
