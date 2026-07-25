package com.wlt.adblocker.vpn

import android.util.Log
import android.util.LruCache
import java.util.concurrent.atomic.AtomicLong

/**
 * IP→Domain Reverse Lookup Cache — Phase 9a (NetGuard technique)
 *
 * When WLT resolves a DNS query (e.g., ads.example.com → 1.2.3.4), it
 * stores the mapping here. When a TCP/UDP connection is made to 1.2.3.4,
 * we look up the domain — enabling per-app domain attribution.
 *
 * Uses LRU cache with 5-minute TTL. Thread-safe.
 *
 * Memory: ~500KB for 5,000 entries.
 */
class DomainIpCache(maxSize: Int = 5_000) {

    companion object {
        private const val TAG = "DomainIpCache"
        private const val TTL_MS = 300_000L // 5 minutes
    }

    private data class Entry(
        val domain: String,
        val expiry: Long,
    )

    private val cache = LruCache<String, Entry>(maxSize)
    private val lookups = AtomicLong(0)
    private val hits = AtomicLong(0)

    /**
     * Stores a DNS resolution result.
     * @param ip The resolved IP address (e.g., "1.2.3.4")
     * @param domain The domain name that resolved to this IP
     */
    @Synchronized
    fun put(ip: String, domain: String) {
        cache.put(ip, Entry(domain, System.currentTimeMillis() + TTL_MS))
    }

    /**
     * Looks up the domain for a given IP address.
     * Returns the domain name, or null if not cached or expired.
     */
    @Synchronized
    fun get(ip: String): String? {
        lookups.incrementAndGet()
        val entry = cache.get(ip) ?: return null
        if (System.currentTimeMillis() > entry.expiry) {
            cache.remove(ip)
            return null
        }
        hits.incrementAndGet()
        return entry.domain
    }

    /** Clears all cached entries. */
    @Synchronized
    fun clear() {
        cache.evictAll()
    }

    /** Returns the number of cached entries. */
    fun size(): Int = cache.size()

    /** Returns the cache hit rate (0.0 to 1.0). */
    fun hitRate(): Float {
        val l = lookups.get()
        return if (l > 0) hits.get().toFloat() / l else 0f
    }
}
