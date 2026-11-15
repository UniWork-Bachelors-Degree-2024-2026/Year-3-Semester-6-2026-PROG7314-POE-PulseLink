package com.example.prog7314_poepart23.utils

/** Counts recorded time, excluding manual pauses and lead-disconnection pauses. */
class RecordingSessionClock(
    private val nowMillis: () -> Long,
    val durationMillis: Long = 90_000L
) {
    private var savedRemaining = durationMillis
    private var resumedAt: Long? = null

    val remainingMillis: Long
        get() = (savedRemaining - (resumedAt?.let { nowMillis() - it } ?: 0L)).coerceIn(0L, durationMillis)

    val elapsedSeconds: Int
        get() = ((durationMillis - remainingMillis) / 1000L).toInt()

    fun reset() {
        savedRemaining = durationMillis
        resumedAt = null
    }

    fun resume() {
        if (resumedAt == null && savedRemaining > 0L) resumedAt = nowMillis()
    }

    fun pause() {
        savedRemaining = remainingMillis
        resumedAt = null
    }
}
