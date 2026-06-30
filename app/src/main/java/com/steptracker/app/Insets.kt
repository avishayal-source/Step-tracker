package com.steptracker.app

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Edge-to-edge support (required on Android 15 / API 35, which is enforced by
 * Play). API 35 draws content under the status and navigation bars. We pad the
 * activity's root view by the system-bar + display-cutout insets so content is
 * never hidden, while the window background (the Y Walk artwork) still draws
 * full-bleed behind the bars.
 */
fun AppCompatActivity.applyRootSystemBarInsets() {
    val content = findViewById<ViewGroup>(android.R.id.content)
    val root = content.getChildAt(0) ?: content
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
