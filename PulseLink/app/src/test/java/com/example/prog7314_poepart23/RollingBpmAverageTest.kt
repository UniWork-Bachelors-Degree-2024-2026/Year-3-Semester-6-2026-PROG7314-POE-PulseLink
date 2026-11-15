package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.utils.RollingBpmAverage
import org.junit.Assert.*
import org.junit.Test

class RollingBpmAverageTest {
    @Test fun emptyAndInvalidReadingsNeverInventABpm() {
        val average = RollingBpmAverage({ 0L })
        assertEquals(0, average.average())
        assertFalse(average.add(0))
        assertFalse(average.add(-5))
        assertFalse(average.add(251))
        assertEquals(0, average.average())
    }

    @Test fun lowAndHighReadingsAreKeptWithoutClamping() {
        val average = RollingBpmAverage({ 0L })
        average.add(35)
        average.add(145)
        assertEquals(90, average.average())
    }

    @Test fun readingsExpireAfterFifteenSeconds() {
        var now = 0L
        val average = RollingBpmAverage({ now })
        average.add(60)
        now = 10_000L
        average.add(100)
        assertEquals(80, average.average())
        now = 15_000L
        assertEquals(100, average.average())
        now = 25_000L
        assertEquals(0, average.average())
    }

    @Test fun clearDoesNotReusePreDisconnectReadings() {
        val average = RollingBpmAverage({ 0L })
        average.add(145)
        average.clear()
        assertEquals(0, average.average())
        average.add(55)
        assertEquals(55, average.average())
    }
}
