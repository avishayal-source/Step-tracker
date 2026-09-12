package com.steptracker.wear

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.steptracker.wear.sync.WearSyncPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Wear OS home (M1): shows Y Walk, listens for a phone hello, and can ping the phone.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WearHomeScreen()
            }
        }
    }
}

@Composable
private fun WearHomeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var phoneLinked by remember { mutableStateOf(false) }
    var lastHello by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val client = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == WearSyncPaths.PATH_HELLO) {
                phoneLinked = true
                lastHello = event.data?.toString(Charsets.UTF_8)
            }
        }
        client.addListener(listener)
        onDispose { client.removeListener(listener) }
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
                .padding(horizontal = 12.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f)
            )
            Text(
                text = if (phoneLinked) {
                    stringResource(R.string.status_connected)
                } else {
                    stringResource(R.string.status_waiting)
                },
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                color = if (phoneLinked) MaterialTheme.colors.primary
                else MaterialTheme.colors.onBackground.copy(alpha = 0.75f)
            )
            lastHello?.let { hello ->
                Text(
                    text = hello,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        val ok = pingPhone(context)
                        Toast.makeText(
                            context,
                            if (ok) R.string.ping_sent else R.string.ping_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
            ) {
                Text(text = stringResource(R.string.btn_ping_phone))
            }
        }
    }
}

private suspend fun pingPhone(context: android.content.Context): Boolean = withContext(Dispatchers.IO) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) return@withContext false
        val messageClient = Wearable.getMessageClient(context)
        val payload = WearSyncPaths.MSG_PING_FROM_WATCH.toByteArray(Charsets.UTF_8)
        nodes.forEach { node ->
            messageClient.sendMessage(node.id, WearSyncPaths.PATH_PING, payload).await()
        }
        true
    } catch (_: Exception) {
        false
    }
}
