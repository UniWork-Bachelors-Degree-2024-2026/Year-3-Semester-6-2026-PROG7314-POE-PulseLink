package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.data.models.NotificationItem
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.AccessAccountDto
import com.example.prog7314_poepart23.data.remote.models.ClinicalPatientDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSamplesResponseDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.remote.models.NurseGrantDto
import com.example.prog7314_poepart23.data.remote.models.PatientProfileDto
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.data.repository.NotificationRepository
import com.example.prog7314_poepart23.data.repository.NurseScreenApproval
import com.example.prog7314_poepart23.data.repository.NurseScreenApprovalRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import com.example.prog7314_poepart23.data.repository.UserRepository
import com.example.prog7314_poepart23.utils.ClinicalPdfReportGenerator
import com.example.prog7314_poepart23.utils.Resource
import com.example.prog7314_poepart23.utils.SessionManager
import com.example.prog7314_poepart23.utils.dashboardFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class KotlinContractsAndAuthTest {

    private fun api(answer: (String, Array<out Any?>?) -> Any?): PulseLinkApiService = Proxy.newProxyInstance(
        PulseLinkApiService::class.java.classLoader,
        arrayOf(PulseLinkApiService::class.java)
    ) { _, method, args -> answer(method.name, args) } as PulseLinkApiService

    @Test
    fun api_client_binds_token_to_uid_and_clears_atomically_on_sign_out() {
        PulseLinkApiClient.clearAuth()
        assertNull(PulseLinkApiClient.getAuthToken())
        assertNull(PulseLinkApiClient.getActiveUid())
        assertFalse(PulseLinkApiClient.isAuthenticatedAs("uid-1"))

        PulseLinkApiClient.setAuthToken("token-alpha", "uid-1")
        assertEquals("token-alpha", PulseLinkApiClient.getAuthToken())
        assertEquals("uid-1", PulseLinkApiClient.getActiveUid())
        assertTrue(PulseLinkApiClient.isAuthenticatedAs("uid-1"))
        assertFalse(PulseLinkApiClient.isAuthenticatedAs("uid-2"))

        NotificationRepository.addNotification(
            NotificationItem(
                notificationId = "n-1",
                title = "Report ready",
                messagePreview = "Released report available",
                timestampLabel = "2026-11-06T12:00:00Z",
                isUnread = true,
                iconRes = 0
            )
        )
        assertEquals(1, NotificationRepository.notifications.value.size)

        val userRepository = UserRepository(
            authProvider = { error("FirebaseAuth not needed") },
            databaseProvider = { error("FirebaseDatabase not needed") }
        )
        userRepository.signOut()

        assertNull(PulseLinkApiClient.getAuthToken())
        assertNull(PulseLinkApiClient.getActiveUid())
        assertTrue(NotificationRepository.notifications.value.isEmpty())
    }

    @Test
    fun server_role_routing_enforces_approved_status_and_rejects_mismatched_or_unavailable_roles() = runBlocking {
        var currentUid: String? = "uid-doc"
        var refreshed = false

        val approvedDoctorRepo = UserRepository(
            api = api { name, _ ->
                assertEquals("getAccessAccount", name)
                Response.success(
                    AccessAccountDto(
                        uid = "uid-doc",
                        email = "doc@example.test",
                        displayName = "Dr Test",
                        role = "doctor",
                        status = "approved",
                        siteIds = listOf("site-1"),
                        version = 1
                    )
                )
            },
            currentUidProvider = { currentUid },
            tokenFetcher = {
                refreshed = true
                "jwt-doc"
            }
        )

        val role = approvedDoctorRepo.getUserRole("uid-doc")
        assertTrue(refreshed)
        assertEquals("doctor", role)
        assertEquals(DoctorDashboardActivity::class.java, dashboardFor(role))
        assertEquals(NurseDashboardActivity::class.java, dashboardFor("nurse"))
        assertEquals("jwt-doc", PulseLinkApiClient.getAuthToken())
        assertEquals("uid-doc", PulseLinkApiClient.getActiveUid())

        // Unapproved doctor request must route to SiteAccessActivity ("pending"), never DoctorDashboardActivity
        val pendingDoctorRepo = UserRepository(
            api = api { _, _ ->
                Response.success(
                    AccessAccountDto(
                        uid = "uid-doc",
                        email = "doc@example.test",
                        displayName = "Dr Pending",
                        role = "patient",
                        requestedRole = "doctor",
                        status = "pending",
                        version = 1
                    )
                )
            },
            currentUidProvider = { currentUid },
            tokenFetcher = { "jwt-doc" }
        )
        val pendingRole = pendingDoctorRepo.getUserRole("uid-doc")
        assertEquals("pending", pendingRole)
        assertEquals(SiteAccessActivity::class.java, dashboardFor(pendingRole))

        // Account switch mid-request must return "unavailable"
        val switchedRepo = UserRepository(
            api = api { _, _ ->
                currentUid = "uid-other"
                Response.success(
                    AccessAccountDto(
                        uid = "uid-doc",
                        role = "doctor",
                        status = "approved"
                    )
                )
            },
            currentUidProvider = { currentUid },
            tokenFetcher = { "jwt-doc" }
        )
        currentUid = "uid-doc"
        assertEquals("unavailable", switchedRepo.getUserRole("uid-doc"))

        // Offline/unavailable fallback resolution
        assertEquals("doctor", SessionManager.resolveRoleWithFallback("unavailable", "doctor"))
        assertEquals("pending", SessionManager.resolveRoleWithFallback("pending", "doctor"))
        assertEquals("unavailable", SessionManager.resolveRoleWithFallback("unavailable", null))
        assertFalse(SessionManager.isCacheableRole("unavailable"))
        assertTrue(SessionManager.isCacheableRole("patient"))
        assertTrue(SessionManager.isCacheableRole("doctor"))
        assertTrue(SessionManager.isCacheableRole("nurse"))
        assertTrue(SessionManager.isCacheableRole("administrator"))
        assertTrue(SessionManager.isCacheableRole("pending"))
    }

    @Test
    fun uid_to_pat_handling_distinguishes_firebase_uid_from_clinical_patient_id() = runBlocking {
        assertTrue(SessionManager.isClinicalPatientId("PAT-20261106-ABCD"))
        assertTrue(SessionManager.isClinicalPatientId("pat-0402"))
        assertFalse(SessionManager.isClinicalPatientId("firebaseUid_1234567890"))
        assertFalse(SessionManager.isClinicalPatientId(null))

        // Linked patient record resolution via /api/patient/record
        val linkedRepo = UserRepository(
            api = api { name, _ ->
                assertEquals("getOwnPatientRecord", name)
                Response.success(
                    PatientProfileDto(
                        patientId = "PAT-0402",
                        fullName = "Thabo Molefe"
                    )
                )
            },
            currentUidProvider = { "firebase-patient-uid" },
            tokenFetcher = { "jwt-patient" }
        )
        val linked = linkedRepo.resolveLinkedClinicalPatient()
        assertTrue(linked is PatientPortalResult.Ready)
        assertEquals("PAT-0402", (linked as PatientPortalResult.Ready).value.patientId)

        // Unlinked patient login returns NotLinked (HTTP 204), never a fabricated PAT record
        val unlinkedRepo = UserRepository(
            api = api { _, _ -> Response.success<PatientProfileDto>(204, null) },
            currentUidProvider = { "firebase-patient-uid" },
            tokenFetcher = { "jwt-patient" }
        )
        assertEquals(PatientPortalResult.NotLinked, unlinkedRepo.resolveLinkedClinicalPatient())

        // Clinical staff looking up a PAT-* identifier calls getClinicalPatient after refreshing token
        var tokenRefreshed = false
        val staffLookupRepo = UserRepository(
            api = api { name, args ->
                assertTrue(tokenRefreshed)
                assertEquals("getClinicalPatient", name)
                assertEquals("PAT-0402", args?.firstOrNull())
                Response.success(
                    ClinicalPatientDto(
                        id = "PAT-0402",
                        fullName = "Thabo Molefe",
                        siteId = "site-1",
                        primaryDoctorUid = "doc-1",
                        version = 1,
                        dateOfBirth = "1980-04-12"
                    )
                )
            },
            currentUidProvider = { "doc-1" },
            tokenFetcher = {
                tokenRefreshed = true
                "jwt-doc"
            }
        )
        val profileRes = staffLookupRepo.getUserProfile("PAT-0402")
        assertTrue(profileRes is Resource.Success)
        assertEquals("PAT-0402", profileRes.data?.uid)
        assertEquals("Thabo Molefe", profileRes.data?.displayName)
    }

    @Test
    fun ecg_repository_refreshes_token_propagates_cancellation_and_discards_switched_account_responses() = runBlocking {
        var uid: String? = "patient-1"
        var refreshed = false

        val sessions = listOf(
            EcgSessionSummaryDto(
                sessionId = "SESSION-1",
                patientId = "PAT-1",
                status = "Completed",
                resultsReleased = true,
                reportAvailable = true
            )
        )

        val validRepo = EcgRepository(
            apiService = api { name, _ ->
                assertTrue(refreshed)
                assertEquals("getPatientSessions", name)
                Response.success(sessions)
            },
            refreshToken = {
                refreshed = true
                "jwt-1"
            },
            currentUid = { uid }
        )

        val ok = validRepo.getPatientSessions("PAT-1")
        assertTrue(ok is Resource.Success)
        assertEquals(1, ok.data?.size)

        // Account switch mid-flight must discard the response and return Resource.Error
        val switchingRepo = EcgRepository(
            apiService = api { _, _ ->
                uid = "patient-2"
                Response.success(
                    EcgSessionSamplesResponseDto(
                        sessionId = "SESSION-1",
                        sampleRateHz = 250,
                        totalSamples = 3,
                        samples = listOf(2048, 2050, 2045)
                    )
                )
            },
            refreshToken = { "jwt-1" },
            currentUid = { uid }
        )
        uid = "patient-1"
        val switchedResult = switchingRepo.getSessionSamples("SESSION-1")
        assertTrue(switchedResult is Resource.Error)

        // Network/HTTP errors must return Resource.Error, never an empty success list
        val failingRepo = EcgRepository(
            apiService = api { _, _ -> Response.error<List<EcgSessionSummaryDto>>(503, "Unavailable".toResponseBody()) },
            refreshToken = { "jwt-1" },
            currentUid = { "patient-1" }
        )
        val failedHistory = failingRepo.getPatientSessions("PAT-1")
        assertTrue(failedHistory is Resource.Error)

        // CancellationException must propagate and never be swallowed into Resource.Error
        val cancellingRepo = EcgRepository(
            apiService = api { _, _ -> error("Must not be called") },
            refreshToken = { throw CancellationException("Cancelled") },
            currentUid = { "patient-1" }
        )
        try {
            cancellingRepo.getPatientSessions("PAT-1")
            fail("CancellationException must propagate from EcgRepository")
        } catch (_: CancellationException) {
            // Expected
        }
    }

    @Test
    fun nurse_screen_approval_discards_response_when_account_changes_mid_flight() = runBlocking {
        var activeUid: String? = "nurse-1"
        val grant = NurseGrantDto(
            id = "grant-1",
            deviceId = "PulseLink-test",
            siteId = "site-1",
            nurseUid = "nurse-1",
            nurseName = "Nurse One",
            approvedAtUtc = "2026-11-06T08:00:00Z",
            expiresAtUtc = "2026-11-06T16:00:00Z",
            syncUntilUtc = "2026-11-09T16:00:00Z"
        )

        val repo = NurseScreenApprovalRepository(
            refreshToken = { "jwt-nurse" },
            request = { _, _ ->
                activeUid = "nurse-2"
                Response.success(grant)
            },
            currentRequest = {
                activeUid = "nurse-2"
                Response.success(grant)
            },
            currentUid = { activeUid }
        )

        assertEquals(
            NurseScreenApproval.Failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED),
            repo.approve("PulseLink-test", "123456", "site-1", "nurse-1")
        )

        activeUid = "nurse-1"
        assertEquals(
            NurseScreenApproval.Failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED),
            repo.current("PulseLink-test", "site-1", "nurse-1")
        )
    }

    @Test
    fun clinical_pdf_report_generator_verifies_pdf_magic_bytes_and_clears_staged_files_on_sign_out() {
        val tempDir = Files.createTempDirectory("pulselink_reports_test").toFile()
        try {
            val validPdf = "%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1)
            val invalidPdf = "Plain text pretending to be a PDF report".toByteArray(Charsets.UTF_8)

            // Invalid payload must not be staged
            assertNull(
                ClinicalPdfReportGenerator.saveAuthoritativePdfToDirectory(
                    reportsDir = tempDir,
                    sessionId = "SESSION-100",
                    pdfBytes = invalidPdf,
                    releasedPatientCopy = true
                )
            )

            // Valid authoritative PDF is staged and found by findCachedPdfInDirectory
            val saved = ClinicalPdfReportGenerator.saveAuthoritativePdfToDirectory(
                reportsDir = tempDir,
                sessionId = "SESSION-100",
                pdfBytes = validPdf,
                releasedPatientCopy = true
            )
            assertNotNull(saved)
            assertTrue(ClinicalPdfReportGenerator.isValidPdfFile(saved))
            assertEquals(saved, ClinicalPdfReportGenerator.findCachedPdfInDirectory(tempDir, "SESSION-100"))

            // Corrupting the cached file on disk causes findCachedPdfInDirectory to reject it
            val corruptFile = File(tempDir, ClinicalPdfReportGenerator.sanitizeReportFileName("SESSION-CORRUPT", true))
            corruptFile.writeBytes(invalidPdf)
            assertNull(ClinicalPdfReportGenerator.findCachedPdfInDirectory(tempDir, "SESSION-CORRUPT"))

            // Clearing cached reports on sign-out removes all staged files
            val deletedCount = ClinicalPdfReportGenerator.clearCachedReportsInDirectory(tempDir)
            assertTrue(deletedCount >= 2)
            assertNull(ClinicalPdfReportGenerator.findCachedPdfInDirectory(tempDir, "SESSION-100"))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
