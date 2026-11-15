package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.utils.GoogleSignInFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleSignInFailureTest {
    @Test fun cancelledPickerIsNotAConfigurationFailure() {
        assertEquals(GoogleSignInFailure.CANCELLED, GoogleSignInFailure.fromStatusCode(12501))
        assertEquals(GoogleSignInFailure.CONFIGURATION, GoogleSignInFailure.fromStatusCode(10))
    }

    @Test fun networkAndServiceFailuresCanBeReportedSeparately() {
        assertEquals(GoogleSignInFailure.NETWORK, GoogleSignInFailure.fromStatusCode(7))
        assertEquals(GoogleSignInFailure.UNAVAILABLE, GoogleSignInFailure.fromStatusCode(12500))
        assertEquals(GoogleSignInFailure.IN_PROGRESS, GoogleSignInFailure.fromStatusCode(12502))
        assertEquals(GoogleSignInFailure.UNKNOWN, GoogleSignInFailure.fromStatusCode(-1))
    }
}
