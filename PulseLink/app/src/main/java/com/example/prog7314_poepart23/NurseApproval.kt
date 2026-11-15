/*
 * Pure presentation logic for the nurse approval and status section of SiteAccessActivity.
 * It holds no Android UI code and does no BLE transport. Device and upload data is supplied
 * by the data layer (see NurseStatusSource), never inferred by the screen.
 *
 * Reference List:
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 6 November 2026].
 */

package com.example.prog7314_poepart23

import android.content.Context
import com.example.prog7314_poepart23.data.local.OfflineEcgDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Every result the nurse can see after pressing Approve. Separate from the BLE pairing code. */
enum class ApprovalOutcome {
    SUCCESS, WRONG_CODE, EXPIRED, DENIED, DEVICE_NOT_FOUND,
    SIGN_IN_REQUIRED, TOO_MANY_ATTEMPTS, OFFLINE, UNAVAILABLE
}

enum class AuthorisationState { NONE, ACTIVE, EXPIRED }

/** Counts supplied by the device service. Local only is never presented as uploaded. */
data class NurseSyncSummary(
    val local: Int,
    val synchronising: Int,
    val uploaded: Int,
    val updatedAtMillis: Long?
)

/** Integration point for Cameron Tasks 1 and 5. The default supplies nothing. */
interface NurseStatusSource {
    suspend fun load(deviceId: String?): NurseSyncSummary?

    companion object {
        val None: NurseStatusSource = object : NurseStatusSource {
            override suspend fun load(deviceId: String?): NurseSyncSummary? = null
        }

        fun fromOfflineDatabase(context: Context): NurseStatusSource {
            val appContext = context.applicationContext
            return object : NurseStatusSource {
                override suspend fun load(deviceId: String?): NurseSyncSummary? = withContext(Dispatchers.IO) {
                    OfflineEcgDatabase.getInstance(appContext).getDeviceSyncSummary(deviceId)
                }
            }
        }
    }
}

object NurseApproval {
    /** Maps the HTTP status and any problem text from the API to a nurse-facing outcome. */
    fun classify(httpCode: Int, problemText: String?): ApprovalOutcome {
        val text = problemText.orEmpty().lowercase(Locale.ROOT)
        return when (httpCode) {
            401 -> ApprovalOutcome.SIGN_IN_REQUIRED
            403 -> ApprovalOutcome.DENIED
            404 -> ApprovalOutcome.DEVICE_NOT_FOUND
            410 -> ApprovalOutcome.EXPIRED
            429 -> ApprovalOutcome.TOO_MANY_ATTEMPTS
            400, 409, 422 -> when {
                "expire" in text -> ApprovalOutcome.EXPIRED
                "denied" in text || "not assigned" in text ||
                    "different site" in text || "wrong site" in text -> ApprovalOutcome.DENIED
                else -> ApprovalOutcome.WRONG_CODE
            }
            else -> ApprovalOutcome.UNAVAILABLE
        }
    }

    /** Parses an ISO 8601 UTC timestamp such as 2026-11-06T08:00:00Z. Returns null if unusable. */
    fun parseUtcMillis(value: String?): Long? {
        if (value.isNullOrBlank() || value.length < 19) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC"); isLenient = false
            }
            format.parse(value.take(19))?.time
        } catch (e: Exception) { null }
    }

    fun authorisationState(expiresAtMillis: Long?, nowMillis: Long): AuthorisationState = when {
        expiresAtMillis == null -> AuthorisationState.NONE
        expiresAtMillis > nowMillis -> AuthorisationState.ACTIVE
        else -> AuthorisationState.EXPIRED
    }

    /**
     * The approval code form is shown only when no approval is active (none yet, or expired), or when
     * the nurse has chosen to approve a different screen. An active approval found by the automatic
     * check needs no code.
     */
    fun needsCode(state: AuthorisationState, approveAnother: Boolean): Boolean =
        approveAnother || state != AuthorisationState.ACTIVE
}
