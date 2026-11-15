package com.example.prog7314_poepart23.utils

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.R

/**
 * Heart rate status that never depends on colour alone (colour-blind friendly requirement).
 *
 * Every BPM reading is shown with a text label and an icon with a different shape:
 *   Normal - check mark, within the expected range
 *   High   - up arrow, above the configured range
 *   Low    - down arrow, below the configured range
 *
 * The range is the one the app already uses for alerts in [BpmAnomalyDetector]
 * (below THRESHOLD_BRADYCARDIA_WARNING is Low, above THRESHOLD_TACHYCARDIA_WARNING is High).
 * The icon takes the TextView's own text colour, so dark mode, high contrast and the
 * colour-blind palette keep working and no layout has to change.
 */
object HeartRateStatus {

    enum class Level(@StringRes val label: Int, @DrawableRes val icon: Int) {
        NORMAL(R.string.hr_status_normal, R.drawable.ic_hr_normal),
        HIGH(R.string.hr_status_high, R.drawable.ic_hr_high),
        LOW(R.string.hr_status_low, R.drawable.ic_hr_low)
    }

    /** Returns null when there is no real reading (0 or less), because nothing is ever invented. */
    fun levelFor(bpm: Int?): Level? = when {
        bpm == null || bpm <= 0 -> null
        bpm < BpmAnomalyDetector.THRESHOLD_BRADYCARDIA_WARNING -> Level.LOW
        bpm > BpmAnomalyDetector.THRESHOLD_TACHYCARDIA_WARNING -> Level.HIGH
        else -> Level.NORMAL
    }

    /**
     * Shows [baseText] followed by the status label, with the status icon before it.
     * With no reading the plain [baseText] is shown and any icon is removed.
     */
    fun apply(view: TextView, bpm: Int?, baseText: String) {
        val level = levelFor(bpm)
        if (level == null) {
            view.text = baseText
            view.contentDescription = null
            view.setCompoundDrawablesRelative(null, null, null, null)
            return
        }
        val context = view.context
        val label = context.getString(level.label)
        view.text = context.getString(R.string.hr_status_with_value, baseText, label)
        view.contentDescription = view.text

        val icon = ContextCompat.getDrawable(context, level.icon)?.mutate()
        if (icon != null) {
            // Size follows the text so the adjustable text size setting scales the icon too.
            val size = (view.textSize * 1.1f).toInt().coerceAtLeast(1)
            icon.setBounds(0, 0, size, size)
            icon.setTintList(ColorStateList.valueOf(view.currentTextColor))
            view.setCompoundDrawablesRelative(icon, null, null, null)
            view.compoundDrawablePadding = (view.textSize * 0.35f).toInt()
        }
    }
}
