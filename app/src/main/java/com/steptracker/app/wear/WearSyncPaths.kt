package com.steptracker.app.wear

/**
 * Shared Wear Data Layer paths/capabilities for phone ↔ watch.
 * Keep in sync with the Wear module's WearSyncPaths.
 */
object WearSyncPaths {
    const val CAPABILITY_PHONE = "ywalk_phone"
    const val CAPABILITY_WATCH = "ywalk_watch"

    const val PATH_HELLO = "/ywalk/hello"
    const val PATH_PING = "/ywalk/ping"

    const val MSG_HELLO_FROM_PHONE = "hello_from_phone"
    const val MSG_PING_FROM_WATCH = "ping_from_watch"
}
