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
    /** Phone → watch: JSON snapshot of today's due workout + strides. */
    const val PATH_TODAY = "/ywalk/today"
    /** Watch → phone: ask for a fresh PATH_TODAY push. */
    const val PATH_REQUEST_TODAY = "/ywalk/request_today"
    /** Watch → phone: finished session JSON. */
    const val PATH_SESSION = "/ywalk/session"
    const val PATH_SESSION_PREFIX = "/ywalk/session"
    /** Phone → watch: session id that History imported. */
    const val PATH_SESSION_ACK = "/ywalk/session_ack"

    const val MSG_HELLO_FROM_PHONE = "hello_from_phone"
    const val MSG_PING_FROM_WATCH = "ping_from_watch"
}
