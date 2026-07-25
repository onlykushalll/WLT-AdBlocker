package com.wlt.adblocker.data

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * In-memory bounded ring buffer of recent DNS queries.
 *
 * Bounded at 2000 entries on purpose:
 *  - Small enough to fit comfortably in memory (each entry <100 bytes
 *    → <200KB even at the cap), so no eviction pressure on low-end devices.
 *  - Large enough to give the user a useful "what just happened" view
 *    without having to scroll through thousands of stale entries.
 *
 * Reads (UI) and writes (VPN loop) happen concurrently. We use a
 * [ReentrantReadWriteLock] rather than a synchronized block because
 * reads are far more frequent than writes, and we don't want to
 * block the VPN loop on a slow UI redraw.
 *
 * Persistence: intentionally NOT persisted to disk. Query logs are
 * ephemeral diagnostics, not user data — a restart should start with
 * a clean slate, both for privacy and because the file would otherwise
 * grow without bound on a long-running device.
 */
class QueryLog(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }

    /** One DNS query, as seen by the VPN loop. */
    data class Entry(
        val domain: String,
        val timestamp: Long,
        val blocked: Boolean,
        val reason: String,
        val sdk: String? = null,
        val uid: Int? = null,
        val packageName: String? = null,
    )

    private val lock = ReentrantReadWriteLock()
    // ArrayDeque is a Java collections class (resizable-array deque) — NOT kotlin.collections.ArrayDeque.
    // We use it for O(1) add/remove at both ends, plus random access for "recent(n)".
    private val buffer = ArrayDeque<Entry>(capacity)

    /** Adds an entry, evicting the oldest if at capacity. */
    fun add(entry: Entry) {
        lock.write {
            if (buffer.size >= capacity) {
                buffer.pollFirst()
            }
            buffer.addLast(entry)
        }
    }

    /** Returns the [n] most recent entries, newest last. */
    fun recent(n: Int): List<Entry> {
        lock.read {
            if (buffer.isEmpty()) return emptyList()
            val start = maxOf(0, buffer.size - n)
            return buffer.toList().subList(start, buffer.size)
        }
    }

    /** Returns the [n] most recent BLOCKED entries, newest last. */
    fun recentBlocked(n: Int): List<Entry> {
        lock.read {
            return buffer.toList().filter { it.blocked }.takeLast(n)
        }
    }

    /** Returns the [n] most recent ALLOWED entries, newest last. */
    fun recentAllowed(n: Int): List<Entry> {
        lock.read {
            return buffer.toList().filterNot { it.blocked }.takeLast(n)
        }
    }

    /** Total entries currently in the buffer. */
    fun size(): Int = lock.read { buffer.size }

    /** Clears all entries. */
    fun clear() = lock.write { buffer.clear() }
}
