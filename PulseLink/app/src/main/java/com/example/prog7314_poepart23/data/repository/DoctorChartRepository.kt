package com.example.prog7314_poepart23.data.repository

import com.example.prog7314_poepart23.data.models.CarePlan
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.*
import kotlinx.coroutines.CancellationException
import retrofit2.Response

// Authoritative clinical chart and doctor review repository with token refresh and account-switch protection.
class DoctorChartRepository(
    private val api: PulseLinkApiService = PulseLinkApiClient.apiService,
    private val refreshToken: suspend () -> String? = { UserRepository().refreshAuthToken() },
    private val currentUid: () -> String? = { UserRepository().currentUser?.uid }
) {
    private fun valid(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))

    private suspend fun <T> call(request: suspend () -> Response<T>): PatientPortalResult<T> {
        val uid = currentUid() ?: return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
        return try {
            if (refreshToken().isNullOrBlank() || currentUid() != uid)
                return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            val response = request()
            if (currentUid() != uid) return PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED)
            when {
                response.isSuccessful && response.body() != null -> PatientPortalResult.Ready(response.body()!!)
                response.code() == 404 -> PatientPortalResult.NotReleased
                else -> PatientPortalResult.Failed(
                    when (response.code()) {
                        400 -> PatientPortalResult.Reason.INVALID_INPUT
                        401 -> PatientPortalResult.Reason.SIGN_IN_REQUIRED
                        403 -> PatientPortalResult.Reason.DENIED
                        409, 410 -> PatientPortalResult.Reason.CONFLICT
                        else -> PatientPortalResult.Reason.UNAVAILABLE
                    }
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PatientPortalResult.Failed(PatientPortalResult.Reason.UNAVAILABLE)
        }
    }

    suspend fun dashboard(): PatientPortalResult<DoctorDashboardDto> =
        call { api.getDoctorDashboard() }

    suspend fun assignedPatients(
        after: String? = null,
        limit: Int = 50
    ): PatientPortalResult<AccessPageDto<DoctorPatientSummaryDto>> {
        if (after != null && !valid(after)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        if (limit !in 1..100) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        return call { api.getDoctorAssignedPatients(after, limit) }
    }

    suspend fun patientChart(patientId: String): PatientPortalResult<PatientChartDto> {
        if (!valid(patientId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.getDoctorPatientChart(patientId) }
        return if (result is PatientPortalResult.Ready && result.value.patient.patientId != patientId)
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT)
        else result
    }

    suspend fun patientVisits(patientId: String): PatientPortalResult<List<ClinicalVisitDto>> {
        if (!valid(patientId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        return call { api.getDoctorPatientVisits(patientId) }
    }

    suspend fun createVisit(
        patientId: String,
        request: CreateClinicalVisitDto
    ): PatientPortalResult<ClinicalVisitDto> {
        if (!valid(patientId) || request.assessment.isBlank() ||
            (request.sessionId != null && !valid(request.sessionId))
        ) {
            return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        }
        val result = call { api.createDoctorPatientVisit(patientId, request) }
        return if (result is PatientPortalResult.Ready && result.value.patientId != patientId)
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT)
        else result
    }

    suspend fun sessionReview(sessionId: String): PatientPortalResult<DoctorSessionReviewDto> {
        if (!valid(sessionId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.getDoctorSessionReview(sessionId) }
        return if (result is PatientPortalResult.Ready && result.value.session.sessionId != sessionId)
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT)
        else result
    }

    suspend fun saveAssessmentNote(request: CreateDoctorNoteDto): PatientPortalResult<DoctorNoteDto> {
        if (!valid(request.sessionId) || !valid(request.patientId) || request.clinicalImpression.isBlank()) {
            return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        }
        val result = call { api.addDoctorNote(request) }
        return if (result is PatientPortalResult.Ready &&
            (result.value.sessionId != request.sessionId || result.value.patientId != request.patientId)
        ) {
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT)
        } else result
    }

    suspend fun saveCarePlan(patientId: String, plan: CarePlan): PatientPortalResult<CarePlan> {
        if (!valid(patientId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        return call { api.saveClinicalCarePlan(patientId, plan) }
    }

    suspend fun releaseSession(sessionId: String): PatientPortalResult<PatientReleaseDto> {
        if (!valid(sessionId)) return PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        val result = call { api.releaseDoctorSessionResults(sessionId) }
        return if (result is PatientPortalResult.Ready && result.value.sessionId != sessionId)
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT)
        else result
    }
}
