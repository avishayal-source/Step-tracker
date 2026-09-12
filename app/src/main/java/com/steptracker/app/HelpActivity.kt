package com.steptracker.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Short first-run product tour (after legal consent). Not part of the Play legal gate.
 */
class HelpActivity : AppCompatActivity() {

    private data class Page(val title: String, val body: String)

    private val pages = listOf(
        Page(
            "Track your walks & runs",
            "On Tracking, press Start to count steps and distance. Use Pause if you stop briefly, and Stop when the session is done.\n\nCalibrate once (⚙) outdoors so distances match your stride."
        ),
        Page(
            "Follow a schedule",
            "The Schedule tab builds walk/run intervals. Start runs a countdown, Pause freezes the clock, Stop ends the run but keeps your periods so you can restart.\n\nSave named workouts with Save / Load."
        ),
        Page(
            "Meet Botty",
            "Botty builds a training plan from your goal and fitness — on your phone, no cloud AI.\n\nBeginners aiming for 5 km get a ~9-week walk/run ladder; 10 km is about 16 weeks. Activate a plan and today's session appears in Schedule."
        ),
        Page(
            "You're set",
            "Backup and legal links live under the ⋮ menu. Sounds & voice cues are there too.\n\nYou can reopen this tour anytime from More → Quick help."
        )
    )

    private var index = 0
    private lateinit var tvTitle: TextView
    private lateinit var tvBody: TextView
    private lateinit var tvPage: TextView
    private lateinit var btnNext: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        applyRootSystemBarInsets()

        tvTitle = findViewById(R.id.tvHelpTitle)
        tvBody = findViewById(R.id.tvHelpBody)
        tvPage = findViewById(R.id.tvHelpPage)
        btnNext = findViewById(R.id.btnHelpNext)

        findViewById<MaterialButton>(R.id.btnHelpSkip).setOnClickListener { finishHelp() }
        btnNext.setOnClickListener {
            if (index >= pages.lastIndex) finishHelp()
            else {
                index++
                showPage()
            }
        }
        showPage()
    }

    private fun showPage() {
        val p = pages[index]
        tvTitle.text = p.title
        tvBody.text = p.body
        tvPage.text = "${index + 1} / ${pages.size}"
        btnNext.text = if (index >= pages.lastIndex) "Done" else "Next"
    }

    private fun finishHelp() {
        UserPrefs(this).hasSeenProductHelp = true
        finish()
    }

    companion object {
        fun intent(ctx: android.content.Context) =
            android.content.Intent(ctx, HelpActivity::class.java)
    }
}
