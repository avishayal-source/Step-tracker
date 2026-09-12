package com.steptracker.app.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * M1 phone-side Wear glue: announce hello to nearby watches and listen for pings.
 */
class WearSyncManager(
    private val context: Context,
    private val onWatchPing: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        if (event.path == WearSyncPaths.PATH_PING) {
            val text = event.data?.toString(Charsets.UTF_8) ?: WearSyncPaths.MSG_PING_FROM_WATCH
            onWatchPing(text)
        }
    }

    fun start() {
        messageClient.addListener(messageListener)
        scope.launch(Dispatchers.IO) {
            try {
                // Ensure this phone advertises the phone capability for discovery.
                capabilityClient.addLocalCapability(WearSyncPaths.CAPABILITY_PHONE).await()
            } catch (e: Exception) {
                Log.w(TAG, "addLocalCapability failed", e)
            }
            sendHelloToWatches()
        }
    }

    fun stop() {
        messageClient.removeListener(messageListener)
        scope.launch(Dispatchers.IO) {
            try {
                capabilityClient.removeLocalCapability(WearSyncPaths.CAPABILITY_PHONE).await()
            } catch (_: Exception) { }
        }
    }

    fun sendHelloToWatches() {
        scope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.d(TAG, "No Wear nodes connected")
                    return@launch
                }
                val payload = WearSyncPaths.MSG_HELLO_FROM_PHONE.toByteArray(Charsets.UTF_8)
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, WearSyncPaths.PATH_HELLO, payload).await()
                    Log.d(TAG, "Hello sent to ${node.displayName}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendHello failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "WearSync"
    }
}
