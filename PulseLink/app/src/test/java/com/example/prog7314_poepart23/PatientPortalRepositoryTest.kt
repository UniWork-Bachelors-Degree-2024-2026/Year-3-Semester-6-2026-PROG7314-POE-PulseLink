package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.data.models.CarePlan
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.*
import com.example.prog7314_poepart23.data.repository.PatientPortalRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import com.example.prog7314_poepart23.utils.ClinicalPdfReportGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class PatientPortalRepositoryTest {
    private fun api(answer: (String) -> Any?): PulseLinkApiService = Proxy.newProxyInstance(
        PulseLinkApiService::class.java.classLoader, arrayOf(PulseLinkApiService::class.java)
    ) { _, method, _ -> answer(method.name) } as PulseLinkApiService
    private fun repository(answer: (String) -> Any?) = PatientPortalRepository(api(answer), { "test-only" }, { "patient" })
    private fun release(id: String = "session") = PatientReleaseDto(id, "PAT-test", "doctor", "2026-11-06T10:00:00Z", emptyList(), CarePlan())

    @Test fun unlinked_and_unreleased_are_distinct_states() = runBlocking {
        val unlinked = repository { Response.success<PatientProfileDto>(204, null) }
        assertEquals(PatientPortalResult.NotLinked, unlinked.record())
        val unreleased = repository { Response.error<PatientReleaseDto>(404, "".toResponseBody()) }
        assertEquals(PatientPortalResult.NotReleased, unreleased.results("session"))
    }
    @Test fun input_errors_never_send_requests() = runBlocking {
        val repository = repository { error("Must not request") }
        val expected = PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_INPUT)
        assertEquals(expected, repository.results("bad/path"))
        assertEquals(expected, repository.sessions("bad/path"))
        assertEquals(expected, repository.reportStatus("bad/path"))
        assertEquals(expected, repository.downloadReleasedReportPdf("bad/path"))
        assertEquals(expected, repository.link("PAT-test", PatientLoginRequestDto("patient", "", 1)))
    }
    @Test fun authentication_refresh_happens_before_read_and_does_not_store_results() = runBlocking {
        var refreshed = false
        val profile = PatientProfileDto("PAT-test", "Test patient")
        val repository = PatientPortalRepository(api { name ->
            assertTrue(refreshed); assertEquals("getOwnPatientRecord", name); Response.success(profile)
        }, { refreshed = true; "test-only" }, { "patient" })
        assertEquals(PatientPortalResult.Ready(profile), repository.record())
    }
    @Test fun signout_or_account_switch_during_request_discards_the_response() = runBlocking {
        var uid: String? = "patient"
        val repository = PatientPortalRepository(api {
            uid = "otherPatient"; Response.success(PatientProfileDto("PAT-test", "Private name"))
        }, { "test-only" }, { uid })
        assertEquals(PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED), repository.record())
    }
    @Test fun mismatched_link_and_release_receipts_are_rejected() = runBlocking {
        val wrong = repository { Response.success(release("otherSession")) }
        assertEquals(PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT), wrong.results("session"))
        val link = repository { Response.success(PatientLoginLinkDto("PAT-other", "patient", "nurse", "now")) }
        assertEquals(PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT),
            link.link("PAT-test", PatientLoginRequestDto("patient", "patient@example.test", 1)))
    }
    @Test fun network_failure_does_not_become_an_empty_history_or_unreleased_result() = runBlocking {
        val offline = repository { throw java.io.IOException("offline") }
        assertEquals(PatientPortalResult.Failed(PatientPortalResult.Reason.UNAVAILABLE), offline.sessions())
    }
    @Test fun cancellation_propagates() = runBlocking {
        val repository = PatientPortalRepository(api { error("Must not request") }, { throw CancellationException() }, { "patient" })
        try { repository.record(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }
    @Test fun patient_history_defaults_never_claim_report_readiness_or_good_signal() {
        val summary = com.google.gson.Gson().fromJson("""{"sessionId":"session","status":"Completed"}""", EcgSessionSummaryDto::class.java)
        assertFalse(summary.resultsReleased); assertFalse(summary.reportAvailable); assertFalse(summary.hasDoctorNotes)
        assertEquals("Unknown", summary.signalQuality)
    }
    @Test fun report_status_distinguishes_unreleased_generating_failed_and_ready_states() = runBlocking {
        val unreleased = repository {
            Response.success(ReportStatusDto(sessionId = "session", state = "Unavailable", reportAvailable = false))
        }
        assertEquals(PatientPortalResult.NotReleased, unreleased.reportStatus("session"))

        val generating = repository {
            Response.success(ReportStatusDto(sessionId = "session", state = "Generating", reportAvailable = false))
        }
        val genResult = generating.reportStatus("session")
        assertTrue(genResult is PatientPortalResult.Ready && genResult.value.state == "Generating")

        val readyStatus = ReportStatusDto(
            sessionId = "session",
            patientId = "PAT-test",
            audience = "patient",
            state = "Ready",
            reportAvailable = true,
            fileName = "PulseLink_Released_Report_session.pdf",
            downloadPath = "/api/patient/sessions/session/report/pdf",
            streamPath = "/api/patient/sessions/session/report/pdf",
            authenticatedEmailLinkPath = "/api/patient/sessions/session/report"
        )
        val ready = repository { Response.success(readyStatus) }
        assertEquals(PatientPortalResult.Ready(readyStatus), ready.reportStatus("session"))
    }
    @Test fun pdf_download_validates_pdf_header_and_rejects_plain_text_or_account_switch() = runBlocking {
        val validPdfBytes = "%PDF-1.4\n1 0 obj\n%%EOF".toByteArray(Charsets.ISO_8859_1)
        val validRepo = repository { Response.success(validPdfBytes.toResponseBody()) }
        val downloaded = validRepo.downloadReleasedReportPdf("session")
        assertTrue(downloaded is PatientPortalResult.Ready)
        assertEquals("PulseLink_Released_Report_session.pdf", (downloaded as PatientPortalResult.Ready).value.fileName)
        assertArrayEquals(validPdfBytes, downloaded.value.pdfBytes)

        val corruptRepo = repository { Response.success("PulseLink ECG Report fallback text".toByteArray().toResponseBody()) }
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.INVALID_RECEIPT),
            corruptRepo.downloadReleasedReportPdf("session")
        )

        var uid: String? = "patient"
        val switchedRepo = PatientPortalRepository(api {
            uid = "otherPatient"
            Response.success(validPdfBytes.toResponseBody())
        }, { "test-only" }, { uid })
        assertEquals(
            PatientPortalResult.Failed(PatientPortalResult.Reason.SIGN_IN_REQUIRED),
            switchedRepo.downloadReleasedReportPdf("session")
        )

        assertEquals("PulseLink_Report_SESSION-1.pdf", ClinicalPdfReportGenerator.sanitizeReportFileName("SESSION-1", releasedPatientCopy = false))
        assertEquals("PulseLink_Released_Report_SESSION-1.pdf", ClinicalPdfReportGenerator.sanitizeReportFileName("SESSION-1", releasedPatientCopy = true))
    }
}
