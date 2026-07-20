package com.wlt.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What this service DOES: manages the Go HTTPS proxy's lifecycle (start
 * once the CA is confirmed installed, stop, report stats), and gates that
 * lifecycle on the CA actually being trusted -- it will refuse to start
 * proxying and show a clear warning instead if [CaCertHelper] reports the
 * CA isn't installed, rather than silently doing nothing or (worse)
 * running a proxy nothing can actually establish a TLS session through.
 *
 * What this service explicitly does NOT do: get traffic to the proxy in
 * the first place. The Go proxy (internal/httpsproxy/proxy.go) listens on
 * a local TCP port and can terminate/relay a connection handed to it, but
 * something still has to capture outbound TCP:443 connections from
 * WltVpnService's tun interface and bridge them to
 * 127.0.0.1:<proxy port> -- that's a real TCP handshake + relay
 * implementation, comparable in scope to what this service does, not a
 * small addition to it. That piece belongs in WltVpnService /
 * ConnectionFilter, and until it exists, this service can be fully
 * running with a fully valid CA and still filter zero bytes of real
 * traffic. Don't let "the service starts cleanly" read as "HTTPS
 * filtering is live" -- confirm the relay separately.
 *
 * Foreground service type: using `specialUse` here too, same as
 * WltVpnService, but flagging real uncertainty this time rather than
 * confidence -- the "VPN apps" carve-out I verified earlier this session
 * is specific to actual VpnService implementations, and this is a plain
 * Service running a local proxy alongside one, which is a different case
 * I have not separately verified against current Android foreground
 * service policy. Check this specifically before shipping; it's the kind
 * of thing that passes review and then crashes on a specific OEM build.
 */
class HttpsProxyService : Service() {

    companion object {
        private const val TAG = "HttpsProxyService"
        const val ACTION_START = "com.wlt.adblocker.action.HTTPS_PROXY_START"
        const val ACTION_STOP = "com.wlt.adblocker.action.HTTPS_PROXY_STOP"
        private const val CHANNEL_ID = "wlt_https_proxy_status"
        private const val NOTIFICATION_ID = 1002
    }

    /** Isolates the still-unverified Go binding surface for the proxy
     * itself, same reasoning as GoSecurityBridge. Replace with the real
     * gomobile-generated calls once mobile.go actually exposes proxy
     * control (it may not yet -- Engine didn't wrap httpsproxy.Proxy as
     * of what I read earlier this session). */
    interface GoHttpsProxyBridge {
        /** Starts the local proxy, returns the port it's listening on. */
        fun start(): Int
        fun stop()
        fun isRunning(): Boolean
        fun getStats(): ProxyStatsData
    }

    data class ProxyStatsData(
        val connections: Long,
        val requestsInspected: Long,
        val responsesFiltered: Long,
        val scriptletsInjected: Long,
        val m3uPruned: Long,
        val bytesRelayed: Long,
    )

    private class UnverifiedGoHttpsProxyBridge : GoHttpsProxyBridge {
        override fun start(): Int {
            throw NotImplementedError(
                "mobile.go does not appear to expose HTTPS proxy control yet " +
                    "(as of what I read earlier this session) -- add e.g. " +
                    "StartHttpsProxy()/StopHttpsProxy()/HttpsProxyStatsJSON() to the " +
                    "Engine or a new binding type, matching whatever internal/httpsproxy " +
                    "actually exposes, then wire this class to the real calls."
            )
        }
        override fun stop() { /* no-op until real binding exists */ }
        override fun isRunning(): Boolean = false
        override fun getStats(): ProxyStatsData = ProxyStatsData(0, 0, 0, 0, 0, 0)
    }

    private var bridge: GoHttpsProxyBridge = UnverifiedGoHttpsProxyBridge()
    private lateinit var caCertHelper: CaCertHelper
    private var statsJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        caCertHelper = CaCertHelper(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startProxy()
        }
        return START_NOT_STICKY // deliberately not START_STICKY: restarting this
                                 // automatically after a kill without re-checking CA
                                 // trust state first would be the wrong default
    }

    private fun startProxy() {
        if (!caCertHelper.isCaTrustedBySystem()) {
            Log.w(TAG, "CA is not (or not detectably) installed -- refusing to start the proxy")
            showWarningNotification(
                "HTTPS filtering needs one-time setup",
                "Install the local CA certificate from Settings first, then turn this on again.",
            )
            stopSelf()
            return
        }

        val port = try {
            bridge.start()
        } catch (e: NotImplementedError) {
            Log.e(TAG, "Go HTTPS proxy binding not wired up yet", e)
            showWarningNotification(
                "HTTPS filtering isn't wired up yet",
                "The Go proxy binding needs to be added to mobile.go first.",
            )
            stopSelf()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTPS proxy", e)
            stopSelf()
            return
        }

        Log.i(TAG, "HTTPS proxy listening on 127.0.0.1:$port")
        startForeground(NOTIFICATION_ID, buildStatusNotification(port))

        statsJob = serviceScope.launch {
            while (bridge.isRunning()) {
                delay(5_000)
                val stats = bridge.getStats()
                updateNotification(stats)
            }
        }
    }

    private fun stopProxy() {
        statsJob?.cancel()
        statsJob = null
        try {
            bridge.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping HTTPS proxy", e)
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "HTTPS filtering status", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun buildStatusNotification(port: Int): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTPS filtering active")
            .setContentText("Local proxy on port $port")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(stats: ProxyStatsData) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTPS filtering active")
            .setContentText(
                "${stats.responsesFiltered} filtered · ${stats.scriptletsInjected} scriptlets · " +
                    "${stats.m3uPruned} playlists pruned"
            )
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun showWarningNotification(title: String, text: String) {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
