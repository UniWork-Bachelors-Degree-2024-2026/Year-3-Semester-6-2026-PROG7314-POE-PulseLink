/**
 * PulseLink Activity Transition Helper
 *
 * Configures Material Fade Through window enter transitions for seamless
 * activity transitions (Google Developers, 2024).
 *
 * Reference List:
 * - Google Developers, 2024. Material Design Transitions: Material Fade Through. Android Developers. Available at: <https://developer.android.com/develop/ui/views/animations/transitions> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.transition.platform.MaterialFadeThrough

fun AppCompatActivity.enableFadeThroughEntry() {
    window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
    val fadeThrough = MaterialFadeThrough().apply {
        duration = 220L
    }
    window.enterTransition = fadeThrough
    window.exitTransition = fadeThrough
    window.reenterTransition = fadeThrough
    window.returnTransition = fadeThrough
}
