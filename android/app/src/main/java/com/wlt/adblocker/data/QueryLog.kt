package com.wlt.adblocker.data

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory query log — bounded ring buffer of recent DNS queries.
 *
 * Each entry records: domain, timestamp, blocked/allowed, reason, SDK (if any).
 * The UI reads this for the Query Log screen.
 *
 * Uses a ConcurrentLinkedDeque for thread-safe append + read.
 * Bounded to MAX_ENTRIES to limit memory.
 */
data class QueryLogEntry(
    val domain: String,
    val timestamp: Long,
    val blocked: Boolean,
    val reason: String,
    val sdk: String? = null,
    val sourceApp: String? = null
)

object QueryLog {
    private const val MAX_ENTRIES = 2000
    private val entries = ConcurrentLinkedDeque<QueryLogEntry>()

    @Volatile private var totalBlocked = 0L
    @Volatile private var totalAllowed = 0L

    fun add(entry: QueryLogEntry) {
        entries.addFirst(entry)
        if (entry.blocked) totalBlocked++ else totalAllowed++
        // Trim if over capacity
        while (entries.size > MAX_ENTRIES) {
            entries.removeLast()
        }
    }

    fun recent(n: Int = 100): List<QueryLogEntry> {
        return entries.take(n)
    }

    fun recentBlocked(n: Int = 50): List<QueryLogEntry> {
        return entries.filter { it.blocked }.take(n)
    }

    fun recentAllowed(n: Int = 50): List<QueryLogEntry> {
        return entries.filter { !it.blocked }.take(n)
    }

    fun totalBlockedCount(): Long = totalBlocked
    fun totalAllowedCount(): Long = totalAllowed
    fun totalCount(): Long = totalBlocked + totalAllowed

    fun clear() {
        entries.clear()
        totalBlocked = 0L
        totalAllowed = 0L
    }

    fun size(): Int = entries.size
}
