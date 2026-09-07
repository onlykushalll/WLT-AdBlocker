package com.wlt.adblocker.vpn

import android.content.Context
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import java.io.File

/**
 * Process-wide singleton holding diagnostic state for the DiagnosticScreen.
 *
 * The VPN service (WltVpnService) updates this state as it runs, and the
 * DiagnosticScreen reads it on a 1-second poll. This decouples the UI from
 * the service lifecycle — the screen works even when the service isn't
 * running (showing "not established" etc.).
 *
 * This is the #1 feature from the rebuild blueprint — without it, "nothing
 * works" was impossible to debug because there was zero runtime observability.
 */
object DiagnosticState {

    /** Engine status — set by GoBlockEngine on load. */
    data class EngineStatus(
        val loaded: Boolean,
        val className: String?,
        val error: String?,
        val ruleCount: Int,
        val engineVersion: String,
    )

    /** VPN status — set by WltVpnService on start/stop. */
    data class VpnStatus(
        val established: Boolean,
        val tunInterface: String,
        val tunAddress: String,
        val route: String,
        val uptime: String,
    )

    /** A single DNS query entry for the rolling log. */
    data class QueryEntry(
        val domain: String,
        val blocked: Boolean,
        val reason: String,
        val packageName: String?,
        val timestamp: Long,
    )

    /** Forwarder stats — set by Forwarder. */
    data class ForwarderStats(
        val activeTcpSessions: Int,
        val activeUdpSessions: Int,
        val totalForwarded: Long,
    )

    @Volatile
    var engineStatus: EngineStatus? = null
        private set

    @Volatile
    var vpnStatus: VpnStatus? = null
        private set

    @Volatile
    var forwarderStats: ForwarderStats? = null
        private set

    /** Rolling buffer of last 50 queries. */
    private val queryBuffer = ArrayDeque<QueryEntry>(50)
    private val queryLock = Any()

    /** The last time the VPN was started (epoch millis), for uptime calculation. */
    @Volatile
    private var vpnStartTime: Long = 0L

    /** Called by GoBlockEngine when it finishes loading (or fails). */
    fun setEngineStatus(status: EngineStatus) {
        engineStatus = status
    }

    /** Called by WltVpnService when the VPN is established. */
    fun setVpnEstablished(tunInterface: String, tunAddress: String, route: String) {
        vpnStartTime = System.currentTimeMillis()
        vpnStatus = VpnStatus(
            established = true,
            tunInterface = tunInterface,
            tunAddress = tunAddress,
            route = route,
            uptime = "0s",
        )
    }

    /** Called by WltVpnService when the VPN is stopped. */
    fun setVpnStopped() {
        vpnStatus = VpnStatus(
            established = false,
            tunInterface = "(none)",
            tunAddress = "(none)",
            route = "(none)",
            uptime = "0s",
        )
        vpnStartTime = 0L
    }

    /** Called by WltVpnService for every DNS query processed. */
    fun addQuery(entry: QueryEntry) {
        synchronized(queryLock) {
            if (queryBuffer.size >= 50) queryBuffer.removeFirst()
            queryBuffer.addLast(entry)
        }
    }

    /** The current rolling query log (newest first). */
    val recentQueries: List<QueryEntry>
        get() = synchronized(queryLock) { queryBuffer.toList().reversed() }

    /** Called by Forwarder to update session stats. */
    fun setForwarderStats(tcp: Int, udp: Int, totalForwarded: Long) {
        forwarderStats = ForwarderStats(tcp, udp, totalForwarded)
    }

    /** Returns the current VPN uptime as a human-readable string. */
    fun getVpnUptime(): String {
        if (vpnStartTime == 0L) return "0s"
        val seconds = (System.currentTimeMillis() - vpnStartTime) / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    /** Reads the Android Private DNS mode setting.
     *  Returns "off", "auto", "hostname", or "null" (not set). */
    fun getPrivateDnsMode(context: Context): String {
        return try {
            val mode = AndroidSettings.Global.getString(
                context.contentResolver,
                "private_dns_mode"
            ) ?: "null"
            mode
        } catch (e: Exception) {
            "error"
        }
    }

    /** Checks if the app is affected by battery optimization.
     *  Returns true if the app is NOT on the battery optimization whitelist. */
    fun isBatteryOptimized(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignoring: Boolean = pm.isIgnoringBatteryOptimizations(context.packageName)
            !ignoring
        } catch (e: Exception) {
            false
        }
    }

    /** Reads TUN interface RX packet count from /proc/net/dev.
     *  Returns 0 if tun0 doesn't exist or can't be read. */
    fun readTunRxPackets(): Long {
        return try {
            val file = File("/proc/net/dev")
            if (!file.exists()) return 0L
            file.useLines { lines ->
                for (line in lines) {
                    if (line.contains("tun0")) {
                        // Format: "tun0: rxBytes rxPackets ..."
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            // parts[0] is "tun0:" or "tun0"
                            return parts[2].toLongOrNull() ?: 0L
                        }
                    }
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }
}
