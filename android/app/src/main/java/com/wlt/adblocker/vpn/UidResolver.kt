package com.wlt.adblocker.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.net.InetSocketAddress

/**
 * Per-app UID resolution: maps an outgoing connection (src IP+port, dst
 * IP+port, protocol) to the Android UID that owns it.
 *
 * Why this exists: when a DNS query or HTTPS connection comes through
 * the VPN tun, we want to attribute it to a specific app ("YouTube
 * tried to load ads.google.com"). Without UID resolution, all traffic
 * looks the same — we can see what's blocked but not who asked for it.
 *
 * Two implementations, by API level:
 *
 *  - **Android 10+**: [ConnectivityManager.getConnectionOwnerUid] —
 *    the official, supported API. Fast and accurate.
 *  - **Android 9 and below**: parse `/proc/net/udp` and `/proc/net/tcp`
 *    manually. Same technique NetGuard uses (ServiceSinkhole.java:2025).
 *    Less accurate (race-y if connections churn fast) but works on
 *    older devices.
 *
 * Once we have a UID, [uidToPackageName] and [uidToAppName] translate
 * it to user-facing strings via PackageManager.
 */
class UidResolver(private val context: Context) {

    companion object {
        private const val TAG = "UidResolver"

        // Special system UIDs (from NetGuard Rule.java). Used in diagnostics.
        const val UID_ROOT = 0
        const val UID_SYSTEM = 1000
        const val UID_MEDIA = 1013
        const val UID_MULTICAST = 1020
        const val UID_GPS = 1021
        const val UID_DNS = 1051
    }

    private val pm = context.packageManager
    private val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    // Cache of UID → package+app name, populated lazily and never invalidated
    // (UIDs are stable per-boot). Bounded by the number of installed apps,
    // typically <200 entries.
    private val uidCache = HashMap<Int, Pair<String, String>>()

    /** Returns the UID that owns the connection from [srcPort] to
     *  [dstIp]:[dstPort] over [protocol] (TCP or UDP). Returns
     *  [Process.INVALID_UID] (-1) if the owner can't be determined. */
    fun getConnectionOwnerUid(
        protocol: Int,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
    ): Int {
        // Try ConnectivityManager.getConnectionOwnerUid on Android 11+ (API 30).
        // The API was added in API 30 with the PROTOCOL_TCP/PROTOCOL_UDP constants.
        // On older Android, fall through to /proc/net parsing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && connMgr != null) {
            try {
                // NetworkCapabilities.PROTOCOL_TCP = 6, PROTOCOL_UDP = 17 (added API 30).
                // We use our own PacketIo constants (same values) to avoid hard-coding
                // an API 30 dependency in the constant lookup itself.
                val proto = when (protocol) {
                    PacketIo.IP_PROTO_TCP -> 6
                    PacketIo.IP_PROTO_UDP -> 17
                    else -> return Process.INVALID_UID
                }
                val uid = connMgr.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIp, srcPort),
                    InetSocketAddress(dstIp, dstPort),
                )
                if (uid != Process.INVALID_UID) return uid
            } catch (e: Exception) {
                Log.w(TAG, "getConnectionOwnerUid failed, falling back to /proc/net", e)
            }
        }
        // Fallback: parse /proc/net/udp or /proc/net/tcp
        return resolveUidFromProc(protocol, srcPort, dstIp, dstPort)
    }

    /** Parses /proc/net/udp (or /proc/net/tcp for TCP) to find the UID
     *  that owns a connection matching [srcPort] and [dstIp]:[dstPort].
     *  Returns [Process.INVALID_UID] if not found. */
    private fun resolveUidFromProc(protocol: Int, srcPort: Int, dstIp: String, dstPort: Int): Int {
        val filename = when (protocol) {
            PacketIo.IP_PROTO_TCP -> "/proc/net/tcp"
            PacketIo.IP_PROTO_UDP -> "/proc/net/udp"
            else -> return Process.INVALID_UID
        }
        val file = File(filename)
        if (!file.canRead()) return Process.INVALID_UID
        try {
            // Format (per /proc/net/tcp):
            //   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  ...
            //   0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0  ...
            // Addresses are little-endian hex, ports are big-endian hex.
            val srcPortHex = srcPort.toString(16).padStart(4, '0').uppercase()
            val dstPortHex = dstPort.toString(16).padStart(4, '0').uppercase()
            val dstIpHex = ipToLittleEndianHex(dstIp)
            if (dstIpHex == null) return Process.INVALID_UID

            file.useLines { lines ->
                // Skip header line
                val iterator = lines.iterator()
                if (iterator.hasNext()) iterator.next()
                while (iterator.hasNext()) {
                    val line = iterator.next()
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size < 8) continue
                    // parts[1] = local_address (IP:Port)
                    // parts[2] = rem_address (IP:Port)
                    // parts[7] = uid
                    val local = parts[1]
                    val remote = parts[2]
                    val uidStr = parts[7]
                    // Match local port (our source port for outgoing)
                    if (!local.endsWith(":$srcPortHex", ignoreCase = true)) continue
                    // Match remote address:port
                    if (!remote.equals("$dstIpHex:$dstPortHex", ignoreCase = true)) continue
                    return uidStr.toIntOrNull() ?: Process.INVALID_UID
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $filename", e)
        }
        return Process.INVALID_UID
    }

    /** Converts a dotted-quad IP to the little-endian hex format used
     *  by /proc/net/tcp. "1.2.3.4" → "04030201". Returns null on parse error. */
    private fun ipToLittleEndianHex(ip: String): String? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val bytes = IntArray(4)
        for (i in 0..3) {
            bytes[i] = parts[i].toIntOrNull() ?: return null
            if (bytes[i] !in 0..255) return null
        }
        // Little-endian: bytes[3] is the high byte in the printed hex
        return "%02X%02X%02X%02X".format(bytes[3], bytes[2], bytes[1], bytes[0])
    }

    /** Returns the package name for [uid], or null if no installed app
     *  owns that UID. Cached. */
    fun uidToPackageName(uid: Int): String? {
        if (uid == Process.INVALID_UID) return null
        synchronized(uidCache) {
            uidCache[uid]?.let { return it.first }
        }
        val pkg = try {
            val packages = pm.getPackagesForUid(uid) ?: emptyArray()
            packages.firstOrNull()
        } catch (e: Exception) {
            null
        }
        // Cache even nulls (so we don't repeatedly probe the same invalid UID)
        val appName = pkg?.let { getAppName(it) } ?: ""
        synchronized(uidCache) {
            uidCache[uid] = (pkg ?: "") to appName
        }
        return pkg
    }

    /** Returns the human-readable app name for [uid], or null. */
    fun uidToAppName(uid: Int): String? {
        if (uid == Process.INVALID_UID) return null
        synchronized(uidCache) {
            uidCache[uid]?.let { return it.second.ifEmpty { null } }
        }
        val pkg = uidToPackageName(uid) ?: return null
        return getAppName(pkg)
    }

    private fun getAppName(pkg: String): String {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            pkg // fall back to package name
        }
    }

    /** Special-UID → human label. Used in the UI for system processes. */
    fun describeUid(uid: Int): String {
        return when (uid) {
            UID_ROOT -> "root"
            UID_SYSTEM -> "Android system"
            UID_MEDIA -> "Media server"
            UID_MULTICAST -> "Multicast"
            UID_GPS -> "GPS"
            UID_DNS -> "DNS server"
            Process.INVALID_UID -> "unknown"
            else -> uidToAppName(uid) ?: uidToPackageName(uid) ?: "uid=$uid"
        }
    }
}
