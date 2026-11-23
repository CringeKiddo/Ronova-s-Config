package com.ronova.config.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val notifications: Boolean,
    val location: Boolean,
    val usageStats: Boolean,
    val overlay: Boolean
) {
    val allGranted: Boolean get() = notifications && location && usageStats && overlay
}

object PermissionHelper {

    fun status(context: Context): PermissionStatus = PermissionStatus(
        notifications = hasNotifications(context),
        location = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED,
        usageStats = hasUsageStats(context),
        overlay = Settings.canDrawOverlays(context)
    )

    private fun hasNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    private fun hasUsageStats(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Runtime permissions that can still be requested with the system dialog. */
    fun missingRuntimePermissions(context: Context): Array<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) list += Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) list += Manifest.permission.ACCESS_FINE_LOCATION
        return list.toTypedArray()
    }

    fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    fun deviceInfoIntent(): Intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** Starts an activity and returns false instead of crashing when the settings page is missing. */
    fun safeStart(context: Context, intent: Intent): Boolean = try {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
