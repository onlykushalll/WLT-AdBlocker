package com.wlt.adblocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Configuration
import com.wlt.adblocker.data.BlocklistUpdateWorker
import com.wlt.adblocker.data.RuleStore

/**
 * Application class for WLT-Adblocker.
 *
 * Manual DI (no Hilt): we initialise the few process-wide singletons that need
 * eager startup here, in onCreate. Everything else is created lazily on first
 * access (see [RuleStore.get]).
 *
 * onCreate runs on the main thread, so we keep it light: notification channels
 * (fast, syscalls), WorkManager scheduling (queued, returns immediately), and
 * the RuleStore singleton init (which kicks off a background disk read).
 *
 * The VPN service is NOT started here. We respect the user's last-known state
 * by leaving that to [MainActivity] (which checks `auto_start_vpn` intent
 * extras from the quick-settings tile) and the system's [BootReceiver] (only
 * enabled if the user explicitly opts in to "start on boot" — privacy default
 * is off).
 */
class WltApplication : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "WltApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "WLT-Adblocker application starting")

        // Eagerly initialise the RuleStore singleton. This kicks off a
        // background load of the user's saved rules so the VPN service has
        // them ready by the time it starts.
        RuleStore.get(this)

        // Notification channels (Android O+). Cheap — kernel syscalls.
        createNotificationChannels()

        // WorkManager: we implement Configuration.Provider below, so the
        // platform's on-demand initializer (androidx.startup) will pick up
        // our configuration via reflection on first WorkManager.getInstance()
        // call — which happens inside BlocklistUpdateWorker.schedule().
        // We deliberately do NOT call WorkManager.initialize() manually
        // here, because doing so would race with the platform initializer
        // and could throw "WorkManager is already initialized".

        // Schedule the 24h blocklist update worker. Idempotent — KEEP policy
        // means re-calling doesn't reset the existing schedule's next fire.
        // This is also the first access to WorkManager.getInstance(), which
        // triggers lazy init via our Configuration.Provider.
        BlocklistUpdateWorker.schedule(this)
    }

    /**
     * Two channels:
     *  - VPN_CHANNEL_ID: low importance, ongoing. The VPN foreground
     *    notification lives here. Low importance so it doesn't beep on every
     *    stats update.
     *  - STATS_CHANNEL_ID: default importance. Reserved for user-facing
     *    block reports / pause expiry alerts.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val vpnChannel = NotificationChannel(
            NotificationHelper.VPN_CHANNEL_ID,
            getString(R.string.channel_vpn_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_vpn_desc)
            setShowBadge(false)
        }

        val statsChannel = NotificationChannel(
            NotificationHelper.STATS_CHANNEL_ID,
            getString(R.string.channel_stats_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_stats_desc)
            setShowBadge(true)
        }

        manager.createNotificationChannels(listOf(vpnChannel, statsChannel))
    }

    /**
     * WorkManager Configuration.Provider. We provide our own configuration
     * so we can initialise WorkManager manually in onCreate instead of using
     * the default androidx.startup initializer (which would race with our
     * onCreate for singleton init order).
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
