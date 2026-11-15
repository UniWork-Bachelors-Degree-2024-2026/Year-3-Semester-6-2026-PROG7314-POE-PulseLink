package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.viewmodels.SessionOverview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionOverviewTest {
    private fun session(
        id: String, start: String, released: Boolean, notes: Boolean, report: Boolean
    ) = EcgSessionSummaryDto(
        sessionId = id, startTimeUtc = start,
        resultsReleased = released, hasDoctorNotes = notes, reportAvailable = report
    )

    @Test fun unreleasedNotesAndReportsAreNotCounted() {
        val overview = SessionOverview.from(listOf(
            session("a", "2026-11-01T08:00:00Z", released = true, notes = true, report = true),
            session("b", "2026-11-02T08:00:00Z", released = false, notes = true, report = true),
            session("c", "2026-11-03T08:00:00Z", released = true, notes = false, report = true)
        ))
        assertEquals(3, overview.totalSessions)
        assertEquals(2, overview.completedReports)
        assertEquals(1, overview.releasedNotes)
    }

    @Test fun latestSessionIsTheMostRecentStart() {
        val overview = SessionOverview.from(listOf(
            session("old", "2026-10-01T08:00:00Z", released = true, notes = false, report = false),
            session("new", "2026-11-05T08:00:00Z", released = false, notes = false, report = false)
        ))
        assertEquals("new", overview.latest?.sessionId)
    }

    @Test fun noSessionsGivesZeroCountsAndNoLatest() {
        val overview = SessionOverview.from(emptyList())
        assertNull(overview.latest)
        assertEquals(0, overview.totalSessions)
        assertEquals(0, overview.completedReports)
        assertEquals(0, overview.releasedNotes)
    }
}
