package com.wlt.adblocker.vpn

import android.util.Log
import android.util.LruCache
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS Response Cache — Phase 8a
 *
 * Caches DNS responses (both allowed and blocked) to reduce upstream
 * queries by ~70% and provide <1ms response for cache hits.
 *
 * - Allowed responses: Cached for upstream TTL (capped at 3600s = 1 hour)
 * - Blocked responses: Cached for 300s (5 min) so blocklist updates take effect
 * - NXDOMAIN: Cached for 900s (15 min)
 *
 * Uses Android's LruCache for automatic LRU eviction. Thread-safe.
 *
 * Memory: ~1MB for 10,000 entries (each entry ~100 bytes).
 */
class DnsCache(maxSize: Int = 10_000) {

    companion object {
        private const val TAG = "DnsCache"

        // TTL caps (in milliseconds)
        private const val MAX_ALLOWED_TTL_MS = 3600_000L   // 1 hour
        private const val BLOCKED_TTL_MS = 300_000L        // 5 minutes
        private const val NXDOMAIN_TTL_MS = 900_000L       // 15 minutes
    }

    private data class CacheEntry(
        val response: ByteArray,
        val expiry: Long,        // System.currentTimeMillis() when entry expires
        val blocked: Boolean,
        val domain: String,
    )

    // LruCache is thread-safe and handles LRU eviction automatically.
    // Size is measured in entries (each ~100 bytes, so 10K entries ≈ 1MB).
    private val cache = LruCache<String, CacheEntry>(maxSize)

    // Statistics
    private val hits = AtomicLong(0)
    private val misses = AtomicLong(0)
    private val evictions = AtomicLong(0)

    /**
     * Looks up a cached DNS response for the given domain.
     * Returns the cached response bytes, or null if not cached or expired.
     *
     * @param domain The domain name (lowercased, no trailing dot)
     * @return Cached response bytes, or null if cache miss
     */
    @Synchronized
    fun get(domain: String): ByteArray? {
        val entry = cache.get(domain)
        if (entry == null) {
            misses.incrementAndGet()
            return null
        }

        // Check if expired
        if (System.currentTimeMillis() > entry.expiry) {
            cache.remove(domain)
            misses.incrementAndGet()
            return null
        }

        hits.incrementAndGet()
        Log.d(TAG, "Cache HIT: $domain (blocked=${entry.blocked})")
        return entry.response
    }

    /**
     * Stores a DNS response in the cache.
     *
     * @param domain The domain name (lowercased)
     * @param response The DNS response bytes
     * @param blocked Whether this was a blocked response (affects TTL)
     * @param isNXDomain Whether this is an NXDOMAIN response (affects TTL)
     * @param upstreamTtl The TTL from the upstream response (for allowed responses).
     *                    Ignored for blocked/NXDOMAIN responses.
     */
    @Synchronized
    fun put(
        domain: String,
        response: ByteArray,
        blocked: Boolean,
        isNXDomain: Boolean = false,
        upstreamTtl: Int = 300,
    ) {
        val now = System.currentTimeMillis()
        val ttl = when {
            isNXDomain -> NXDOMAIN_TTL_MS
            blocked -> BLOCKED_TTL_MS
            else -> minOf(upstreamTtl.toLong() * 1000, MAX_ALLOWED_TTL_MS)
        }

        val entry = CacheEntry(
            response = response,
            expiry = now + ttl,
            blocked = blocked,
            domain = domain,
        )

        // LruCache.put handles eviction internally
        cache.put(domain, entry)
    }

    /**
     * Removes a specific domain from the cache.
     * Useful when a user adds a custom rule that should take effect immediately.
     */
    @Synchronized
    fun remove(domain: String) {
        cache.remove(domain)
    }

    /**
     * Clears all cached entries.
     */
    @Synchronized
    fun clear() {
        cache.evictAll()
        Log.i(TAG, "Cache cleared")
    }

    /**
     * Clears only blocked entries (keeps allowed).
     * Useful when blocklists are updated — forces re-evaluation of blocked domains.
     */
    @Synchronized
    fun clearBlocked() {
        // LruCache doesn't support filtered eviction, so we snapshot and remove
        val keysToRemove = mutableListOf<String>()
        // We can't iterate LruCache directly, so use snapshot
        for (entry in cache.snapshot().entries) {
            if (entry.value.blocked) {
                keysToRemove.add(entry.key)
            }
        }
        for (key in keysToRemove) {
            cache.remove(key)
        }
        Log.i(TAG, "Cleared ${keysToRemove.size} blocked entries")
    }

    /** Returns the number of entries currently in the cache. */
    fun size(): Int = cache.size()

    /** Returns the maximum cache capacity. */
    fun maxSize(): Int = cache.maxSize()

    /** Returns the cache hit rate (0.0 to 1.0). */
    fun hitRate(): Float {
        val h = hits.get()
        val m = misses.get()
        val total = h + m
        return if (total > 0) h.toFloat() / total else 0f
    }

    /** Returns a snapshot of cache statistics for the UI. */
    fun stats(): CacheStats {
        return CacheStats(
            size = size(),
            maxSize = maxSize(),
            hits = hits.get(),
            misses = misses.get(),
            hitRate = hitRate(),
        )
    }

    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hits: Long,
        val misses: Long,
        val hitRate: Float,
    )
}
