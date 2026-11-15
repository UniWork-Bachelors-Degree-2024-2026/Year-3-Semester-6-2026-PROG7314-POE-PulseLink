package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.utils.RecordingSessionClock
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingSessionClockTest {
    @Test fun pausesExcludeDisconnectedTime() {
        var now = 0L
        val clock = RecordingSessionClock({ now })
        clock.resume()
        now = 15_000L
        clock.pause()
        now = 60_000L
        assertEquals(75_000L, clock.remainingMillis)
        assertEquals(15, clock.elapsedSeconds)
        clock.resume()
        now = 65_000L
        assertEquals(70_000L, clock.remainingMillis)
        clock.resume()
        now = 70_000L
        assertEquals(65_000L, clock.remainingMillis)
    }

    @Test fun restartResetsTheFullRecordingDuration() {
        var now = 0L
        val clock = RecordingSessionClock({ now })
        clock.resume()
        now = 100_000L
        assertEquals(0L, clock.remainingMillis)
        clock.pause()
        clock.reset()
        assertEquals(90_000L, clock.remainingMillis)
        assertEquals(0, clock.elapsedSeconds)
    }
}
