package com.steptracker.wear.sync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives Data Layer messages from the phone while the watch app may be in background.
 * For M1, hello messages are also observed live in [com.steptracker.wear.MainActivity].
 */
class WearDataListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        // M1: no-op persistence; MainActivity MessageClient listener handles UI.
        // Kept so the watch declares MESSAGE_RECEIVED for the /ywalk path prefix.
        super.onMessageReceived(messageEvent)
    }
}
