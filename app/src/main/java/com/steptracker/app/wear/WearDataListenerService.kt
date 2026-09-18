package com.steptracker.app.wear

import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Receives watch pings, today-sync requests, and finished workout sessions.
 */
class WearDataListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearSyncPaths.PATH_PING -> {
                val text = messageEvent.data?.toString(Charsets.UTF_8)
                    ?: WearSyncPaths.MSG_PING_FROM_WATCH
                Log.d(TAG, "Watch ping: $text from ${messageEvent.sourceNodeId}")
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "⌚ Watch ping received", Toast.LENGTH_SHORT).show()
                }
                scope.launch { ackAndSync(messageEvent.sourceNodeId) }
            }
            WearSyncPaths.PATH_REQUEST_TODAY -> {
                Log.d(TAG, "Watch requested today from ${messageEvent.sourceNodeId}")
                scope.launch { ackAndSync(messageEvent.sourceNodeId) }
            }
            WearSyncPaths.PATH_SESSION -> {
                val json = messageEvent.data?.toString(Charsets.UTF_8) ?: return
                scope.launch { importSession(json, messageEvent.sourceNodeId) }
            }
            else -> super.onMessageReceived(messageEvent)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = event.dataItem.uri.path ?: return@forEach
            if (!path.startsWith(WearSyncPaths.PATH_SESSION_PREFIX)) return@forEach
            val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("json")
                ?: return@forEach
            val nodeId = event.dataItem.uri.host.orEmpty()
            scope.launch { importSession(json, nodeId) }
        }
    }

    private suspend fun importSession(json: String, nodeId: String) {
        val result = WearSessionImporter.importJson(applicationContext, json) ?: return
        WearSessionImporter.ack(applicationContext, nodeId, result.id)
        if (!result.newlyImported) return
        try {
            val bytes = WearTodayPayload.buildJson(applicationContext).toByteArray(Charsets.UTF_8)
            Wearable.getMessageClient(this)
                .sendMessage(nodeId, WearSyncPaths.PATH_TODAY, bytes)
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "today push after import failed", e)
        }
        scope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, "⌚ Watch workout saved", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun ackAndSync(nodeId: String) {
        val client = Wearable.getMessageClient(this)
        try {
            client.sendMessage(
                nodeId,
                WearSyncPaths.PATH_HELLO,
                WearSyncPaths.MSG_HELLO_FROM_PHONE.toByteArray(Charsets.UTF_8)
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "hello reply failed", e)
        }
        try {
            val bytes = WearTodayPayload.buildJson(applicationContext).toByteArray(Charsets.UTF_8)
            client.sendMessage(nodeId, WearSyncPaths.PATH_TODAY, bytes).await()
            Log.d(TAG, "Today pushed (${bytes.size}b)")
        } catch (e: Exception) {
            Log.w(TAG, "today push failed", e)
        }
    }

    companion object {
        private const val TAG = "WearSync"
    }
}
