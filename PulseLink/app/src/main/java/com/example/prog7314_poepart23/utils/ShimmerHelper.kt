/*
 * Helper utility for controlling Facebook Shimmer skeleton placeholders
 * and smooth transitions when telemetry and API responses load.
 *
 * Reference List:
 * - Facebook, 2024. Shimmer for Android. [online] Available at: <https://facebook.github.io/shimmer-android/> [Accessed 21 September 2026].
 * - Google Developers, 2024. View - Android Developers. [online] Available at: <https://developer.android.com/reference/android/view/View> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.view.View                            // (Google Developers, 2024)
import com.facebook.shimmer.ShimmerFrameLayout       // (Facebook, 2024)

object ShimmerHelper { // (Facebook, 2024)

    // Displays shimmer skeleton and hides the actual content views during network loading.
    fun startShimmer(shimmerView: ShimmerFrameLayout, vararg contentViews: View) {
        shimmerView.visibility = View.VISIBLE
        shimmerView.startShimmer()
        for (view in contentViews) {
            view.visibility = View.GONE
        }
    }

    // Stops shimmer animation, hides skeleton layout, and reveals loaded content views.
    fun stopShimmer(shimmerView: ShimmerFrameLayout, vararg contentViews: View) {
        shimmerView.stopShimmer()
        shimmerView.visibility = View.GONE
        for (view in contentViews) {
            view.visibility = View.VISIBLE
        }
    }
}
