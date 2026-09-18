package com.steptracker.wear.workout

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/**
 * Live workout UI. Tracking lives in [WorkoutService] so ambient / back
 * cannot silently stop the session.
 */
class WorkoutActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = WorkoutService.EXTRA_MODE
        const val MODE_FREE = WorkoutService.MODE_FREE
        const val MODE_SCHEDULED = WorkoutService.MODE_SCHEDULED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!WorkoutService.isActive) {
            val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FREE
            WorkoutService.start(this, mode)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (WorkoutService.state.value.finished) {
                    finish()
                } else {
                    moveTaskToBack(true)
                }
            }
        })

        setContent {
            val snap by WorkoutService.state.collectAsState()
            MaterialTheme {
                WorkoutScreen(
                    title = snap.title.ifBlank { "Workout" },
                    periodLabel = snap.periodLabel.ifBlank { snap.title },
                    nextLabel = snap.nextLabel,
                    periodLeftMs = snap.periodLeftMs,
                    scheduled = snap.scheduled,
                    steps = snap.steps,
                    distM = snap.distM,
                    elapsedMs = snap.elapsedMs,
                    paused = snap.paused,
                    finished = snap.finished,
                    onTogglePause = {
                        WorkoutService.send(this, WorkoutService.ACTION_TOGGLE_PAUSE)
                    },
                    onEnd = { WorkoutService.send(this, WorkoutService.ACTION_END) },
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
private fun WorkoutScreen(
    title: String,
    periodLabel: String,
    nextLabel: String,
    periodLeftMs: Long,
    scheduled: Boolean,
    steps: Int,
    distM: Double,
    elapsedMs: Long,
    paused: Boolean,
    finished: Boolean,
    onTogglePause: () -> Unit,
    onEnd: () -> Unit,
    onClose: () -> Unit
) {
    val bg = Color(0xFF0B1220)
    val fg = Color(0xFFF2F5F8)
    val accent = Color(0xFF3DDC97)
    val warn = Color(0xFFFFB020)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        TimeText(modifier = Modifier.align(Alignment.TopCenter))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 22.dp, bottom = 6.dp, start = 8.dp, end = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        finished -> "Saved"
                        paused -> "Paused"
                        scheduled -> periodLabel
                        else -> title
                    },
                    color = if (paused && !finished) warn else if (scheduled) accent else fg,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (scheduled && !finished) {
                    Text(
                        text = formatMs(periodLeftMs) + " left",
                        color = fg.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.caption1
                    )
                    if (nextLabel.isNotBlank()) {
                        Text(
                            text = nextLabel,
                            color = fg.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    text = formatMs(elapsedMs),
                    color = fg,
                    style = MaterialTheme.typography.title1,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "$steps · ${"%.2f".format(distM / 1000.0)} km",
                    color = fg.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center
                )
            }

            if (!finished) {
                Button(
                    onClick = onTogglePause,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (paused) "Resume" else "Pause")
                }
                Button(
                    onClick = onEnd,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                ) {
                    Text("End")
                }
            } else {
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}
