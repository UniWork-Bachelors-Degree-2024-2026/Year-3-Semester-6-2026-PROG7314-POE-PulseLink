/*
 * Centralised tactile haptic feedback manager.
 * Provides standardised vibration profiles across UI navigation, button presses,
 * detected cardiac R-peaks in live ECG mode, and critical lead detachment warnings.
 *
 * Reference List:
 * - Android Open Source Project (AOSP), 2024. Vibrator and VibratorManager API Reference. [online] Available at: <https://developer.android.com/reference/android/os/Vibrator> [Accessed 21 September 2026].
 * - Google Developers, 2024. VibrationEffect - Android Developers. [online] Available at: <https://developer.android.com/reference/android/os/VibrationEffect> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect                      // (Google Developers, 2024)
import android.os.Vibrator                             // (Android Open Source Project (AOSP), 2024)
import android.os.VibratorManager                      // (Android Open Source Project (AOSP), 2024)
import java.lang.ref.WeakReference

object HapticFeedbackManager { // (Google Developers, 2024)

    private var appContextRef: WeakReference<Context>? = null
    private var vibrator: Vibrator? = null

    
    // Initialises the haptic feedback manager with the application context.
    fun initialise(context: Context) {
        val appContext = context.applicationContext
        appContextRef = WeakReference(appContext)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // Light tick feedback (10ms @ 20% intensity) for tab transitions, chips, and list selections.
    fun lightTick() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(10L, 50))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(10L)
        }
    }

    // Standard confirmation click (25ms @ 50% intensity) for primary buttons and CTAs.
    fun confirmClick() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(25L, 128))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(25L)
        }
    }

    
    // Subtle micro-pulse (15ms @ 30% intensity) synchronised with detected cardiac R-peaks during live ECG acquisition.
    fun rPeakPulse() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(15L, 75))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(15L)
        }
    }

    // Double heavy buzz alarm pattern for emergency SOS triggers, lead detachment, or sustained BPM anomalies.
    fun warningBuzz() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0L, 80L, 60L, 80L)
            val amplitudes = intArrayOf(0, 230, 0, 230)
            vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            val pattern = longArrayOf(0L, 80L, 60L, 80L)
            @Suppress("DEPRECATION")
            vib.vibrate(pattern, -1)
        }
    }
}
