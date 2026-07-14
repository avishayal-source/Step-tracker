package com.steptracker.app

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

private const val BG_WRAPPED_TAG = "ywalk_bg_wrapped"

/**
 * Edge-to-edge support (required on Android 15 / API 35). Pads content for system
 * bars and adds the Y Walk background as a center-crop ImageView after layout
 * (not via theme windowBackground, which decoded the full bitmap at startup and
 * could OOM on low-memory devices).
 *
 * Keyboard (IME) insets are NOT applied here — scrollable forms (e.g. Botty) apply
 * IME padding on their own content so headers/tabs stay put and fields can scroll
 * above the keypad.
 */
fun AppCompatActivity.applyRootSystemBarInsets() {
    WindowCompat.setDecorFitsSystemWindows(window, false)

    val content = findViewById<ViewGroup>(android.R.id.content)
    val insetTarget = wrapRootWithBackground(content)

    ViewCompat.setOnApplyWindowInsetsListener(insetTarget) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        // System bars only — leave IME for the active ScrollView form.
        v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(insetTarget)
}

/** Wraps the activity content root in a FrameLayout with a full-bleed background. */
private fun wrapRootWithBackground(content: ViewGroup): View {
    val original = content.getChildAt(0) ?: return content
    if (original.tag == BG_WRAPPED_TAG) {
        return (original as? FrameLayout)?.getChildAt(1) ?: original
    }

    val frame = FrameLayout(content.context).apply {
        layoutParams = original.layoutParams
        tag = BG_WRAPPED_TAG
    }
    val bg = ImageView(content.context).apply {
        setImageResource(R.drawable.ywalk_background)
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    content.removeView(original)
    original.layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    frame.addView(bg)
    frame.addView(original)
    content.addView(frame, 0)
    return original
}
