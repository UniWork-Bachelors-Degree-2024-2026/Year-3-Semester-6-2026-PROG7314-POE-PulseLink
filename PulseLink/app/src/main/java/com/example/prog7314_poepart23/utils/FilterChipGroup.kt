/**
 * PulseLink Filter Chip Group Manager
 *
 * Implements single-choice selection states and dynamic style mutations for Material
 * filter chips across diagnostic feeds (Google Developers, 2024).
 *
 * Reference List:
 * - Google Developers, 2024. Chips: Material Design components. Android Developers. Available at: <https://m3.material.io/components/chips/overview> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.graphics.drawable.Drawable
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.content.ContextCompat
import com.example.prog7314_poepart23.R

class FilterChipGroup private constructor(
    private val chips: List<TextView>,
    private val restingBackground: List<Drawable?>,
    private val restingTextColour: List<Int>,
    private val selectedBackground: Drawable?,
    private val selectedTextColour: Int,
    private val onSelected: (Int) -> Unit
) {

    var selectedIndex: Int = 0
        private set

    fun select(index: Int, notify: Boolean = true) {
        if (index !in chips.indices) return
        selectedIndex = index
        chips.forEachIndexed { i, chip ->
            val active = i == index
            chip.background = if (active) selectedBackground?.constantState?.newDrawable()?.mutate()
            else restingBackground[i]
            chip.setTextColor(if (active) selectedTextColour else restingTextColour[i])
            chip.isSelected = active
        }
        if (notify) onSelected(index)
    }

    companion object {
        /**
         * Binds the chips identified by [ids], in order. [onSelected] fires with the index of
         * the chip the user tapped; it is not called for the initial state.
         */
        @JvmStatic
        fun bind(activity: Activity, @IdRes vararg ids: Int, onSelected: (Int) -> Unit): FilterChipGroup? {
            // `ids` is a primitive IntArray (it's a vararg Int), and Kotlin's stdlib does not
            // generate mapNotNull for primitive arrays — only for Iterable<T> and Array<T>.
            // Converting to a List first gives an unambiguous, always-resolvable call.
            val idList = ids.toList()
            val chips = idList.mapNotNull { activity.findViewById<TextView>(it) }
            if (chips.size != idList.size) return null

            // The layout ships the first chip pre-highlighted, so its resting look has to be
            // taken from the neutral chip style rather than from the view itself.
            val neutralBackground = ContextCompat.getDrawable(activity, R.drawable.bg_chip_unselected)
            val neutralTextColour = ContextCompat.getColor(activity, R.color.pulse_text_secondary)

            val group = FilterChipGroup(
                chips = chips,
                restingBackground = chips.mapIndexed { i, chip ->
                    if (i == 0) neutralBackground else chip.background
                },
                restingTextColour = chips.mapIndexed { i, chip ->
                    if (i == 0) neutralTextColour else chip.currentTextColor
                },
                selectedBackground = ContextCompat.getDrawable(activity, R.drawable.bg_chip_selected),
                selectedTextColour = ContextCompat.getColor(activity, R.color.white),
                onSelected = onSelected
            )

            chips.forEachIndexed { index, chip ->
                chip.isClickable = true
                chip.isFocusable = true
                chip.setOnClickListener {
                    HapticFeedbackManager.lightTick()
                    group.select(index)
                }
            }
            group.select(0, notify = false)
            return group
        }
    }
}