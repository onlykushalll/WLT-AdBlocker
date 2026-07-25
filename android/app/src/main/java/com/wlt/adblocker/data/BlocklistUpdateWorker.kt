package com.wlt.adblocker.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wlt.adblocker.filter.BlocklistManager
import com.wlt.adblocker.filter.BlocklistParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that downloads fresh blocklists every 24h
 * and reloads them into [BlocklistManager].
 *
 * Sources (from the project's sources.json — kept here as a hardcoded
 * fallback because the JSON parsing path is brittle across config changes):
 *  - OISD Big: https://big.oisd.nl/domainswild (wildcard format, ~150k domains)
 *  - HaGeZi Normal: https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains-normal.txt
 *
 * On success: parsed domains are written to `filesDir/blocklists/oisd.txt`
 * and `filesDir/blocklists/hagezi.txt`, and [BlocklistManager.loadFromFile]
 * is called to atomically swap them into the live trie.
 *
 * On failure (network error, parse error): the existing files (if any)
 * are kept untouched. The worker returns Result.retry() so WorkManager
 * will try again with exponential backoff.
 *
 * Privacy: downloads go directly to the device. No cloud proxy, no
 * telemetry, no analytics. The user's IP is exposed to the source
 * domains (same as any blocklist download) but that's it.
 */
class BlocklistUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "BlocklistWorker"
        private const val WORK_NAME = "wlt_blocklist_update"

        private val SOURCES = listOf(
            Source("oisd", "https://big.oisd.nl/domainswild"),
            Source("hagezi-normal", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains-normal.txt"),
        )

        /** Schedules the 24-hour periodic worker. Idempotent — uses
         *  [ExistingPeriodicWorkPolicy.KEEP] so re-calling won't reset the
         *  existing schedule's next-fire-time. Call from Application.onCreate. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(
                24, TimeUnit.HOURS,
                6, TimeUnit.HOURS, // flex period: run in last 6h of the 24h window
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            Log.i(TAG, "Scheduled 24h periodic blocklist update worker")
        }
    }

    private data class Source(val name: String, val url: String)

    override suspend fun doWork(): Result {
        val filesDir = applicationContext.filesDir
        val blocklistDir = File(filesDir, "blocklists").apply { mkdirs() }
        val blocklistManager = BlocklistManager(applicationContext)

        var anySuccess = false
        var allFailed = true

        for (source in SOURCES) {
            try {
                val targetFile = File(blocklistDir, "${source.name}.txt")
                val downloaded = downloadTo(source.url, targetFile)
                if (downloaded > 0) {
                    val loaded = blocklistManager.loadFromFile(targetFile, source = "network")
                    Log.i(TAG, "Loaded ${source.name}: $loaded rules")
                    anySuccess = true
                    allFailed = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update ${source.name}", e)
            }
        }

        // Persist update timestamp via PrefsRepository (best effort)
        try {
            PrefsRepository(applicationContext).setLastBlocklistUpdate(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist last-update timestamp", e)
        }

        return when {
            anySuccess -> Result.success()
            allFailed -> Result.retry()
            else -> Result.success()
        }
    }

    /** Downloads [url] to [targetFile] using a stream to avoid loading the
     *  whole file in memory (some blocklists are >5MB). Returns the number
     *  of bytes written. Throws on any I/O or HTTP error. */
    private fun downloadTo(url: String, targetFile: File): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "WLT-Adblocker/1.0 (Android blocklist updater)")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("HTTP $code for $url")
            }
            val tmp = File(targetFile.parentFile, "${targetFile.name}.tmp")
            try {
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Validate the downloaded file looks like a blocklist — at least
                // one valid domain line. This catches proxy error pages.
                val sample = tmp.readText(Charsets.UTF_8)
                val (block, _) = BlocklistParser.parse(sample)
                if (block.isEmpty()) {
                    throw java.io.IOException("Downloaded $url produced zero block rules — refusing")
                }
                if (targetFile.exists()) targetFile.delete()
                tmp.renameTo(targetFile)
                return targetFile.length()
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        } finally {
            connection.disconnect()
        }
    }
}
