package com.steptracker.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Generic legal document viewer. Renders a plain-text file from /assets.
 * Used for both the Privacy Policy and the Terms of Service.
 */
class LegalDocActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ASSET = "extra_asset"

        fun privacy(ctx: Context) = Intent(ctx, LegalDocActivity::class.java).apply {
            putExtra(EXTRA_TITLE, ctx.getString(R.string.privacy_policy_title))
            putExtra(EXTRA_ASSET, "privacy_policy.txt")
        }

        fun terms(ctx: Context) = Intent(ctx, LegalDocActivity::class.java).apply {
            putExtra(EXTRA_TITLE, ctx.getString(R.string.terms_title))
            putExtra(EXTRA_ASSET, "terms_of_service.txt")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_policy)
        applyRootSystemBarInsets()
        findViewById<View>(R.id.btnPrivacyBack).setOnClickListener { finish() }

        val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.privacy_policy_title)
        val asset = intent.getStringExtra(EXTRA_ASSET) ?: "privacy_policy.txt"

        findViewById<TextView>(R.id.tvLegalTitle).text = title
        val body = assets.open(asset).bufferedReader().use { it.readText() }
        findViewById<TextView>(R.id.tvPrivacyBody).text = body
    }
}
