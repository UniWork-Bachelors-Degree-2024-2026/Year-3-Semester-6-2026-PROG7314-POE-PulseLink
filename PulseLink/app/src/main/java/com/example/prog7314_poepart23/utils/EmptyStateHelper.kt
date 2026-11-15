/**
 * PulseLink Empty State View Helper
 *
 * Coordinates dynamic visibility swapping between RecyclerViews and placeholder layouts
 * adhering to Material Design empty-state guidance (Google Developers, 2024).
 *
 * Reference List:
 * - Google Developers, 2024. Empty states: Material Design guidelines. Android Developers. Available at: <https://m3.material.io/foundations/interaction-design> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.view.View
import androidx.recyclerview.widget.RecyclerView

object EmptyStateHelper {

    /**
     * Shows [emptyView] and hides [recyclerView] when [list] is null or empty;
     * otherwise shows [recyclerView] and hides [emptyView].
     *
     * @param recyclerView the list view backed by [list]'s adapter.
     * @param emptyView the empty-state placeholder (an inflated/included
     *   `empty_state_*.xml` root view) shown in place of [recyclerView].
     * @param list the current data collection driving [recyclerView]'s adapter.
     */
    fun toggleEmptyState(recyclerView: RecyclerView, emptyView: View, list: List<*>?) {
        val isEmpty = list.isNullOrEmpty()
        recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyView.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }
}
