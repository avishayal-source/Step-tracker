package com.steptracker.wear.sync

/**
 * Shared Wear Data Layer paths/capabilities for phone ↔ watch.
 * Keep in sync with [com.steptracker.app.wear.WearSyncPaths] on the phone module.
 */
object WearSyncPaths {
    const val CAPABILITY_PHONE = "ywalk_phone"
    const val CAPABILITY_WATCH = "ywalk_watch"

    const val PATH_HELLO = "/ywalk/hello"
    const val PATH_PING = "/ywalk/ping"
    const val PATH_TODAY = "/ywalk/today"
    const val PATH_REQUEST_TODAY = "/ywalk/request_today"
    /** Watch → phone: finished session JSON. */
    const val PATH_SESSION = "/ywalk/session"
    const val PATH_SESSION_PREFIX = "/ywalk/session"
    /** Phone → watch: session id that History imported. */
    const val PATH_SESSION_ACK = "/ywalk/session_ack"

    const val MSG_HELLO_FROM_PHONE = "hello_from_phone"
    const val MSG_PING_FROM_WATCH = "ping_from_watch"
}
