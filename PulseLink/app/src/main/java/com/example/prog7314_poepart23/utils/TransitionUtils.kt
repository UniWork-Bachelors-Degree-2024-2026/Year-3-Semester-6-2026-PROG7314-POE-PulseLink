/**
 * PulseLink Material Motion & Transition Utilities
 *
 * Implements Material 3 Shared Axis (X, Y, Z) and Fade Through activity transitions (Google Developers, 2024).
 *
 * Reference List:
 * - Google Developers, 2024. Material Design Transitions: Shared Axis and Fade Through. Android Developers. Available at: <https://developer.android.com/develop/ui/views/animations/transitions> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.os.Build
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.transition.platform.MaterialFadeThrough
import com.google.android.material.transition.platform.MaterialSharedAxis

enum class TransitionAxis {
    X, Y, Z
}

fun AppCompatActivity.enableFadeThroughTransition() {
    window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
    val enterTransition = MaterialFadeThrough().apply {
        duration = 220L
    }
    val exitTransition = MaterialFadeThrough().apply {
        duration = 220L
    }
    window.enterTransition = enterTransition
    window.exitTransition = exitTransition
    window.reenterTransition = enterTransition
    window.returnTransition = exitTransition
}

fun AppCompatActivity.enableSharedAxisTransition(axis: TransitionAxis = TransitionAxis.X, forward: Boolean = true) {
    window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
    val materialAxis = when (axis) {
        TransitionAxis.X -> MaterialSharedAxis.X
        TransitionAxis.Y -> MaterialSharedAxis.Y
        TransitionAxis.Z -> MaterialSharedAxis.Z
    }
    window.enterTransition = MaterialSharedAxis(materialAxis, forward).apply { duration = 280L }
    window.exitTransition = MaterialSharedAxis(materialAxis, forward).apply { duration = 280L }
    window.reenterTransition = MaterialSharedAxis(materialAxis, !forward).apply { duration = 280L }
    window.returnTransition = MaterialSharedAxis(materialAxis, !forward).apply { duration = 280L }
}

fun Activity.applyFadeThroughPendingTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
