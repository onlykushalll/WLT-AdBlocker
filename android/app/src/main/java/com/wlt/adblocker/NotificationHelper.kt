package com.wlt.adblocker

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Builds the foreground-service notifications used by the VPN service and
 * the (optional) pause-protection alert.
 *
 * Two channel IDs, both created in [WltApplication.onCreate]:
 *  - [VPN_CHANNEL_ID] — low importance, ongoing. The VPN foreground
 *    notification lives here. Low importance = no sound, no heads-up,
 *    but still required by the platform for foreground services.
 *  - [STATS_CHANNEL_ID] — default importance. Reserved for the
 *    pause-expiry reminder and any user-facing block-count milestone
 *    notifications we add later.
 *
 * Why a separate helper class and not just inline calls in WltVpnService:
 * the service already has its own notification builder (for the live
 * "X queries blocked" counter that updates every minute); this class
 * is for one-shot notifications fired from outside the service (e.g.
 * a pause-expiry reminder scheduled via AlarmManager).
 */
object NotificationHelper {

    const val VPN_CHANNEL_ID = "wlt_vpn"
    const val STATS_CHANNEL_ID = "wlt_stats"

    /** Notification ID for the VPN foreground service. */
    const val VPN_NOTIFICATION_ID = 1001

    /** Notification ID for the pause-expiry reminder (distinct from VPN so
     *  cancelling one doesn't cancel the other). */
    const val PAUSE_NOTIFICATION_ID = 1002

    /**
     * Builds a basic notification on the VPN channel. The [pendingIntent]
     * is fired when the user taps the notification body (typically opens
     * MainActivity).
     */
    fun createNotification(
        context: Context,
        title: String,
        text: String,
        pendingIntent: PendingIntent?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, VPN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (pendingIntent != null) builder.setContentIntent(pendingIntent)
        return builder.build()
    }

    /**
     * Builds a pause-expiry reminder notification on the stats channel.
     * Not ongoing — the user can dismiss it.
     */
    fun createPauseNotification(
        context: Context,
        minutesRemaining: Int,
    ): Notification {
        val intent = Intent().apply {
            setClassName(context.packageName, "com.wlt.adblocker.MainActivity")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            pendingIntentFlagsImmutable(),
        )
        return NotificationCompat.Builder(context, STATS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_pause_title))
            .setContentText(
                context.getString(R.string.notif_pause_text, minutesRemaining),
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
    }

    /** Returns the PendingIntent flags with FLAG_IMMUTABLE added on API 23+.
     *  Required since API 31 enforces immutability. */
    private fun pendingIntentFlagsImmutable(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }
}
