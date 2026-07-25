package com.wlt.adblocker.data

import android.util.Log
import com.wlt.adblocker.data.BlockCategory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Per-App Network Statistics — Phase 9b/9c
 *
 * Tracks per-UID network activity: queries, blocked, trackers detected,
 * top domains, and data usage. Powers the App Analytics screen.
 *
 * Thread-safe. Bounded to top 100 apps to limit memory.
 */
class AppNetworkStats {

    companion object {
        private const val TAG = "AppNetworkStats"
        private const val MAX_APPS = 100
        private const val MAX_DOMAINS_PER_APP = 50
    }

    data class AppStats(
        val packageName: String,
        val uid: Int,
        val queries: AtomicLong = AtomicLong(0),
        val blocked: AtomicLong = AtomicLong(0),
        val bytesIn: AtomicLong = AtomicLong(0),
        val bytesOut: AtomicLong = AtomicLong(0),
        val domains: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap(),
        val trackers: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap(),
        val categories: ConcurrentHashMap<BlockCategory, AtomicLong> = ConcurrentHashMap(),
    )

    private val stats = ConcurrentHashMap<String, AppStats>()
    private val totalBlocked = AtomicLong(0)
    private val totalQueries = AtomicLong(0)
    private val totalTrackers = AtomicLong(0)

    /**
     * Records a DNS query for a specific app.
     *
     * @param packageName The app's package name (null for system/unknown)
     * @param uid The app's UID
     * @param domain The queried domain
     * @param blocked Whether the query was blocked
     * @param category The block category (if blocked)
     * @param trackerName The tracker/SDK name (if detected)
     */
    fun recordQuery(
        packageName: String?,
        uid: Int,
        domain: String,
        blocked: Boolean,
        category: BlockCategory? = null,
        trackerName: String? = null,
    ) {
        if (packageName == null) return

        totalQueries.incrementAndGet()
        if (blocked) totalBlocked.incrementAndGet()

        val appStats = stats.getOrPut(packageName) {
            if (stats.size >= MAX_APPS) {
                // Evict the app with fewest queries
                evictLeastActive()
            }
            AppStats(packageName, uid)
        }

        appStats.queries.incrementAndGet()
        if (blocked) appStats.blocked.incrementAndGet()

        // Track domain (bounded per-app)
        if (appStats.domains.size < MAX_DOMAINS_PER_APP || appStats.domains.containsKey(domain)) {
            appStats.domains.getOrPut(domain) { AtomicLong(0) }.incrementAndGet()
        }

        // Track tracker
        if (trackerName != null) {
            appStats.trackers.getOrPut(trackerName) { AtomicLong(0) }.incrementAndGet()
            totalTrackers.incrementAndGet()
        }

        // Track category
        if (category != null) {
            appStats.categories.getOrPut(category) { AtomicLong(0) }.incrementAndGet()
        }
    }

    /**
     * Records data usage for a specific app.
     */
    fun recordDataUsage(packageName: String?, bytesIn: Long, bytesOut: Long) {
        if (packageName == null) return
        val appStats = stats[packageName] ?: return
        appStats.bytesIn.addAndGet(bytesIn)
        appStats.bytesOut.addAndGet(bytesOut)
    }

    /** Returns stats for a specific app, or null. */
    fun getStats(packageName: String): AppStats? = stats[packageName]

    /** Returns all app stats, sorted by query count (descending). */
    fun getAllStats(): List<AppStats> {
        return stats.values.sortedByDescending { it.queries.get() }
    }

    /** Returns the top N apps by query count. */
    fun getTopApps(n: Int): List<AppStats> {
        return getAllStats().take(n)
    }

    /** Returns aggregate statistics. */
    fun getAggregate(): AggregateStats {
        return AggregateStats(
            totalApps = stats.size,
            totalQueries = totalQueries.get(),
            totalBlocked = totalBlocked.get(),
            totalTrackers = totalTrackers.get(),
            blockRate = if (totalQueries.get() > 0)
                totalBlocked.get().toFloat() / totalQueries.get()
            else 0f,
        )
    }

    /** Clears all stats. */
    fun clear() {
        stats.clear()
        totalBlocked.set(0)
        totalQueries.set(0)
        totalTrackers.set(0)
    }

    private fun evictLeastActive() {
        val least = stats.minByOrNull { it.value.queries.get() }
        least?.let { stats.remove(it.key) }
    }

    data class AggregateStats(
        val totalApps: Int,
        val totalQueries: Long,
        val totalBlocked: Long,
        val totalTrackers: Long,
        val blockRate: Float,
    )
}
