package com.example.prog7314_poepart23.utils

import androidx.annotation.StringRes
import com.example.prog7314_poepart23.R
import java.util.Locale

// Recording lifecycle values do not describe a patient's cardiac rhythm.
object SessionStatusLabels {
    @StringRes fun resource(raw: String?): Int = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "completed" -> R.string.l10n_status_completed
        "active" -> R.string.l10n_status_active
        "flagged" -> R.string.l10n_status_flagged
        "paused" -> R.string.ui_status_paused
        "finalising" -> R.string.ui_status_finalising
        "discarded" -> R.string.ui_status_discarded
        else -> R.string.l10n_unknown
    }
}
