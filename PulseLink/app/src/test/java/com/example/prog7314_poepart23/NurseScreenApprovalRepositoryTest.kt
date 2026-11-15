package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.data.remote.models.NurseGrantDto
import com.example.prog7314_poepart23.data.repository.NurseScreenApproval
import com.example.prog7314_poepart23.data.repository.NurseScreenApprovalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class NurseScreenApprovalRepositoryTest {
    private val grant = NurseGrantDto("grant", "PulseLink-test", "site", "nurse", "Nurse",
        "2026-11-06T08:00:00Z", "2026-11-06T16:00:00Z", "2026-11-09T16:00:00Z")

    @Test fun restoring_an_active_shift_never_posts_another_code() = runBlocking {
        val repository = NurseScreenApprovalRepository({ "test-only" }, { _, _ -> error("Must not approve again") },
            currentRequest = { Response.success(grant) })
        assertEquals(NurseScreenApproval.Approved(grant), repository.current("PulseLink-test", "site", "nurse"))
    }
    @Test fun a_failed_connection_check_does_not_reapprove_or_claim_the_shift_expired() = runBlocking {
        val repository = NurseScreenApprovalRepository({ "test-only" }, { _, _ -> error("Must not approve again") },
            currentRequest = { throw java.io.IOException("offline") })
        assertEquals(NurseScreenApproval.Failed(NurseScreenApproval.Reason.UNAVAILABLE), repository.current("PulseLink-test", "site", "nurse"))
    }

    @Test fun no_active_shift_and_a_different_nurse_are_not_treated_as_reusable_approval() = runBlocking {
        val expired = NurseScreenApprovalRepository({ "test-only" }, { _, _ -> error("Must not approve") },
            currentRequest = { Response.success<NurseGrantDto>(204, null) })
        assertEquals(NurseScreenApproval.NotActive, expired.current("PulseLink-test", "site", "nurse"))
        val wrongNurse = NurseScreenApprovalRepository({ "test-only" }, { _, _ -> error("Must not approve") },
            currentRequest = { Response.success(grant.copy(nurseUid = "other")) })
        assertEquals(NurseScreenApproval.Failed(NurseScreenApproval.Reason.INVALID_RECEIPT), wrongNurse.current("PulseLink-test", "site", "nurse"))
    }

    @Test fun invalid_input_never_refreshes_credentials_or_sends_a_request() = runBlocking {
        val repository = NurseScreenApprovalRepository(
            refreshToken = { error("Must not refresh") }, request = { _, _ -> error("Must not send") })
        assertEquals(NurseScreenApproval.Failed(NurseScreenApproval.Reason.INVALID_DEVICE), repository.approve("bad/id", "000123", "site", "nurse"))
        assertEquals(NurseScreenApproval.Failed(NurseScreenApproval.Reason.INVALID_CODE), repository.approve("PulseLink-test", "123", "site", "nurse"))
        assertEquals(NurseScreenApproval.Failed(NurseScreenApproval.Reason.DENIED), repository.approve("PulseLink-test", "000123", "", "nurse"))
    }

    @Test fun leading_zeroes_are_preserved_and_token_is_refreshed_before_approval() = runBlocking {
        var refreshed = false
        val repository = NurseScreenApprovalRepository(refreshToken = { refreshed = true; "test-only" }, request = { id, body ->
            assertTrue(refreshed); assertEquals("PulseLink-test", id); assertEquals("000123", body.code)
            Response.success(grant)
        })
        assertEquals(NurseScreenApproval.Approved(grant), repository.approve(" PulseLink-test ", "000123", "site", "nurse"))
    }

    @Test fun a_receipt_for_another_device_site_or_nurse_is_not_reported_as_approved() = runBlocking {
        for (wrong in listOf(grant.copy(deviceId = "other"), grant.copy(siteId = "other"), grant.copy(nurseUid = "other"))) {
            val repository = NurseScreenApprovalRepository({ "test-only" }, { _, _ -> Response.success(wrong) })
            assertEquals(NurseScreenApproval.Failed(NurseScreenApproval.Reason.INVALID_RECEIPT), repository.approve("PulseLink-test", "000123", "site", "nurse"))
        }
    }

    @Test fun expired_denied_and_conflicting_codes_have_distinct_results() = runBlocking {
        for ((status, reason) in mapOf(401 to NurseScreenApproval.Reason.SIGN_IN_REQUIRED,
            403 to NurseScreenApproval.Reason.DENIED, 409 to NurseScreenApproval.Reason.CONFLICT,
            410 to NurseScreenApproval.Reason.EXPIRED, 503 to NurseScreenApproval.Reason.UNAVAILABLE)) {
            val repository = NurseScreenApprovalRepository({ "test-only" }, { _, _ -> Response.error(status, "{}".toResponseBody()) })
            assertEquals(NurseScreenApproval.Failed(reason), repository.approve("PulseLink-test", "000123", "site", "nurse"))
        }
    }

    @Test fun missing_login_does_not_send_and_cancellation_is_not_swallowed() = runBlocking {
        val signedOut = NurseScreenApprovalRepository({ null }, { _, _ -> error("Must not send") })
        assertEquals(NurseScreenApproval.Failed(NurseScreenApproval.Reason.SIGN_IN_REQUIRED), signedOut.approve("PulseLink-test", "000123", "site", "nurse"))
        val cancelled = NurseScreenApprovalRepository({ throw CancellationException("closed") }, { _, _ -> error("Must not send") })
        try { cancelled.approve("PulseLink-test", "000123", "site", "nurse"); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }
}
