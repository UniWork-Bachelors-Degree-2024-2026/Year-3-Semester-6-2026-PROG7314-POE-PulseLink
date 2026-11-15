package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.data.models.CarePlan
import com.example.prog7314_poepart23.data.models.MedicationItem
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.*
import com.example.prog7314_poepart23.data.repository.DoctorChartRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class DoctorChartRepositoryTest {
    private fun api(answer: (String) -> Any?): PulseLinkApiService = Proxy.newProxyInstance(
        PulseLinkApiService::class.java.classLoader,
        arrayOf(PulseLinkApiService::class.java)
    ) { _, method, _ -> answer(method.name) } as PulseLinkApiService

    private fun repository(answer: (String) -> Any?) =
        DoctorChartRepository(api(answer), { "doctor-token" }, { "doc1" })

    @Test
    fun dashboard_and_assigned_patients_refresh_token_and_return_typed_models() = runBlocking {
        var refreshed = false
        val dashboardDto = DoctorDashboardDto(
            assignedPatientsCount = 3,
            completedSessionsCount = 2,
            awaitingReviewCount = 1,
            draftAssessedCount = 1,
            pendingReleaseCount = 2,
            releasedCount = 0,
            completedReportsReadyCount = 2
        )
        val repo = DoctorChartRepository(
            api { name ->
                assertTrue(refreshed)
                assertEquals("getDoctorDashboard", name)
                Response.success(dashboardDto)
            },
            { refreshed = true; "doctor-token" },
            { "doc1" }
        )
        assertEquals(PatientPortalResult.Ready(dashboardDto), repo.dashboard())
    }

    @Test
    fun invalid_identifiers_or_empty_assessments_fail_before_network_call() = runBlocking {
        val repo = repository { error("Network must not be called for invalid input") }
        val invalid = PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)

        assertEquals(invalid, repo.assignedPatients(after = "bad/cursor"))
        assertEquals(invalid, repo.assignedPatients(limit = 0))
        assertEquals(invalid, repo.patientChart("bad/patient"))
        assertEquals(invalid, repo.patientVisits("bad/patient"))
        assertEquals(invalid, repo.sessionReview("bad/session"))
        assertEquals(invalid, repo.releaseSession("bad/session"))
        assertEquals(
            invalid,
            repo.createVisit("PAT-1", CreateClinicalVisitDto(assessment = "   "))
        )
        assertEquals(
            invalid,
            repo.saveAssessmentNote(
                CreateDoctorNoteDto(
                    sessionId = "SESSION-1",
                    patientId = "PAT-1",
                    clinicalImpression = "   "
                )
            )
        )
    }

    @Test
    fun mismatched_patient_or_session_receipts_are_rejected() = runBlocking {
        val wrongChart = repository {
            Response.success(
                PatientChartDto(patient = DoctorPatientSummaryDto(patientId = "PAT-OTHER"))
            )
        }
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT),
            wrongChart.patientChart("PAT-1")
        )

        val wrongReview = repository {
            Response.success(
                DoctorSessionReviewDto(session = EcgSessionSummaryDto(sessionId = "SESSION-OTHER"))
            )
        }
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT),
            wrongReview.sessionReview("SESSION-1")
        )

        val wrongRelease = repository {
            Response.success(
                PatientReleaseDto("SESSION-OTHER", "PAT-1", "doc1", "2026-11-06T12:00:00Z", emptyList(), CarePlan())
            )
        }
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT),
            wrongRelease.releaseSession("SESSION-1")
        )
    }

    @Test
    fun account_switch_or_forbidden_status_is_mapped_safely() = runBlocking {
        var uid: String? = "doc1"
        val switchedRepo = DoctorChartRepository(
            api {
                uid = "admin"
                Response.success(DoctorDashboardDto(assignedPatientsCount = 5))
            },
            { "doctor-token" },
            { uid }
        )
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED),
            switchedRepo.dashboard()
        )

        val forbiddenRepo = repository {
            Response.error<DoctorDashboardDto>(403, "".toResponseBody())
        }
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.DENIED),
            forbiddenRepo.dashboard()
        )

        val incompleteSessionRepo = repository {
            Response.error<DoctorSessionReviewDto>(409, "".toResponseBody())
        }
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.CONFLICT),
            incompleteSessionRepo.sessionReview("SESSION-ACTIVE")
        )
    }

    @Test
    fun assessment_notes_support_prescriptions_and_follow_up_fields() = runBlocking {
        val rx = listOf(MedicationItem(name = "Atenolol", dosage = "50mg", frequency = "Once daily"))
        val returnedNote = DoctorNoteDto(
            noteId = "NOTE-1",
            sessionId = "SESSION-1",
            patientId = "PAT-1",
            doctorId = "doc1",
            doctorName = "Test doctor",
            clinicalImpression = "Regular rhythm on single-lead trace",
            recommendations = "Continue beta-blocker",
            followUpRequired = true,
            followUpInstructions = "Recheck in 14 days",
            followUpInDays = 14,
            prescriptions = rx,
            createdAtUtc = "2026-11-06T12:00:00Z"
        )
        val repo = repository { Response.success(returnedNote) }
        val result = repo.saveAssessmentNote(
            CreateDoctorNoteDto(
                sessionId = "SESSION-1",
                patientId = "PAT-1",
                clinicalImpression = "Regular rhythm on single-lead trace",
                recommendations = "Continue beta-blocker",
                followUpRequired = true,
                followUpInstructions = "Recheck in 14 days",
                followUpInDays = 14,
                prescriptions = rx
            )
        )
        assertEquals(PatientPortalResult.Ready(returnedNote), result)
    }
}
