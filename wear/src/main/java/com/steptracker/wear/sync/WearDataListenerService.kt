package com.steptracker.wear.sync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Persists PATH_TODAY and session ACKs while the watch UI may be in the background.
 */
class WearDataListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearSyncPaths.PATH_TODAY -> {
                val json = messageEvent.data?.toString(Charsets.UTF_8) ?: return
                TodayCache.save(applicationContext, json)
            }
            WearSyncPaths.PATH_SESSION_ACK -> {
                val id = messageEvent.data?.toString(Charsets.UTF_8) ?: return
                scope.launch { SessionSync.onAck(applicationContext, id) }
            }
            else -> super.onMessageReceived(messageEvent)
        }
    }
}
