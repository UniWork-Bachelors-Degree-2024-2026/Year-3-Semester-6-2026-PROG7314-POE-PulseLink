package com.example.prog7314_poepart23.data.repository

import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.*
import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody
import retrofit2.Response

sealed interface PatientPortalResult<out T> {
    data class Ready<T>(val value: T) : PatientPortalResult<T>
    data object NotLinked : PatientPortalResult<Nothing>
    data object NotReleased : PatientPortalResult<Nothing>
    data class Failed(val reason: Reason) : PatientPortalResult<Nothing>
    enum class Reason { INVALID_INPUT, SIGN_IN_REQUIRED, DENIED, CONFLICT, UNAVAILABLE, INVALID_RECEIPT }
}

// Results remain in the caller's state, with no shared patient cache or persisted access grant.
class PatientPortalRepository(
    private val api: PulseLinkApiService = PulseLinkApiClient.apiService,
    private val refreshToken: suspend () -> String? = { UserRepository().refreshAuthToken() },
    private val currentUid: () -> String? = { UserRepository().currentUser?.uid }
) {
    companion object {
        private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // "%PDF-"

        fun isValidPdfPayload(bytes: ByteArray?): Boolean {
            if (bytes == null || bytes.size < PDF_MAGIC.size) return false
            for (i in PDF_MAGIC.indices) {
                if (bytes[i] != PDF_MAGIC[i]) return false
            }
            return true
        }
    }

    private fun valid(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    private suspend fun <T> call(request: suspend () -> Response<T>): PatientPortalResult<T> {
        val uid = currentUid() ?: return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
        return try {
            if (refreshToken().isNullOrBlank() || currentUid() != uid)
                return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            val response = request()
            if (currentUid() != uid) return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            when {
                response.code() == 204 -> PatientPortalResult.NotLinked
                response.isSuccessful && response.body() != null -> PatientPortalResult.Ready(response.body()!!)
                response.code() == 404 -> PatientPortalResult.NotReleased
                else -> PatientPortalResult.Failed(when (response.code()) {
                    400 -> PatientPortalResult.Reason.INVALID_INPUT
                    401 -> PatientPortalResult.Reason.SIGN_IN_REQUIRED
                    403 -> PatientPortalResult.Reason.DENIED
                    409 -> PatientPortalResult.Reason.CONFLICT
                    else -> PatientPortalResult.Reason.UNAVAILABLE
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PatientPortalResult.Failed(PatientPortalResult.Reason.UNAVAILABLE) }
    }

    private suspend fun downloadPdf(
        sessionId: String,
        defaultFileName: String,
        request: suspend () -> Response<ResponseBody>
    ): PatientPortalResult<ReportDocumentPayload> {
        if (!valid(sessionId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val uid = currentUid() ?: return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
        return try {
            if (refreshToken().isNullOrBlank() || currentUid() != uid)
                return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            val response = request()
            if (currentUid() != uid) return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            when {
                response.code() == 204 -> PatientPortalResult.NotLinked
                response.code() == 404 -> PatientPortalResult.NotReleased
                response.isSuccessful && response.body() != null -> {
                    val bytes = response.body()!!.use { it.bytes() }
                    if (currentUid() != uid) {
                        return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
                    }
                    if (!isValidPdfPayload(bytes)) {
                        return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT)
                    }
                    PatientPortalResult.Ready(
                        ReportDocumentPayload(
                            sessionId = sessionId,
                            fileName = defaultFileName,
                            pdfBytes = bytes
                        )
                    )
                }
                else -> PatientPortalResult.Failed(when (response.code()) {
                    400 -> PatientPortalResult.Reason.INVALID_INPUT
                    401 -> PatientPortalResult.Reason.SIGN_IN_REQUIRED
                    403 -> PatientPortalResult.Reason.DENIED
                    409 -> PatientPortalResult.Reason.CONFLICT
                    else -> PatientPortalResult.Reason.UNAVAILABLE
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PatientPortalResult.Failed(PatientPortalResult.Reason.UNAVAILABLE) }
    }

    suspend fun record(): PatientPortalResult<PatientProfileDto> = call { api.getOwnPatientRecord() }

    suspend fun sessions(after: String? = null): PatientPortalResult<AccessPageDto<PatientSessionStatusDto>> {
        if (after != null && !valid(after)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        return call { api.getOwnPatientSessions(after) }
    }

    suspend fun results(sessionId: String): PatientPortalResult<PatientReleaseDto> {
        if (!valid(sessionId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.getReleasedPatientResults(sessionId) }
        return if (result is PatientPortalResult.Ready && result.value.sessionId != sessionId)
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT) else result
    }

    suspend fun reportStatus(sessionId: String): PatientPortalResult<ReportStatusDto> {
        if (!valid(sessionId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.getReleasedPatientReportStatus(sessionId) }
        return when {
            result is PatientPortalResult.Ready && result.value.sessionId != sessionId ->
                PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT)
            result is PatientPortalResult.Ready &&
                result.value.state.equals("Unavailable", ignoreCase = true) &&
                !result.value.reportAvailable ->
                PatientPortalResult.NotReleased
            else -> result
        }
    }

    suspend fun downloadReleasedReportPdf(sessionId: String): PatientPortalResult<ReportDocumentPayload> =
        downloadPdf(sessionId, "PulseLink_Released_Report_$sessionId.pdf") {
            api.downloadReleasedPatientReportPdf(sessionId)
        }

    suspend fun staffReportStatus(sessionId: String, generateIfMissing: Boolean = true): PatientPortalResult<ReportStatusDto> {
        if (!valid(sessionId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.getSessionReportStatus(sessionId, generateIfMissing) }
        return if (result is PatientPortalResult.Ready && result.value.sessionId != sessionId)
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT) else result
    }

    suspend fun downloadStaffReportPdf(sessionId: String, forceRegenerate: Boolean = false): PatientPortalResult<ReportDocumentPayload> =
        downloadPdf(sessionId, "PulseLink_Report_$sessionId.pdf") {
            api.downloadSessionReportPdf(sessionId, forceRegenerate)
        }

    suspend fun link(patientId: String, request: PatientLoginRequestDto): PatientPortalResult<PatientLoginLinkDto> {
        if (!valid(patientId) || !valid(request.accountUid) || request.expectedVersion < 1 || request.confirmedEmail.isBlank())
            return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.linkPatientLogin(patientId, request) }
        return if (result is PatientPortalResult.Ready &&
            (result.value.patientId != patientId || result.value.accountUid != request.accountUid))
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT) else result
    }

    suspend fun release(sessionId: String): PatientPortalResult<PatientReleaseDto> {
        if (!valid(sessionId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.releasePatientResults(sessionId) }
        return if (result is PatientPortalResult.Ready && result.value.sessionId != sessionId)
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT) else result
    }
}
