package com.steptracker.app.wear

import android.content.Context
import android.util.Log
import android.widget.Toast
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
 * Phone-side Wear glue (M1–M2): hello/ping + push today's schedule & strides.
 */
class WearSyncManager(
    private val context: Context,
    private val onWatchPing: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val messageClient: MessageClient = Wearable.getMessageClient(context)
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context)

    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        when (event.path) {
            WearSyncPaths.PATH_PING -> {
                val text = event.data?.toString(Charsets.UTF_8) ?: WearSyncPaths.MSG_PING_FROM_WATCH
                onWatchPing(text)
                scope.launch(Dispatchers.IO) {
                    replyHello(event.sourceNodeId)
                    pushTodayToNode(event.sourceNodeId)
                }
            }
            WearSyncPaths.PATH_REQUEST_TODAY -> {
                scope.launch(Dispatchers.IO) {
                    replyHello(event.sourceNodeId)
                    pushTodayToNode(event.sourceNodeId)
                }
            }
        }
    }

    fun start() {
        messageClient.addListener(messageListener)
        scope.launch(Dispatchers.IO) {
            try {
                capabilityClient.addLocalCapability(WearSyncPaths.CAPABILITY_PHONE).await()
            } catch (e: Exception) {
                Log.w(TAG, "addLocalCapability failed", e)
            }
            sendHelloToWatches(showToast = false)
            syncTodayToWatches(showToast = false)
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

    fun sendHelloToWatches(showToast: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.d(TAG, "No Wear nodes connected")
                    if (showToast) {
                        toast("⌚ No Wear node linked")
                    }
                    return@launch
                }
                val payload = WearSyncPaths.MSG_HELLO_FROM_PHONE.toByteArray(Charsets.UTF_8)
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, WearSyncPaths.PATH_HELLO, payload).await()
                    Log.d(TAG, "Hello sent to ${node.displayName}")
                }
                if (showToast) {
                    toast("⌚ Hello → ${nodes.size}: ${nodes.joinToString { it.displayName }}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "sendHello failed", e)
                if (showToast) toast("⌚ Hello failed: ${e.message}")
            }
        }
    }

    fun syncTodayToWatches(showToast: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.d(TAG, "syncToday: no nodes")
                    if (showToast) toast("⌚ No watch to sync")
                    return@launch
                }
                val json = WearTodayPayload.buildJson(context)
                val bytes = json.toByteArray(Charsets.UTF_8)
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, WearSyncPaths.PATH_TODAY, bytes).await()
                    Log.d(TAG, "Today synced to ${node.displayName} (${bytes.size}b)")
                }
                if (showToast) toast("⌚ Today synced → ${nodes.size} watch(es)")
            } catch (e: Exception) {
                Log.w(TAG, "syncToday failed", e)
                if (showToast) toast("⌚ Sync failed: ${e.message}")
            }
        }
    }

    private suspend fun replyHello(nodeId: String) {
        try {
            val payload = WearSyncPaths.MSG_HELLO_FROM_PHONE.toByteArray(Charsets.UTF_8)
            messageClient.sendMessage(nodeId, WearSyncPaths.PATH_HELLO, payload).await()
        } catch (e: Exception) {
            Log.w(TAG, "hello ack failed", e)
        }
    }

    private suspend fun pushTodayToNode(nodeId: String) {
        try {
            val bytes = WearTodayPayload.buildJson(context).toByteArray(Charsets.UTF_8)
            messageClient.sendMessage(nodeId, WearSyncPaths.PATH_TODAY, bytes).await()
            Log.d(TAG, "Today pushed to $nodeId")
        } catch (e: Exception) {
            Log.w(TAG, "pushToday failed", e)
        }
    }

    private fun toast(msg: String) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "WearSync"
    }
}
