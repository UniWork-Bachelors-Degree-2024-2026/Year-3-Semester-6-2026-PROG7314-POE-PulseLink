package com.example.prog7314_poepart23.data.repository

import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.models.NurseGrantDto
import com.example.prog7314_poepart23.data.remote.models.ScreenApprovalRequestDto
import kotlinx.coroutines.CancellationException
import retrofit2.Response

sealed interface NurseScreenApproval {
    data object NotActive : NurseScreenApproval
    data class Approved(val grant: NurseGrantDto) : NurseScreenApproval
    data class Failed(val reason: Reason) : NurseScreenApproval
    enum class Reason { INVALID_DEVICE, INVALID_CODE, SIGN_IN_REQUIRED, DENIED, EXPIRED, CONFLICT, UNAVAILABLE, INVALID_RECEIPT }
}

// Screen code approval is independent of BLE pairing. Neither code enters diagnostics or storage.
class NurseScreenApprovalRepository(
    private val refreshToken: suspend () -> String? = { UserRepository().refreshAuthToken() },
    private val request: suspend (String, ScreenApprovalRequestDto) -> Response<NurseGrantDto> = { id, body ->
        PulseLinkApiClient.apiService.approveNurseScreen(id, body)
    },
    private val currentRequest: suspend (String) -> Response<NurseGrantDto> = { id ->
        PulseLinkApiClient.apiService.getNurseScreenAuthorisation(id)
    },
    private val currentUid: () -> String? = {
        try { UserRepository().currentUser?.uid } catch (_: Exception) { null }
    }
) {
    private fun activeAccountMatches(expectedNurseUid: String): Boolean {
        val uid = currentUid()
        return uid == null || uid == expectedNurseUid
    }

    suspend fun current(deviceId: String, siteId: String, nurseUid: String): NurseScreenApproval {
        val id = deviceId.trim()
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return NurseScreenApproval.Failed(NurseScreenApproval.Reason.INVALID_DEVICE)
        if (siteId.isBlank() || nurseUid.isBlank()) return NurseScreenApproval.Failed(NurseScreenApproval.Reason.DENIED)
        if (!activeAccountMatches(nurseUid)) return NurseScreenApproval.Failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED)
        return try {
            if (refreshToken().isNullOrBlank() || !activeAccountMatches(nurseUid)) {
                return NurseScreenApproval.Failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED)
            }
            val response = currentRequest(id)
            if (!activeAccountMatches(nurseUid)) {
                return NurseScreenApproval.Failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED)
            }
            if (response.code() == 204) NurseScreenApproval.NotActive
            else if (response.isSuccessful) receipt(response.body(), id, siteId, nurseUid)
            else NurseScreenApproval.Failed(if (response.code() == 401) NurseScreenApproval.Reason.SIGN_IN_REQUIRED
                else if (response.code() == 403) NurseScreenApproval.Reason.DENIED else NurseScreenApproval.Reason.UNAVAILABLE)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { NurseScreenApproval.Failed(NurseScreenApproval.Reason.UNAVAILABLE) }
    }

    private fun receipt(grant: NurseGrantDto?, id: String, siteId: String, nurseUid: String): NurseScreenApproval =
        if (grant == null || grant.id.isBlank() || grant.deviceId != id || grant.siteId != siteId || grant.nurseUid != nurseUid)
            NurseScreenApproval.Failed(NurseScreenApproval.Reason.INVALID_RECEIPT)
        else NurseScreenApproval.Approved(grant)

    suspend fun approve(deviceId: String, code: String, siteId: String, nurseUid: String): NurseScreenApproval {
        val id = deviceId.trim()
        val digits = code.trim()
        fun failed(reason: NurseScreenApproval.Reason) = NurseScreenApproval.Failed(reason)
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return failed(NurseScreenApproval.Reason.INVALID_DEVICE)
        if (!digits.matches(Regex("[0-9]{6}"))) return failed(NurseScreenApproval.Reason.INVALID_CODE)
        if (siteId.isBlank() || nurseUid.isBlank()) return failed(NurseScreenApproval.Reason.DENIED)
        if (!activeAccountMatches(nurseUid)) return failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED)
        return try {
            if (refreshToken().isNullOrBlank() || !activeAccountMatches(nurseUid)) {
                return failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED)
            }
            val response = request(id, ScreenApprovalRequestDto(digits))
            if (!activeAccountMatches(nurseUid)) {
                return failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED)
            }
            if (response.isSuccessful) {
                receipt(response.body(), id, siteId, nurseUid)
            } else failed(when (response.code()) {
                400 -> NurseScreenApproval.Reason.INVALID_CODE
                401 -> NurseScreenApproval.Reason.SIGN_IN_REQUIRED
                403 -> NurseScreenApproval.Reason.DENIED
                409 -> NurseScreenApproval.Reason.CONFLICT
                410 -> NurseScreenApproval.Reason.EXPIRED
                else -> NurseScreenApproval.Reason.UNAVAILABLE
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed(NurseScreenApproval.Reason.UNAVAILABLE)
        }
    }
}
