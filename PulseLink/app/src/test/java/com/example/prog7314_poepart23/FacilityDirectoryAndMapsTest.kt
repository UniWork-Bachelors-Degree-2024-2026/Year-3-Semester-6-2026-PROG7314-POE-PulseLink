package com.example.prog7314_poepart23

import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.DirectionsLaunchResult
import com.example.prog7314_poepart23.data.remote.models.FacilityDirectoryState
import com.example.prog7314_poepart23.data.remote.models.PublicFacilityDirectoryResponse
import com.example.prog7314_poepart23.data.remote.models.PublicFacilityDto
import com.example.prog7314_poepart23.data.repository.FacilityDirectionsHelper
import com.example.prog7314_poepart23.data.repository.FacilityDirectoryRepository
import com.example.prog7314_poepart23.data.repository.InMemoryFacilityCacheStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class FacilityDirectoryAndMapsTest {

    private fun fakeApi(
        onGetFacilities: () -> Response<PublicFacilityDirectoryResponse>
    ): PulseLinkApiService {
        return Proxy.newProxyInstance(
            PulseLinkApiService::class.java.classLoader,
            arrayOf(PulseLinkApiService::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getPublicFacilities" -> onGetFacilities()
                else -> throw UnsupportedOperationException("Unexpected call: ${method.name}")
            }
        } as PulseLinkApiService
    }

    @Test
    fun buildsValidatedMapsUrlWithoutClinicalOrOriginParametersAndRejectsInvalidCoordinates() {
        val livingstone = FacilityDirectoryRepository.SeededGqeberhaFacilities.first {
            it.facilityId == "facility-ec-nmb-livingstone"
        }
        val url = FacilityDirectionsHelper.buildValidatedGoogleMapsUrl(
            livingstone.entranceLatitude,
            livingstone.entranceLongitude
        )
        assertNotNull(url)
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=-33.925240,25.569760", url)
        assertFalse(url!!.contains("PAT-", ignoreCase = true))
        assertFalse(url.contains("origin=", ignoreCase = true))
        assertFalse(url.contains("travelmode=", ignoreCase = true))

        // Out-of-bounds or NaN coordinates must be rejected
        assertNull(FacilityDirectionsHelper.buildValidatedGoogleMapsUrl(0.0, 0.0))
        assertNull(FacilityDirectionsHelper.buildValidatedGoogleMapsUrl(-26.2041, 28.0473))
        assertNull(FacilityDirectionsHelper.buildValidatedGoogleMapsUrl(Double.NaN, 25.569760))
    }

    @Test
    fun handlesMapsAppLaunchBrowserFallbackOfflineFallbackAndInvalidDestinationCleanly() {
        val dora = FacilityDirectoryRepository.SeededGqeberhaFacilities.first {
            it.facilityId == "facility-ec-nmb-dora-nginza"
        }

        // 1. Online with Google Maps installed -> launches Google Maps app
        val mapsAppResult = FacilityDirectionsHelper.prepareDirectionsHandoff(
            facility = dora,
            isOnline = true,
            canResolveGoogleMapsApp = true,
            canResolveBrowser = true
        )
        assertTrue(mapsAppResult is DirectionsLaunchResult.LaunchedMapsApp)
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=-33.877300,25.560000",
            (mapsAppResult as DirectionsLaunchResult.LaunchedMapsApp).url
        )

        // 2. Online without Google Maps app, browser available -> launches browser Maps
        val browserResult = FacilityDirectionsHelper.prepareDirectionsHandoff(
            facility = dora,
            isOnline = true,
            canResolveGoogleMapsApp = false,
            canResolveBrowser = true
        )
        assertTrue(browserResult is DirectionsLaunchResult.LaunchedBrowserMaps)

        // 3. Offline -> returns AddressCoordinateFallback with verified street address and entrance coordinates
        val offlineResult = FacilityDirectionsHelper.prepareDirectionsHandoff(
            facility = dora,
            isOnline = false,
            canResolveGoogleMapsApp = true,
            canResolveBrowser = true
        )
        assertTrue(offlineResult is DirectionsLaunchResult.FallbackRequired)
        val fallback = (offlineResult as DirectionsLaunchResult.FallbackRequired).fallback
        assertEquals("Dora Nginza Provincial Hospital", fallback.facilityName)
        assertEquals("Spondo Street, Zwide, Gqeberha, 6201", fallback.address)
        assertEquals("-33.877300, 25.560000", fallback.formattedCoordinates)
        assertTrue(fallback.offlineOrUnavailableReason.contains("offline", ignoreCase = true))

        // 4. Neither Maps nor browser available -> returns AddressCoordinateFallback
        val noAppsResult = FacilityDirectionsHelper.prepareDirectionsHandoff(
            facility = dora,
            isOnline = true,
            canResolveGoogleMapsApp = false,
            canResolveBrowser = false
        )
        assertTrue(noAppsResult is DirectionsLaunchResult.FallbackRequired)

        // 5. Invalid destination coordinates -> returns InvalidDestination
        val invalidFacility = dora.copy(entranceLatitude = 10.0, entranceLongitude = 10.0)
        val invalidResult = FacilityDirectionsHelper.prepareDirectionsHandoff(
            facility = invalidFacility,
            isOnline = true,
            canResolveGoogleMapsApp = true,
            canResolveBrowser = true
        )
        assertTrue(invalidResult is DirectionsLaunchResult.InvalidDestination)
    }

    @Test
    fun cachesDirectoryAndSupportsNameAreaAndTypeSearchOnlineAndOffline() = runBlocking {
        val cache = InMemoryFacilityCacheStore()
        var networkAvailable = true
        val api = fakeApi {
            if (!networkAvailable) throw RuntimeException("Network unreachable")
            Response.success(
                PublicFacilityDirectoryResponse(
                    facilities = FacilityDirectoryRepository.SeededGqeberhaFacilities,
                    totalCount = FacilityDirectoryRepository.SeededGqeberhaFacilities.size,
                    municipality = FacilityDirectionsHelper.MUNICIPALITY_NAME,
                    lastReviewedDate = "2026-11-06",
                    clinicalDisclaimer = FacilityDirectionsHelper.CLINICAL_DISCLAIMER
                )
            )
        }
        val repo = FacilityDirectoryRepository(
            apiService = api,
            cacheStore = cache,
            ensureFreshAuth = { true }
        )

        // Online search by suburb/area ("Motherwell")
        val onlineState = repo.loadFacilities(query = "Motherwell", isOnline = true)
        assertTrue(onlineState is FacilityDirectoryState.Ready)
        val readyOnline = onlineState as FacilityDirectoryState.Ready
        assertFalse(readyOnline.isFromOfflineCache)
        assertEquals(1, readyOnline.facilities.size)
        assertEquals("Motherwell Community Health Centre (CHC)", readyOnline.facilities.first().name)
        assertEquals("2026-11-06", readyOnline.lastReviewedDate)

        // Offline search by facility type ("Clinic") uses cached public data
        networkAvailable = false
        val offlineState = repo.loadFacilities(facilityType = "Clinic", isOnline = false)
        assertTrue(offlineState is FacilityDirectoryState.Ready)
        val readyOffline = offlineState as FacilityDirectoryState.Ready
        assertTrue(readyOffline.isFromOfflineCache)
        assertEquals(4, readyOffline.facilities.size)
        assertNotNull(readyOffline.statusNotice)

        // Unmatched search returns Empty state with review date and disclaimer preserved
        val emptyState = repo.loadFacilities(query = "NonExistentSuburb", isOnline = false)
        assertTrue(emptyState is FacilityDirectoryState.Empty)
        assertEquals("2026-11-06", (emptyState as FacilityDirectoryState.Empty).lastReviewedDate)
    }
}
