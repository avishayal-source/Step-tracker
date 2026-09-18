package com.steptracker.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.steptracker.wear.sync.SessionSync
import com.steptracker.wear.sync.TodayCache
import com.steptracker.wear.sync.TodaySnapshot
import com.steptracker.wear.sync.WearSyncPaths
import com.steptracker.wear.workout.SessionStore
import com.steptracker.wear.workout.WorkoutActivity
import com.steptracker.wear.workout.WorkoutService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wear home (M2–M4): due workout, start, resume live session, retry phone sync.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 0)
        }
        setContent {
            MaterialTheme {
                WearHomeScreen()
            }
        }
    }
}

@Composable
private fun WearHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phoneLinked by remember { mutableStateOf(false) }
    var today by remember { mutableStateOf(TodayCache.load(context)) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var unsyncedCount by remember { mutableIntStateOf(SessionStore.unsynced(context).size) }
    val workout by WorkoutService.state.collectAsState()

    DisposableEffect(Unit) {
        val client = Wearable.getMessageClient(context)
        val capabilityClient = Wearable.getCapabilityClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            when (event.path) {
                WearSyncPaths.PATH_HELLO -> phoneLinked = true
                WearSyncPaths.PATH_TODAY -> {
                    phoneLinked = true
                    val json = event.data?.toString(Charsets.UTF_8) ?: return@OnMessageReceivedListener
                    TodayCache.save(context, json)
                    today = TodayCache.parse(json)
                    statusLine = "Synced"
                }
                WearSyncPaths.PATH_SESSION_ACK -> {
                    val id = event.data?.toString(Charsets.UTF_8) ?: return@OnMessageReceivedListener
                    scope.launch(Dispatchers.IO) {
                        SessionSync.onAck(context, id)
                        val n = SessionStore.unsynced(context).size
                        withContext(Dispatchers.Main) { unsyncedCount = n }
                    }
                }
            }
        }
        client.addListener(listener)
        scope.launch(Dispatchers.IO) {
            try {
                capabilityClient.addLocalCapability(WearSyncPaths.CAPABILITY_WATCH).await()
            } catch (_: Exception) { }
        }
        onDispose {
            client.removeListener(listener)
            scope.launch(Dispatchers.IO) {
                try {
                    capabilityClient.removeLocalCapability(WearSyncPaths.CAPABILITY_WATCH).await()
                } catch (_: Exception) { }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                unsyncedCount = SessionStore.unsynced(context).size
                scope.launch(Dispatchers.IO) {
                    SessionSync.pushUnsynced(context)
                    val n = SessionStore.unsynced(context).size
                    withContext(Dispatchers.Main) { unsyncedCount = n }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        SessionStore.finalizeOrphanIfNeeded(context)
        unsyncedCount = SessionStore.unsynced(context).size
        statusLine = requestToday(context)
        phoneLinked = true
        today = TodayCache.load(context)
        withContext(Dispatchers.IO) {
            SessionSync.pushUnsynced(context)
        }
        unsyncedCount = SessionStore.unsynced(context).size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
    ) {
        TimeText()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )
            Text(
                text = if (phoneLinked) stringResource(R.string.status_connected)
                else stringResource(R.string.status_waiting),
                style = MaterialTheme.typography.caption1,
                color = if (phoneLinked) MaterialTheme.colors.primary
                else MaterialTheme.colors.onBackground.copy(alpha = 0.75f)
            )

            val snap = today
            if (snap != null && (snap.periods.isNotEmpty() || snap.title.isNotBlank())) {
                TodayBlock(snap)
            } else {
                Text(
                    text = stringResource(R.string.today_none),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center
                )
            }

            statusLine?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center
                )
            }

            if (unsyncedCount > 0 && !workout.inProgress) {
                Text(
                    text = stringResource(R.string.unsynced_sessions, unsyncedCount),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        statusLine = requestToday(context)
                        today = TodayCache.load(context)
                        withContext(Dispatchers.IO) { SessionSync.pushUnsynced(context) }
                        unsyncedCount = SessionStore.unsynced(context).size
                        Toast.makeText(context, statusLine, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
            ) { Text(stringResource(R.string.btn_sync_today)) }

            if (workout.inProgress) {
                Button(
                    onClick = {
                        context.startActivity(Intent(context, WorkoutActivity::class.java))
                    },
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
                ) { Text(stringResource(R.string.btn_open_workout)) }
            } else {
                if (snap != null && snap.periods.isNotEmpty()) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(context, WorkoutActivity::class.java)
                                    .putExtra(WorkoutActivity.EXTRA_MODE, WorkoutActivity.MODE_SCHEDULED)
                            )
                        },
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_start_due)) }
                }

                Button(
                    onClick = {
                        context.startActivity(
                            Intent(context, WorkoutActivity::class.java)
                                .putExtra(WorkoutActivity.EXTRA_MODE, WorkoutActivity.MODE_FREE)
                        )
                    },
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_start_free)) }
            }
        }
    }
}

@Composable
private fun TodayBlock(snap: TodaySnapshot) {
    Text(
        text = snap.dueLabel(),
        modifier = Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.caption1,
        color = MaterialTheme.colors.primary,
        textAlign = TextAlign.Center
    )
    if (snap.title.isNotBlank()) {
        Text(
            text = snap.title,
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
    }
    Text(
        text = "${snap.totalMins} min total",
        style = MaterialTheme.typography.caption2,
        color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
    )
    snap.periods.forEach { p ->
        Text(
            text = "• ${p.label}",
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center
        )
    }
    val strideLine = if (snap.calibrated) {
        stringResource(R.string.strides_calibrated, snap.walkStrideM, snap.runStrideM)
    } else {
        stringResource(R.string.strides_default, snap.walkStrideM, snap.runStrideM)
    }
    Text(
        text = strideLine,
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.caption2,
        textAlign = TextAlign.Center
    )
    if (snap.syncedAtMs > 0L) {
        val whenStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snap.syncedAtMs))
        Text(
            text = stringResource(R.string.synced_at, whenStr),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f)
        )
    }
}

private suspend fun requestToday(context: android.content.Context): String = withContext(Dispatchers.IO) {
    try {
        val allNodes = Wearable.getNodeClient(context).connectedNodes.await()
        val capabilityInfo = Wearable.getCapabilityClient(context)
            .getCapability(
                WearSyncPaths.CAPABILITY_PHONE,
                com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE
            )
            .await()
        val targets = capabilityInfo.nodes.ifEmpty { allNodes.toSet() }
        if (targets.isEmpty()) return@withContext "No phone link"
        val messageClient = Wearable.getMessageClient(context)
        val ping = WearSyncPaths.MSG_PING_FROM_WATCH.toByteArray(Charsets.UTF_8)
        targets.forEach { node ->
            messageClient.sendMessage(node.id, WearSyncPaths.PATH_PING, ping).await()
            messageClient.sendMessage(node.id, WearSyncPaths.PATH_REQUEST_TODAY, ByteArray(0)).await()
        }
        "Sent sync request"
    } catch (e: Exception) {
        "Sync failed: ${e.message}"
    }
}
