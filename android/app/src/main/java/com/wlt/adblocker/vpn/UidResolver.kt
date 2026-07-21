package com.wlt.adblocker.vpn

import android.annotation.TargetApi
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.net.InetSocketAddress

/**
 * UID Resolver — maps network connections to the originating app's UID.
 * Ported from NetGuard's getUidQ() method.
 * Uses ConnectivityManager.getConnectionOwnerUid() (Android 10+).
 * Fallback: /proc/net/udp parsing for older Android.
 */
object UidResolver {

    private const val TAG = "UidResolver"
    private const val INVALID_UID = -1

    @TargetApi(Build.VERSION_CODES.Q)
    fun getUid(context: Context, protocol: Int, srcAddr: String, srcPort: Int, dstAddr: String, dstPort: Int): Int {
        if (protocol != 6 && protocol != 17) return INVALID_UID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return INVALID_UID
                val local = InetSocketAddress(srcAddr, srcPort)
                val remote = InetSocketAddress(dstAddr, dstPort)
                val uid = cm.getConnectionOwnerUid(protocol, local, remote)
                if (uid != Process.INVALID_UID) return uid
            } catch (e: Exception) { Log.w(TAG, "getConnectionOwnerUid failed: ${e.message}") }
        }
        return getUidFromProc(protocol, srcPort)
    }

    private fun getUidFromProc(protocol: Int, srcPort: Int): Int {
        val procFile = if (protocol == 6) "/proc/net/tcp" else "/proc/net/udp"
        try {
            val lines = java.io.File(procFile).readLines()
            for (i in 1 until lines.size) {
                val fields = lines[i].trim().split(Regex("\\s+"))
                if (fields.size < 8) continue
                val portHex = fields[1].substringAfter(":")
                if (portHex.toInt(16) == srcPort) return fields[7].toInt()
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to parse $procFile: ${e.message}") }
        return INVALID_UID
    }

    fun uidToPackageName(context: Context, uid: Int): String? {
        if (uid <= 0 || uid == Process.INVALID_UID) return null
        try {
            val packages = context.packageManager.getPackagesForUid(uid)
            if (packages != null && packages.isNotEmpty()) return packages[0]
        } catch (e: Exception) { }
        return null
    }

    fun uidToAppName(context: Context, uid: Int): String {
        val pkg = uidToPackageName(context, uid) ?: return "UID:$uid"
        try {
            val pm = context.packageManager
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { return pkg }
    }
}
