package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.utils.SessionStatusLabels
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatusLabelsTest {
    @Test fun lifecycle_labels_are_explicit_and_localisable() {
        mapOf("Completed" to R.string.l10n_status_completed, "Active" to R.string.l10n_status_active,
            "Flagged" to R.string.l10n_status_flagged, "Paused" to R.string.ui_status_paused,
            "Finalising" to R.string.ui_status_finalising, "Discarded" to R.string.ui_status_discarded).forEach { (value, id) ->
            assertEquals(id, SessionStatusLabels.resource(value))
        }
    }
    @Test fun legacy_diagnostic_or_unrecognised_values_do_not_become_a_rhythm_conclusion() {
        listOf(null, "", "Normal", "Irregular", "Normal Rhythm", "Tachycardia", "Bradycardia", "Good", "Poor", "unrecognised")
            .forEach { assertEquals(R.string.l10n_unknown, SessionStatusLabels.resource(it)) }
    }
    @Test fun whitespace_and_case_do_not_break_status_labels() {
        assertEquals(R.string.ui_status_finalising, SessionStatusLabels.resource(" FINALISING "))
    }
}
