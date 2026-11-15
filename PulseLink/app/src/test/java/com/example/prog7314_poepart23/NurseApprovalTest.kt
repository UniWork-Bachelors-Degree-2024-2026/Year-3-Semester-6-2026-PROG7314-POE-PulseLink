package com.example.prog7314_poepart23

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NurseApprovalTest {
    @Test fun statusCodesMapToOutcomes() {
        assertEquals(ApprovalOutcome.SIGN_IN_REQUIRED, NurseApproval.classify(401, null))
        assertEquals(ApprovalOutcome.DENIED, NurseApproval.classify(403, null))
        assertEquals(ApprovalOutcome.DEVICE_NOT_FOUND, NurseApproval.classify(404, null))
        assertEquals(ApprovalOutcome.EXPIRED, NurseApproval.classify(410, null))
        assertEquals(ApprovalOutcome.TOO_MANY_ATTEMPTS, NurseApproval.classify(429, null))
        assertEquals(ApprovalOutcome.UNAVAILABLE, NurseApproval.classify(500, null))
    }

    @Test fun badRequestUsesProblemText() {
        assertEquals(ApprovalOutcome.WRONG_CODE, NurseApproval.classify(400, "Invalid code"))
        assertEquals(ApprovalOutcome.EXPIRED, NurseApproval.classify(422, "Code has expired"))
        assertEquals(ApprovalOutcome.DENIED, NurseApproval.classify(409, "Device is not assigned to your site"))
    }

    @Test fun parsesUtcTimestamps() {
        assertEquals(0L, NurseApproval.parseUtcMillis("1970-01-01T00:00:00Z"))
        assertEquals(1000L, NurseApproval.parseUtcMillis("1970-01-01T00:00:01.123Z"))
        assertNotNull(NurseApproval.parseUtcMillis("2026-11-06T08:00:00+00:00"))
        assertNull(NurseApproval.parseUtcMillis("not a date"))
        assertNull(NurseApproval.parseUtcMillis(null))
    }

    @Test fun codeEntryOnlyWhenNeeded() {
        assertEquals(true, NurseApproval.needsCode(AuthorisationState.NONE, approveAnother = false))
        assertEquals(true, NurseApproval.needsCode(AuthorisationState.EXPIRED, approveAnother = false))
        assertEquals(false, NurseApproval.needsCode(AuthorisationState.ACTIVE, approveAnother = false))
        assertEquals(true, NurseApproval.needsCode(AuthorisationState.ACTIVE, approveAnother = true))
    }

    @Test fun authorisationStateFollowsExpiry() {
        assertEquals(AuthorisationState.NONE, NurseApproval.authorisationState(null, 100L))
        assertEquals(AuthorisationState.ACTIVE, NurseApproval.authorisationState(200L, 100L))
        assertEquals(AuthorisationState.EXPIRED, NurseApproval.authorisationState(100L, 100L))
    }
}
