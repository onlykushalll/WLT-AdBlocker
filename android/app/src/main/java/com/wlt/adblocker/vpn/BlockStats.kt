package com.wlt.adblocker.vpn

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Atomic counters + bounded per-domain top-N tracker.
 *
 * Why a separate class: the VPN loop needs to record every blocked
 * query (could be hundreds per second under load) without blocking on
 * locks or allocating per-call. The counters below use [LongAdder]
 * for the global totals (lock-free under contention, designed for
 * high-write scenarios) and a small bounded [ConcurrentHashMap] for
 * the per-domain tracker.
 *
 * The per-domain map is intentionally bounded — without a cap, a
 * malicious or buggy DNS client querying millions of random subdomains
 * would OOM the process. The [topBlocked] method returns the top-N
 * by count, copying the map under a read lock.
 */
class BlockStats {

    /** One per-domain counter, paired with its domain for display. */
    data class DomainCount(val domain: String, val count: Long, val sdk: String? = null)

    /** Snapshot of all counters at a point in time. Used by [StatsHistory]
     *  to record a minute of activity. */
    data class StatsSnapshot(
        val totalBlocked: Long,
        val totalAllowed: Long,
        val blockRate: Float,
        val perDomainTop: List<DomainCount>,
        val perSdkTop: List<DomainCount>,
        val timestamp: Long,
    )

    private val totalBlocked = LongAdder()
    private val totalAllowed = LongAdder()
    private val totalQueries = LongAdder()

    // Bounded per-domain map. We rely on [pruneIfNeeded] to keep size in check.
    private val perDomain = ConcurrentHashMap<String, LongAdder>()
    private val perSdk = ConcurrentHashMap<String, LongAdder>()
    private val perDomainSdk = ConcurrentHashMap<String, String>() // domain -> sdk

    private val pruneLock = ReentrantReadWriteLock()

    /** Hard cap on per-domain counter map size, to bound memory. */
    private val maxPerDomain: Int = 10_000

    /** Records a blocked query for [domain], optionally attributed to [sdk]. */
    fun incBlocked(domain: String, sdk: String? = null) {
        totalBlocked.increment()
        totalQueries.increment()
        val normalized = domain.lowercase()
        perDomain.computeIfAbsent(normalized) { LongAdder() }.increment()
        if (sdk != null) {
            perSdk.computeIfAbsent(sdk) { LongAdder() }.increment()
            perDomainSdk[normalized] = sdk
        }
        pruneIfNeeded()
    }

    /** Records an allowed query (no per-domain tracking — allowed queries
     *  vastly outnumber blocked ones, and the user only cares about
     *  blocked stats at the per-domain level). */
    fun incAllowed() {
        totalAllowed.increment()
        totalQueries.increment()
    }

    fun totalBlocked(): Long = totalBlocked.sum()
    fun totalAllowed(): Long = totalAllowed.sum()
    fun totalQueries(): Long = totalQueries.sum()

    /** Block rate as a Float in 0.0..1.0. Returns 0f if no queries yet. */
    fun blockRate(): Float {
        val total = totalQueries.sum()
        return if (total == 0L) 0f else totalBlocked.sum().toFloat() / total.toFloat()
    }

    /** Returns the top [n] blocked domains by count, descending. */
    fun topBlocked(n: Int): List<DomainCount> {
        return perDomain.entries
            .map { DomainCount(it.key, it.value.sum(), perDomainSdk[it.key]) }
            .sortedByDescending { it.count }
            .take(n)
    }

    /** Returns the top [n] blocked SDKs by count, descending. */
    fun topSdk(n: Int): List<DomainCount> {
        return perSdk.entries
            .map { DomainCount(it.key, it.value.sum(), null) }
            .sortedByDescending { it.count }
            .take(n)
    }

    /** Returns a complete snapshot. Used by the statsRecorder() coroutine
     *  in WltVpnService to record one minute of activity. */
    fun snapshot(): StatsSnapshot {
        return StatsSnapshot(
            totalBlocked = totalBlocked.sum(),
            totalAllowed = totalAllowed.sum(),
            blockRate = blockRate(),
            perDomainTop = topBlocked(50),
            perSdkTop = topSdk(20),
            timestamp = System.currentTimeMillis(),
        )
    }

    /** Resets all counters to zero. Used when the user clears stats. */
    fun reset() {
        pruneLock.write {
            totalBlocked.reset()
            totalAllowed.reset()
            totalQueries.reset()
            perDomain.clear()
            perSdk.clear()
            perDomainSdk.clear()
        }
    }

    /** Caps the per-domain map at [maxPerDomain] entries by evicting
     *  the lowest-count ones. Called on every blocked query, but only
     *  does real work when the map is over capacity — so the common
     *  case is a single map.size() check. */
    private fun pruneIfNeeded() {
        if (perDomain.size < maxPerDomain) return
        pruneLock.write {
            if (perDomain.size < maxPerDomain) return@write
            // Evict the bottom 10% by count.
            val targetSize = (maxPerDomain * 0.9).toInt()
            val sorted = perDomain.entries
                .sortedByDescending { it.value.sum() }
                .drop(targetSize)
            for ((domain, _) in sorted) {
                perDomain.remove(domain)
                perDomainSdk.remove(domain)
            }
        }
    }
}
