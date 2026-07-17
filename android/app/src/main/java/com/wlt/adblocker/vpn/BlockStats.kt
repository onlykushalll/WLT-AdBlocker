package com.wlt.adblocker.vpn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregated block statistics — observable by the UI via flows.
 *
 * Tracked per-domain and per-SDK (when known). Bounded to prevent memory growth.
 */
object BlockStats {
    private val totalQueries = AtomicLong(0)
    private val totalBlocked = AtomicLong(0)
    private val totalAllowed = AtomicLong(0)

    // Per-domain block counts (top-N for UI). Bounded LRU-ish.
    private val domainCounts = ConcurrentHashMap<String, Long>()
    private const val MAX_DOMAINS = 5000

    fun onQuery(domain: String, blocked: Boolean) {
        totalQueries.incrementAndGet()
        if (blocked) {
            totalBlocked.incrementAndGet()
            domainCounts.compute(domain) { _, v -> (v ?: 0) + 1 }
            // Bound the map
            if (domainCounts.size > MAX_DOMAINS) {
                // Evict ~10% of entries (simplest approach — not true LRU but bounded)
                val toRemove = domainCounts.entries.sortedBy { it.value }.take(MAX_DOMAINS / 10)
                toRemove.forEach { domainCounts.remove(it.key) }
            }
        } else {
            totalAllowed.incrementAndGet()
        }
    }

    fun totalQueries(): Long = totalQueries.get()
    fun totalBlocked(): Long = totalBlocked.get()
    fun totalAllowed(): Long = totalAllowed.get()
    fun blockRate(): Float {
        val t = totalQueries.get()
        return if (t == 0L) 0f else totalBlocked.get().toFloat() / t
    }

    fun topBlockedDomains(n: Int): List<Pair<String, Long>> {
        return domainCounts.entries
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key to it.value }
    }

    fun reset() {
        totalQueries.set(0)
        totalBlocked.set(0)
        totalAllowed.set(0)
        domainCounts.clear()
    }
}
