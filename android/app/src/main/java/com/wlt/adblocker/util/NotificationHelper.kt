package com.wlt.adblocker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationHelper {
    const val CHANNEL_VPN = "wlt_vpn"
    const val CHANNEL_STATS = "wlt_stats"
    const val NOTIF_VPN_ID = 1

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val vpn = NotificationChannel(
            CHANNEL_VPN,
            "VPN Protection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when WLT ad blocking is active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(vpn)

        val stats = NotificationChannel(
            CHANNEL_STATS,
            "Block Stats",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Periodic blocked-ad count updates"
        }
        nm.createNotificationChannel(stats)
    }
}
