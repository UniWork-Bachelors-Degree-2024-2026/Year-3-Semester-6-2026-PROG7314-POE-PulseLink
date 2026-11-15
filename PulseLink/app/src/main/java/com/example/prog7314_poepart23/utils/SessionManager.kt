/*
 * Lightweight cache of the signed-in user's role so cold-start routing (SplashActivity)
 * can pick the correct dashboard instantly and offline. The cache is keyed by Firebase UID
 * so a stale role can never be applied to a different account.
 *
 * Reference List:
 * - Google Developers, 2024. Save simple data with SharedPreferences. [online] Available at: <https://developer.android.com/training/data-storage/shared-preferences> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.utils

import android.content.Context
import com.example.prog7314_poepart23.security.SecurePreferences
import java.util.Locale

object SessionManager { // (Google Developers, 2024)

    const val ROLE_PATIENT = "patient"
    const val ROLE_DOCTOR = "doctor"
    const val ROLE_NURSE = "nurse"
    const val ROLE_ADMINISTRATOR = "administrator"
    const val ROLE_PENDING = "pending"
    const val ROLE_UNAVAILABLE = "unavailable"

    private val APPROVED_ROLES = setOf(ROLE_PATIENT, ROLE_DOCTOR, ROLE_NURSE, ROLE_ADMINISTRATOR)
    private val CACHEABLE_ROLES = APPROVED_ROLES + ROLE_PENDING
    private val CLINICAL_PAT_REGEX = Regex("^PAT-[A-Za-z0-9_-]{1,64}$", RegexOption.IGNORE_CASE)

    private const val PREFS_NAME = "pulselink_session_v2"
    private const val KEY_UID = "uid"
    private const val KEY_ROLE = "role"

    fun normaliseRole(role: String?): String? {
        val clean = role?.trim()?.lowercase(Locale.ROOT) ?: return null
        return clean.takeIf { it in CACHEABLE_ROLES || it == ROLE_UNAVAILABLE }
    }

    fun isApprovedRole(role: String?): Boolean {
        val clean = role?.trim()?.lowercase(Locale.ROOT) ?: return false
        return clean in APPROVED_ROLES
    }

    fun isCacheableRole(role: String?): Boolean {
        val clean = role?.trim()?.lowercase(Locale.ROOT) ?: return false
        return clean in CACHEABLE_ROLES
    }

    // Distinguishes a clinical patient record identifier (PAT-...) from a Firebase Authentication UID.
    fun isClinicalPatientId(id: String?): Boolean {
        val clean = id?.trim() ?: return false
        return CLINICAL_PAT_REGEX.matches(clean)
    }

    // Resolves the server-authoritative role or falls back to the cached role when the server is unreachable ("unavailable").
    fun resolveRoleWithFallback(liveRole: String?, cachedRole: String?): String {
        val normalisedLive = normaliseRole(liveRole)
        if (normalisedLive != null && normalisedLive != ROLE_UNAVAILABLE) {
            return normalisedLive
        }
        val normalisedCached = normaliseRole(cachedRole)
        if (normalisedCached != null && normalisedCached != ROLE_UNAVAILABLE) {
            return normalisedCached
        }
        return ROLE_UNAVAILABLE
    }

    fun saveRole(context: Context, uid: String?, role: String) {
        if (uid.isNullOrBlank()) return
        val normalizedRole = role.trim().lowercase(Locale.ROOT)
        if (!isCacheableRole(normalizedRole)) return

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UID, uid)
            .putString(KEY_ROLE, normalizedRole)
            .apply()

        // Keep hardware-backed SecurePreferences synchronised with active user session
        SecurePreferences.getInstance(context).saveUserRole(normalizedRole)
        SecurePreferences.getInstance(context).saveUserId(uid)
    }

    // Returns the cached role only if it belongs to [uid] and is a valid cacheable role; otherwise null.
    fun getCachedRole(context: Context, uid: String): String? {
        if (uid.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = if (prefs.getString(KEY_UID, null) == uid) prefs.getString(KEY_ROLE, null) else null
        return cached?.takeIf { isCacheableRole(it) }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        SecurePreferences.getInstance(context).saveUserRole(null)
        SecurePreferences.getInstance(context).saveUserId(null)
    }
}
