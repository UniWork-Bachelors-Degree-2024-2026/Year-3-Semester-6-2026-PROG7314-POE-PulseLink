/*
 * Process-memory hand-off of the credentials just used for a successful password sign-in, so
 * BiometricSetupActivity can encrypt them after the user confirms with their biometrics.
 *
 * Reference List:
 * - Google Developers, 2024. Show a biometric authentication dialog. [online] Available at: <https://developer.android.com/training/sign-in/biometric-auth> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.security

import android.content.Context

object PendingBiometricEnrollment { // (Google Developers, 2024)

    private var stagedEmail: String? = null
    private var stagedPassword: CharArray? = null

    @Synchronized
    fun stage(email: String, password: String) {
        clear()
        stagedEmail = email.trim()
        stagedPassword = password.toCharArray()
    }

    @Synchronized
    fun isStaged(): Boolean = stagedEmail != null && stagedPassword != null

    @Synchronized
    fun stagedEmail(): String? = stagedEmail

    // Returns a copy of the staged credentials (wipe() it after use), or null if nothing is staged.
    @Synchronized
    fun peek(): BiometricCredentialVault.Credentials? {
        val email = stagedEmail ?: return null
        val password = stagedPassword ?: return null
        return BiometricCredentialVault.Credentials(email, password.copyOf())
    }

    @Synchronized
    fun clear() {
        stagedPassword?.fill('\u0000')
        stagedPassword = null
        stagedEmail = null
    }

    // Decides whether to show the "enable biometrics?" screen after a successful password sign-in.
    fun shouldOfferSetup(context: Context): Boolean {
        val email = stagedEmail() ?: return false

        // A saved sign-in for a different account must never survive: the Login screen would
        // otherwise sign the previous person back in.
        if (BiometricCredentialVault.isEnrolled(context) &&
            !BiometricCredentialVault.enrolledEmail(context).equals(email, ignoreCase = true)
        ) {
            BiometricCredentialVault.clear(context)
        }

        if (BiometricCredentialVault.isEnrolled(context)) return false
        if (BiometricCredentialVault.isSetupDeclined(context, email)) return false
        return BiometricCapability.evaluate(context) == BiometricStatus.READY
    }
}
