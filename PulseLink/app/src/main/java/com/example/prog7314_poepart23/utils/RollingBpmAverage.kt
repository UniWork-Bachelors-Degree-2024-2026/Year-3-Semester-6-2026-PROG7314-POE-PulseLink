package com.example.prog7314_poepart23.utils

/** A time window of real positive readings; zero means no current reading. */
class RollingBpmAverage(private val nowMillis: () -> Long, private val windowMillis: Long = 15_000L) {
    private val readings = ArrayDeque<Pair<Long, Int>>()

    fun add(bpm: Int): Boolean {
        if (bpm !in 1..250) return false
        readings.addLast(nowMillis() to bpm)
        return true
    }

    fun average(): Int {
        val oldest = nowMillis() - windowMillis
        while (readings.isNotEmpty() && readings.first().first <= oldest) readings.removeFirst()
        return if (readings.isEmpty()) 0 else readings.map { it.second }.average().toInt()
    }

    fun clear() = readings.clear()
}
