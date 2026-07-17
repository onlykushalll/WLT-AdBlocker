package com.wlt.adblocker.data

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Time-series block stats for the dashboard chart.
 * Records a snapshot every minute: (timestamp, blocked, allowed).
 *
 * Bounded to MAX_POINTS to limit memory. The dashboard reads this
 * to draw a sparkline / bar chart of block rate over time.
 */
object StatsHistory {
    private const val MAX_POINTS = 60 // 60 minutes of history

    data class Point(
        val timestamp: Long,
        val blocked: Long,
        val allowed: Long
    ) {
        fun total(): Long = blocked + allowed
        fun blockRate(): Float = if (total() == 0L) 0f else blocked.toFloat() / total()
    }

    private val points = ConcurrentLinkedDeque<Point>()

    fun record(blocked: Long, allowed: Long) {
        points.addLast(Point(System.currentTimeMillis(), blocked, allowed))
        while (points.size > MAX_POINTS) points.removeFirst()
    }

    fun recent(n: Int = MAX_POINTS): List<Point> {
        return points.toList().takeLast(n)
    }

    fun clear() { points.clear() }

    fun size(): Int = points.size
}
