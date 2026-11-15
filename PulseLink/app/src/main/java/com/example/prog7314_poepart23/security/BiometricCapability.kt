/*
 * Capability evaluation: a thin, exhaustive wrapper over BiometricManager.canAuthenticate() so the
 * rest of the app can reason about "why not?" without touching raw integer result codes.
 *
 * Reference List:
 * - Google Developers, 2024. Show a biometric authentication dialog. [online] Available at: <https://developer.android.com/training/sign-in/biometric-auth> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.security

import android.content.Context
import androidx.biometric.BiometricManager          // (Google Developers, 2024)

// Capability evaluation wrapper over BiometricManager // (Google Developers, 2024)
enum class BiometricStatus {
    // The requested class of authenticator is present and enrolled.
    READY,

    // Hardware exists but the user has not enrolled a fingerprint/face (or, when device credential
    // is allowed, has no secure lock screen).
    NONE_ENROLLED,

    // No suitable hardware, or the OS/build does not support the requested combination.
    NO_HARDWARE,
    UNSUPPORTED,

    // Temporary (sensor busy, security update pending, unknown state). Worth retrying, not wiping.
    HW_UNAVAILABLE,
    UNKNOWN
}

object BiometricCapability {

    // Enrolment requires a Class 3 (BIOMETRIC_STRONG) sensor, which is what can back a CryptoObject.
    fun evaluate(
        context: Context,
        authenticators: Int = BiometricManager.Authenticators.BIOMETRIC_STRONG
    ): BiometricStatus {
        return when (BiometricManager.from(context).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> BiometricStatus.UNSUPPORTED
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HW_UNAVAILABLE
            else -> BiometricStatus.UNKNOWN
        }
    }
}
