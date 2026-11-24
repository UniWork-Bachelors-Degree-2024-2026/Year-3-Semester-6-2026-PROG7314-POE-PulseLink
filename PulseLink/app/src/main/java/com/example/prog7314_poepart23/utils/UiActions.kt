/**
 * PulseLink UI Interaction & Action Dispatch Utilities
 *
 * Provides centralised click handling, haptic dispatch, intent routing, and
 * Firebase session invalidation (Google Cloud, 2024; Google Developers, 2024).
 *
 * Reference List:
 * - Google Cloud, 2024. Manage Users in Firebase: Sign Out. Google Cloud. Available at: <https://firebase.google.com/docs/auth/android/manage-users> [Accessed 18 September 2026].
 * - Google Developers, 2024. Input events overview: Handling click events and accessibility. Android Developers. Available at: <https://developer.android.com/guide/topics/ui/ui-events> [Accessed 18 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.example.prog7314_poepart23.AboutActivity
import com.example.prog7314_poepart23.AppearanceActivity
import com.example.prog7314_poepart23.HelpSupportActivity
import com.example.prog7314_poepart23.LanguageSelectionActivity
import com.example.prog7314_poepart23.LoginActivity
import com.example.prog7314_poepart23.NotificationsActivity
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.SecurityPasswordActivity
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.security.BiometricCredentialVault
import com.example.prog7314_poepart23.security.TokenManager
import com.google.firebase.auth.FirebaseAuth

/** Wires [id] if the view exists in the current layout; returns it so callers can chain. */
fun Activity.onClick(@IdRes id: Int, action: (View) -> Unit): View? {
    val view = findViewById<View>(id) ?: return null
    view.isClickable = true
    view.isFocusable = true
    view.setOnClickListener {
        HapticFeedbackManager.lightTick()
        action(it)
    }
    return view
}

/** Wires one or more "back" / "close" controls to finish the activity. */
fun Activity.wireBack(@IdRes vararg ids: Int) {
    ids.forEach { id -> onClick(id) { finish() } }
}

/** Wires [id] to open [target]. */
fun Activity.openOnClick(@IdRes id: Int, target: Class<out Activity>, vararg extras: Pair<String, String>) {
    onClick(id) {
        val intent = Intent(this, target)
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        startActivity(intent)
    }
}

/** Wires [id] to a short confirmation toast, used for actions that have no backend yet. */
fun Activity.toastOnClick(@IdRes id: Int, @StringRes message: Int) {
    onClick(id) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
}

/** Wires [id] to the standard "this part of PulseLink is still being built" message. */
fun Activity.comingSoonOnClick(@IdRes vararg ids: Int) {
    ids.forEach { toastOnClick(it, R.string.action_not_available_yet) }
}

/**
 * Loads [uid]'s saved profile photo into the avatar ImageView at [id], if one has been set via
 * AvatarStore. Leaves the default person-icon placeholder untouched when no photo is stored yet,
 * so every top-nav avatar and the profile screen itself can share this one call.
 */
fun Activity.applyStoredAvatar(@IdRes id: Int, uid: String?) {
    if (uid == null) return
    val avatarView = findViewById<android.widget.ImageView>(id) ?: return
    val uri = AvatarStore.get(this, uid) ?: return
    avatarView.setPadding(0, 0, 0, 0)
    avatarView.clipToOutline = true
    avatarView.setImageURI(null) // force a refresh even if the Uri string is unchanged
    avatarView.setImageURI(uri)
}

/**
 * Ends the Firebase session, clears the cached role, saved biometric sign-in, staged PDF reports,
 * notifications, and offline cache, and returns to the login screen.
 */
fun Activity.signOutToLogin() {
    runCatching { FirebaseAuth.getInstance().signOut() }
    SessionManager.clear(this)
    runCatching { BiometricCredentialVault.clear(this) }
    runCatching { TokenManager.clearToken(this) }
    runCatching { NotificationRepository.clear() }
    runCatching { ClinicalPdfReportGenerator.clearCachedReports(this) }
    runCatching { OfflineEcgDatabase.getInstance(this).onUserSignedOut() }
    Toast.makeText(this, R.string.action_signed_out, Toast.LENGTH_SHORT).show()
    val intent = Intent(this, LoginActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
    startActivity(intent)
    finish()
}

/** Shared wiring for Settings preferences common to Patient, Doctor and Nurse settings screens. */
fun Activity.wireCommonSettingsRows(
    personalInfoTarget: Class<out Activity>? = null,
    onPersonalInfoClick: (() -> Unit)? = null
) {
    wireBack(R.id.btnBack)
    if (onPersonalInfoClick != null) {
        onClick(R.id.rowPersonalInfo) { onPersonalInfoClick() }
    } else if (personalInfoTarget != null) {
        openOnClick(R.id.rowPersonalInfo, personalInfoTarget)
    }
    openOnClick(R.id.rowSecurity, SecurityPasswordActivity::class.java)
    openOnClick(R.id.rowNotifications, NotificationsActivity::class.java)
    openOnClick(R.id.rowAppearance, AppearanceActivity::class.java)
    openOnClick(R.id.rowLanguage, LanguageSelectionActivity::class.java)
    openOnClick(R.id.rowPrivacyPolicy, AboutActivity::class.java)
    openOnClick(R.id.rowHelpCenter, HelpSupportActivity::class.java)
    openOnClick(R.id.rowAboutLegal, AboutActivity::class.java)
}
