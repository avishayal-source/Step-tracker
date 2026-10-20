package com.steptracker.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions step tracking needs, shared by the Tracking and Schedule tabs and the service. */
object TrackingPermissions {

    const val REQUEST_TRACKING = 100
    const val REQUEST_SCHEDULE = 101

    fun all(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }.toTypedArray()

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Step counting needs activity recognition from Android 10. */
    fun canTrack(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)

    /**
     * Foreground-service types allowed right now. From Android 14 the service may only
     * start in the foreground with a type whose permission is granted; 0 there means it
     * must not start at all.
     */
    fun foregroundTypes(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var type = 0
        if (hasLocation(ctx)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)
        ) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        return type
    }

    fun canStartForeground(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || foregroundTypes(ctx) != 0
}
