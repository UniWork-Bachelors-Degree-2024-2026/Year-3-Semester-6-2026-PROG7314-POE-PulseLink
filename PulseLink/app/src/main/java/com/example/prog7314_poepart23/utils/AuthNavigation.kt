/**
 * PulseLink Post-Authentication Navigation Routing Utility
 *
 * Implements role-based dashboard dispatch, biometric onboarding gating, and
 * task back-stack clearing (Google Cloud, 2024; Google Developers, 2024).
 *
 * Reference List:
 * - Google Cloud, 2024. Manage Users in Firebase: Get Started with Firebase Authentication. Google Cloud. Available at: <https://firebase.google.com/docs/auth> [Accessed 18 September 2026].
 * - Google Developers, 2024. Tasks and the back stack: Intent Flags. Android Developers. Available at: <https://developer.android.com/guide/components/activities/tasks-and-back-stack> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.content.Intent
import com.example.prog7314_poepart23.BiometricSetupActivity
import com.example.prog7314_poepart23.DoctorDashboardActivity
import com.example.prog7314_poepart23.PatientDashboardActivity
import com.example.prog7314_poepart23.SiteAccessActivity
import com.example.prog7314_poepart23.security.PendingBiometricEnrollment
import com.google.firebase.auth.FirebaseAuth

fun dashboardFor(role: String?): Class<out Activity> = when (role?.lowercase()) {
    SessionManager.ROLE_DOCTOR -> DoctorDashboardActivity::class.java
    SessionManager.ROLE_PATIENT -> PatientDashboardActivity::class.java
    else -> SiteAccessActivity::class.java
}

// After a successful email/password sign-in or registration: offer biometric setup when it makes
// sense, otherwise go straight to the role's dashboard.
fun Activity.routeAfterPasswordAuth(role: String) {
    val target: Class<out Activity> = if (PendingBiometricEnrollment.shouldOfferSetup(this)) {
        BiometricSetupActivity::class.java
    } else {
        PendingBiometricEnrollment.clear()
        dashboardFor(role)
    }
    startActivity(Intent(this, target))
    finish()
}

// Opens the dashboard for [role] and clears the back stack so Back never returns to an auth screen.
fun Activity.openDashboardFor(role: String?) {
    val intent = Intent(this, dashboardFor(role)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    startActivity(intent)
    finish()
}

fun Activity.openDashboardForCurrentUser() {
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val role = uid?.let { SessionManager.getCachedRole(this, it) }
    openDashboardFor(role)
}
