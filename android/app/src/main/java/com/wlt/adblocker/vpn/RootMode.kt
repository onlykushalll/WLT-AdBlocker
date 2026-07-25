package com.wlt.adblocker.vpn

import android.util.Log
import java.io.File

/**
 * Phase 13g: Root Mode (optional)
 *
 * For rooted devices, modifies /etc/hosts directly instead of using VpnService.
 * Faster, less battery, system-wide blocking.
 *
 * This is OPTIONAL — most users don't have root. The app defaults to VPN mode.
 */
class RootMode {

    companion object {
        private const val TAG = "RootMode"
        private const val HOSTS_PATH = "/system/etc/hosts"
        private const val HOSTS_BACKUP = "/data/local/tmp/wlt-hosts-backup"
        private const val HOSTS_TEMP = "/data/local/tmp/wlt-hosts"
    }

    /**
     * Checks if root is available by running `su -c id`.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Backs up the original /etc/hosts file.
     */
    fun backupHosts(): Boolean {
        return try {
            execRoot("cp $HOSTS_PATH $HOSTS_BACKUP")
            Log.i(TAG, "Hosts file backed up to $HOSTS_BACKUP")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup hosts", e)
            false
        }
    }

    /**
     * Restores the original /etc/hosts file.
     */
    fun restoreHosts(): Boolean {
        return try {
            execRoot("cp $HOSTS_BACKUP $HOSTS_PATH")
            execRoot("chmod 644 $HOSTS_PATH")
            Log.i(TAG, "Hosts file restored")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore hosts", e)
            false
        }
    }

    /**
     * Writes blocked domains to /etc/hosts.
     * Each domain gets a line: "0.0.0.0 domain"
     */
    fun writeHosts(domains: List<String>): Boolean {
        return try {
            val sb = StringBuilder()
            sb.append("# WLT-Adblocker generated hosts file\n")
            sb.append("# Do not edit — managed by WLT-Adblocker\n")
            sb.append("127.0.0.1 localhost\n")
            sb.append("::1 localhost\n\n")
            for (domain in domains) {
                sb.append("0.0.0.0 $domain\n")
            }

            // Write to temp file first
            File(HOSTS_TEMP).writeText(sb.toString())

            // Remount /system as read-write, copy, remount read-only
            execRoot("mount -o remount,rw /system")
            execRoot("cp $HOSTS_TEMP $HOSTS_PATH")
            execRoot("chmod 644 $HOSTS_PATH")
            execRoot("mount -o remount,ro /system")

            Log.i(TAG, "Hosts file written: ${domains.size} domains")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write hosts", e)
            false
        }
    }

    /**
     * Appends a single domain to /etc/hosts.
     */
    fun addDomain(domain: String): Boolean {
        return try {
            execRoot("echo '0.0.0.0 $domain' >> $HOSTS_PATH")
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Removes a domain from /etc/hosts.
     */
    fun removeDomain(domain: String): Boolean {
        return try {
            execRoot("sed -i '/0.0.0.0 $domain/d' $HOSTS_PATH")
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun execRoot(cmd: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        process.waitFor()
        if (error.isNotEmpty()) {
            Log.w(TAG, "Root command error: $error")
        }
        return output
    }
}
