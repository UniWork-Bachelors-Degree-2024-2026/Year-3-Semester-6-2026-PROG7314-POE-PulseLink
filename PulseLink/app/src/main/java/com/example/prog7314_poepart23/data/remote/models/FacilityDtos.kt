package com.example.prog7314_poepart23.data.remote.models

import com.google.gson.annotations.SerializedName

/**
 * Verified public healthcare facility entry for Port Elizabeth / Gqeberha, Eastern Cape.
 *
 * Architectural guardrails:
 * 1. Contains no clinical patient records, credentials, or live capacity/dispatch claims.
 * 2. Provides verified entrance coordinates, authoritative source, and last-checked date.
 */
data class PublicFacilityDto(
    @SerializedName("facilityId") val facilityId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("facilityType") val facilityType: String = "",
    @SerializedName("area") val area: String = "",
    @SerializedName("address") val address: String = "",
    @SerializedName("entranceLatitude") val entranceLatitude: Double = 0.0,
    @SerializedName("entranceLongitude") val entranceLongitude: Double = 0.0,
    @SerializedName("source") val source: String = "",
    @SerializedName("lastCheckedDate") val lastCheckedDate: String = "",
    @SerializedName("validatedGoogleMapsUrl") val validatedGoogleMapsUrl: String = ""
)

data class PublicFacilityDirectoryResponse(
    @SerializedName("facilities") val facilities: List<PublicFacilityDto> = emptyList(),
    @SerializedName("totalCount") val totalCount: Int = 0,
    @SerializedName("municipality") val municipality: String = "",
    @SerializedName("lastReviewedDate") val lastReviewedDate: String = "",
    @SerializedName("clinicalDisclaimer") val clinicalDisclaimer: String = ""
)

/**
 * Fallback details displayed when the phone is offline or neither Google Maps nor a browser
 * is available to open directions.
 */
data class AddressCoordinateFallback(
    val facilityName: String,
    val facilityType: String,
    val area: String,
    val address: String,
    val formattedCoordinates: String,
    val validatedGoogleMapsUrl: String,
    val source: String,
    val lastCheckedDate: String,
    val offlineOrUnavailableReason: String
)

/**
 * Outcome of requesting Google Maps directions for a selected public clinic or hospital.
 */
sealed class DirectionsLaunchResult {
    data class LaunchedMapsApp(val url: String, val facilityName: String) : DirectionsLaunchResult()
    data class LaunchedBrowserMaps(val url: String, val facilityName: String) : DirectionsLaunchResult()
    data class FallbackRequired(val fallback: AddressCoordinateFallback) : DirectionsLaunchResult()
    data class InvalidDestination(val reason: String) : DirectionsLaunchResult()
}

/**
 * UI state contract for Maxine's Facility Finder screen (list/detail/search, review date,
 * offline cache notice, and error states).
 */
sealed class FacilityDirectoryState {
    object Loading : FacilityDirectoryState()
    data class Ready(
        val facilities: List<PublicFacilityDto>,
        val isFromOfflineCache: Boolean,
        val municipality: String,
        val lastReviewedDate: String,
        val clinicalDisclaimer: String,
        val statusNotice: String? = null
    ) : FacilityDirectoryState()
    data class Empty(
        val query: String,
        val isFromOfflineCache: Boolean,
        val lastReviewedDate: String,
        val clinicalDisclaimer: String
    ) : FacilityDirectoryState()
    data class Error(val message: String) : FacilityDirectoryState()
}
