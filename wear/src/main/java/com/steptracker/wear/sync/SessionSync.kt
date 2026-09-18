package com.steptracker.wear.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.steptracker.wear.workout.SessionStore
import com.steptracker.wear.workout.WatchSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Pushes finished watch sessions to the phone (DataItem + message).
 * DataItems retry while the phone is away; ACK marks them synced.
 */
object SessionSync {
    private const val TAG = "WearSync"

    suspend fun push(context: Context, session: WatchSession) {
        val json = SessionStore.toJson(session).toString()
        val bytes = json.toByteArray(Charsets.UTF_8)
        try {
            val req = PutDataMapRequest.create("${WearSyncPaths.PATH_SESSION_PREFIX}/${session.id}")
            req.dataMap.putString("json", json)
            req.dataMap.putLong("ts", System.currentTimeMillis())
            req.setUrgent()
            Wearable.getDataClient(context).putDataItem(req.asPutDataRequest()).await()
        } catch (e: Exception) {
            Log.w(TAG, "session DataItem failed", e)
        }
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val client = Wearable.getMessageClient(context)
            nodes.forEach { node ->
                client.sendMessage(node.id, WearSyncPaths.PATH_SESSION, bytes).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "session message failed", e)
        }
    }

    suspend fun pushUnsynced(context: Context) {
        SessionStore.unsynced(context).forEach { push(context, it) }
    }

    fun pushAsync(context: Context, session: WatchSession) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            push(app, session)
        }
    }

    suspend fun onAck(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        SessionStore.markSynced(context, sessionId)
        try {
            val nodeId = Wearable.getNodeClient(context).localNode.await().id
            val uri = Uri.parse("wear://$nodeId${WearSyncPaths.PATH_SESSION_PREFIX}/$sessionId")
            Wearable.getDataClient(context).deleteDataItems(uri).await()
        } catch (e: Exception) {
            Log.w(TAG, "delete session DataItem failed", e)
        }
    }
}
