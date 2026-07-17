package com.wlt.adblocker.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that auto-updates blocklists every 24 hours.
 *
 * Downloads each enabled source from sources.json, parses domains, and
 * writes them to the app's internal storage where the VPN service can load them.
 *
 * WLT's privacy principle: downloads go directly to the device, no cloud proxy.
 */
class BlocklistUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BlocklistWorker"
        private const val WORK_NAME = "wlt_blocklist_update"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                24, TimeUnit.HOURS
            ).setInitialDelay(1, TimeUnit.HOURS).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Scheduled 24h blocklist updates")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled blocklist updates")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting blocklist update")
        var totalDownloaded = 0
        var failedSources = 0

        val sources = listOf(
            "https://big.oisd.nl/domainswild" to "oisd_big.txt",
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/normal.txt" to "hagezi_normal.txt"
        )

        for ((url, filename) in sources) {
            try {
                val domains = downloadBlocklist(url)
                if (domains.isNotEmpty()) {
                    saveBlocklist(applicationContext, filename, domains)
                    totalDownloaded += domains.size
                    Log.i(TAG, "Downloaded $filename: ${domains.size} domains")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download $filename: ${e.message}")
                failedSources++
            }
        }

        WltDataStore.setLastUpdate((System.currentTimeMillis() / 1000).toInt())
        Log.i(TAG, "Update complete: $totalDownloaded domains, $failedSources failures")
        return Result.success()
    }

    private fun downloadBlocklist(urlStr: String): List<String> {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "WLT-Adblocker/0.1")

        if (conn.responseCode != 200) {
            conn.disconnect()
            throw Exception("HTTP ${conn.responseCode}")
        }

        val domains = mutableListOf<String>()
        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!.trim()
                if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue
                // Strip wildcards and trailing dots
                val d = l.removePrefix("*.").trimEnd('.')
                if (d.contains(".") && d.length <= 253) {
                    domains.add(d)
                }
            }
        }
        conn.disconnect()
        return domains
    }

    private fun saveBlocklist(context: Context, filename: String, domains: List<String>) {
        val file = context.getFileStreamPath(filename)
        file.bufferedWriter().use { writer ->
            for (d in domains) {
                writer.write(d)
                writer.newLine()
            }
        }
    }
}
