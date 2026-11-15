package com.example.prog7314_poepart23.utils

/**
 * BPM display helper. 0 means "no reading yet" (nothing is ever invented), so it is shown as "--".
 */
object BpmText {
    const val NONE = "--"

    fun orDash(bpm: Int?): String = if (bpm != null && bpm > 0) bpm.toString() else NONE
}
