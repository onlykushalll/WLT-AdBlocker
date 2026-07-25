package com.wlt.adblocker.data

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 60-minute time series of block/allow counts, for the dashboard sparkline.
 *
 * Each [Point] is one minute of activity. The dashboard plots these as a
 * block-rate sparkline. We keep 60 points so the chart shows the last
 * hour of activity — beyond that, the user can switch to the QueryLog
 * screen for raw query-level history.
 *
 * Persistence: NOT persisted to disk. A fresh process starts with an
 * empty chart; this is fine because the chart is a "live activity"
 * visualization, not historical analytics.
 */
class StatsHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 60 // 60 minutes
    }

    /** One minute of activity. */
    data class Point(
        val timestamp: Long,
        val blocked: Int,
        val allowed: Int,
    ) {
        /** Total queries this minute. Returns 0 if no traffic. */
        fun total(): Int = blocked + allowed

        /** Block rate as a Float in 0.0..1.0. Returns 0.0 if no traffic
         *  (avoiding the divide-by-zero that would otherwise crash the chart). */
        fun blockRate(): Float = if (total() == 0) 0f else blocked.toFloat() / total().toFloat()
    }

    private val lock = ReentrantReadWriteLock()
    private val buffer = ArrayDeque<Point>(capacity)

    /** Adds a point, evicting the oldest if at capacity. */
    fun add(point: Point) {
        lock.write {
            if (buffer.size >= capacity) {
                buffer.pollFirst()
            }
            buffer.addLast(point)
        }
    }

    /** Returns the [n] most recent points, oldest first (chart-friendly order). */
    fun recent(n: Int): List<Point> {
        lock.read {
            if (buffer.isEmpty()) return emptyList()
            val start = maxOf(0, buffer.size - n)
            return buffer.toList().subList(start, buffer.size)
        }
    }

    /** Returns all points, oldest first. */
    fun all(): List<Point> = lock.read { buffer.toList() }

    /** Total blocked across all points. */
    fun totalBlocked(): Int = lock.read { buffer.sumOf { it.blocked } }

    /** Total allowed across all points. */
    fun totalAllowed(): Int = lock.read { buffer.sumOf { it.allowed } }

    /** Aggregate block rate across the entire buffer. Returns 0f if no traffic. */
    fun aggregateBlockRate(): Float {
        lock.read {
            val b = buffer.sumOf { it.blocked }
            val a = buffer.sumOf { it.allowed }
            val total = b + a
            return if (total == 0) 0f else b.toFloat() / total.toFloat()
        }
    }

    /** Clears all points. */
    fun clear() = lock.write { buffer.clear() }

    fun size(): Int = lock.read { buffer.size }
}
