package com.example.prog7314_poepart23.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.AddressCoordinateFallback
import com.example.prog7314_poepart23.data.remote.models.DirectionsLaunchResult
import com.example.prog7314_poepart23.data.remote.models.FacilityDirectoryState
import com.example.prog7314_poepart23.data.remote.models.PublicFacilityDirectoryResponse
import com.example.prog7314_poepart23.data.remote.models.PublicFacilityDto
import com.example.prog7314_poepart23.security.TokenManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Locale
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.utils.LocaleHelper

/**
 * Validates destination coordinates and constructs strictly sanitised Google Maps directions
 * links and offline/unavailable address-coordinate fallbacks.
 *
 * Never embeds clinical information, credentials, or patient identifiers in navigation URLs,
 * and leaves origin and travel mode to Google Maps.
 */
object FacilityDirectionsHelper {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    const val MUNICIPALITY_NAME = "Nelson Mandela Bay Metropolitan Municipality (Gqeberha / Port Elizabeth), Eastern Cape"
    const val REVIEWED_DATE_ISO = "2026-11-06"
    const val CLINICAL_DISCLAIMER =
        "Reviewed public healthcare directory for Gqeberha / Port Elizabeth. Does not indicate fastest or nearest clinical suitability, live opening hours, bed or staff capacity, or emergency ambulance dispatch."

    /**
     * Clinical disclaimer in the user's language. A server-supplied disclaimer is used as-is
     * unless it is blank or identical to the built-in English default, in which case the
     * localised string resource is used.
     */
    fun clinicalDisclaimer(serverText: String? = null): String {
        val localised = LocaleHelper.stringOr(R.string.rt_facility_disclaimer, CLINICAL_DISCLAIMER)
        return if (serverText.isNullOrBlank() || serverText == CLINICAL_DISCLAIMER) localised else serverText
    }

    private const val MIN_LATITUDE = -35.0
    private const val MAX_LATITUDE = -32.0
    private const val MIN_LONGITUDE = 24.0
    private const val MAX_LONGITUDE = 27.0

    fun isValidEasternCapeCoordinate(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || latitude.isInfinite() || longitude.isNaN() || longitude.isInfinite()) {
            return false
        }
        return latitude in MIN_LATITUDE..MAX_LATITUDE && longitude in MIN_LONGITUDE..MAX_LONGITUDE
    }

    fun formatCoordinates(latitude: Double, longitude: Double): String {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
    }

    /**
     * Produces a validated Google Maps directions URL for the destination entrance coordinates
     * only, leaving Google Maps to determine the user's origin and travel mode and excluding
     * all clinical, patient, or authentication fields.
     */
    fun buildValidatedGoogleMapsUrl(latitude: Double, longitude: Double): String? {
        if (!isValidEasternCapeCoordinate(latitude, longitude)) {
            return null
        }
        val latStr = String.format(Locale.US, "%.6f", latitude)
        val lngStr = String.format(Locale.US, "%.6f", longitude)
        return "https://www.google.com/maps/dir/?api=1&destination=$latStr,$lngStr"
    }

    fun buildFallback(facility: PublicFacilityDto, reason: String): AddressCoordinateFallback {
        val safeUrl = buildValidatedGoogleMapsUrl(facility.entranceLatitude, facility.entranceLongitude)
            ?: ""
        return AddressCoordinateFallback(
            facilityName = facility.name,
            facilityType = facility.facilityType,
            area = facility.area,
            address = facility.address,
            formattedCoordinates = formatCoordinates(facility.entranceLatitude, facility.entranceLongitude),
            validatedGoogleMapsUrl = safeUrl,
            source = facility.source,
            lastCheckedDate = facility.lastCheckedDate,
            offlineOrUnavailableReason = reason
        )
    }

    /**
     * Pure, deterministic handoff evaluator used by both the Android intent launcher and unit tests.
     */
    fun prepareDirectionsHandoff(
        facility: PublicFacilityDto?,
        isOnline: Boolean,
        canResolveGoogleMapsApp: Boolean,
        canResolveBrowser: Boolean
    ): DirectionsLaunchResult {
        if (facility == null || facility.facilityId.isBlank() || facility.name.isBlank() || facility.address.isBlank()) {
            return DirectionsLaunchResult.InvalidDestination(LocaleHelper.stringOr(R.string.rt_facility_dest_missing, "Selected destination is missing verified facility details."))
        }
        val validatedUrl = buildValidatedGoogleMapsUrl(facility.entranceLatitude, facility.entranceLongitude)
            ?: return DirectionsLaunchResult.InvalidDestination(
                LocaleHelper.stringOr(R.string.rt_facility_dest_invalid, "Destination coordinates for %1\$s are invalid or outside the verified Gqeberha / Eastern Cape directory bounds.", facility.name)
            )

        if (!isOnline) {
            return DirectionsLaunchResult.FallbackRequired(
                buildFallback(
                    facility = facility,
                    reason = LocaleHelper.stringOr(R.string.rt_facility_reason_offline, "Device is offline. Live routing is not available offline; use the verified street address and entrance coordinates below.")
                )
            )
        }

        if (canResolveGoogleMapsApp) {
            return DirectionsLaunchResult.LaunchedMapsApp(validatedUrl, facility.name)
        }
        if (canResolveBrowser) {
            return DirectionsLaunchResult.LaunchedBrowserMaps(validatedUrl, facility.name)
        }
        return DirectionsLaunchResult.FallbackRequired(
            buildFallback(
                facility = facility,
                reason = LocaleHelper.stringOr(R.string.rt_facility_reason_no_apps, "Google Maps and web browser are unavailable on this device. Use the verified street address and entrance coordinates below.")
            )
        )
    }

    /**
     * Launches validated Google Maps directions on an Android device, falling back cleanly to
     * browser directions or the verified address/coordinate fallback when offline or unavailable.
     */
    fun openDirectionsInGoogleMaps(
        context: Context,
        facility: PublicFacilityDto?,
        isOnline: Boolean
    ): DirectionsLaunchResult {
        val pm = context.packageManager
        val probeUrl = facility?.let { buildValidatedGoogleMapsUrl(it.entranceLatitude, it.entranceLongitude) }
        val mapsIntent = probeUrl?.let {
            Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply {
                setPackage(GOOGLE_MAPS_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val browserIntent = probeUrl?.let {
            Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val canResolveMaps = mapsIntent != null && mapsIntent.resolveActivity(pm) != null
        val canResolveBrowser = browserIntent != null && browserIntent.resolveActivity(pm) != null

        val decision = prepareDirectionsHandoff(
            facility = facility,
            isOnline = isOnline,
            canResolveGoogleMapsApp = canResolveMaps,
            canResolveBrowser = canResolveBrowser
        )

        return when (decision) {
            is DirectionsLaunchResult.LaunchedMapsApp -> {
                runCatching {
                    context.startActivity(mapsIntent)
                    decision
                }.getOrElse {
                    DirectionsLaunchResult.FallbackRequired(
                        buildFallback(
                            facility = facility!!,
                            reason = LocaleHelper.stringOr(R.string.rt_facility_reason_maps_failed, "Could not open Google Maps. Use the verified street address and entrance coordinates below.")
                        )
                    )
                }
            }
            is DirectionsLaunchResult.LaunchedBrowserMaps -> {
                runCatching {
                    context.startActivity(browserIntent)
                    decision
                }.getOrElse {
                    DirectionsLaunchResult.FallbackRequired(
                        buildFallback(
                            facility = facility!!,
                            reason = LocaleHelper.stringOr(R.string.rt_facility_reason_browser_failed, "Could not open browser directions. Use the verified street address and entrance coordinates below.")
                        )
                    )
                }
            }
            else -> decision
        }
    }
}

interface FacilityCacheStore {
    fun readCachedFacilities(): List<PublicFacilityDto>?
    fun writeCachedFacilities(facilities: List<PublicFacilityDto>, lastReviewedDate: String)
    fun readLastReviewedDate(): String?
}

class SharedPrefsFacilityCacheStore(context: Context) : FacilityCacheStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun readCachedFacilities(): List<PublicFacilityDto>? {
        val raw = prefs.getString(KEY_FACILITIES_JSON, null) ?: return null
        return runCatching {
            val type = object : TypeToken<List<PublicFacilityDto>>() {}.type
            gson.fromJson<List<PublicFacilityDto>>(raw, type)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    override fun writeCachedFacilities(facilities: List<PublicFacilityDto>, lastReviewedDate: String) {
        prefs.edit()
            .putString(KEY_FACILITIES_JSON, gson.toJson(facilities))
            .putString(KEY_REVIEWED_DATE, lastReviewedDate)
            .apply()
    }

    override fun readLastReviewedDate(): String? = prefs.getString(KEY_REVIEWED_DATE, null)

    companion object {
        private const val PREFS_NAME = "pulselink_public_facility_cache"
        private const val KEY_FACILITIES_JSON = "facilities_json"
        private const val KEY_REVIEWED_DATE = "last_reviewed_date"
    }
}

class InMemoryFacilityCacheStore : FacilityCacheStore {
    private var cached: List<PublicFacilityDto>? = null
    private var reviewedDate: String? = null

    override fun readCachedFacilities(): List<PublicFacilityDto>? = cached
    override fun writeCachedFacilities(facilities: List<PublicFacilityDto>, lastReviewedDate: String) {
        cached = facilities.toList()
        reviewedDate = lastReviewedDate
    }
    override fun readLastReviewedDate(): String? = reviewedDate
}

/**
 * Repository for the reviewed Gqeberha / Port Elizabeth public healthcare facility directory.
 * Supports name/area/type search, cached public data for offline access, and validated Google Maps
 * destination handoff.
 */
class FacilityDirectoryRepository(
    private val apiService: PulseLinkApiService = PulseLinkApiClient.apiService,
    private val cacheStore: FacilityCacheStore = InMemoryFacilityCacheStore(),
    private val ensureFreshAuth: suspend () -> Boolean = { true }
) {
    constructor(context: Context) : this(
        apiService = PulseLinkApiClient.apiService,
        cacheStore = SharedPrefsFacilityCacheStore(context),
        ensureFreshAuth = { TokenManager.refreshToken(context.applicationContext) != null }
    )

    suspend fun loadFacilities(
        query: String? = null,
        facilityType: String? = null,
        isOnline: Boolean = true
    ): FacilityDirectoryState {
        if (isOnline && ensureFreshAuth()) {
            val remoteResult = runCatching { apiService.getPublicFacilities(query = null, type = null) }.getOrNull()
            if (remoteResult != null && remoteResult.isSuccessful) {
                val body: PublicFacilityDirectoryResponse? = remoteResult.body()
                val validRemote = body?.facilities?.filter {
                    FacilityDirectionsHelper.isValidEasternCapeCoordinate(it.entranceLatitude, it.entranceLongitude)
                }.orEmpty()
                if (validRemote.isNotEmpty()) {
                    val reviewed = body?.lastReviewedDate?.ifBlank { FacilityDirectionsHelper.REVIEWED_DATE_ISO }
                        ?: FacilityDirectionsHelper.REVIEWED_DATE_ISO
                    cacheStore.writeCachedFacilities(validRemote, reviewed)
                    return filterAndBuildState(
                        sourceList = validRemote,
                        query = query,
                        facilityType = facilityType,
                        isFromOfflineCache = false,
                        municipality = body?.municipality?.ifBlank { FacilityDirectionsHelper.MUNICIPALITY_NAME }
                            ?: FacilityDirectionsHelper.MUNICIPALITY_NAME,
                        lastReviewedDate = reviewed,
                        clinicalDisclaimer = FacilityDirectionsHelper.clinicalDisclaimer(body?.clinicalDisclaimer),
                        statusNotice = null
                    )
                }
            }
        }

        // Offline or API unreachable: serve from verified local cache or reviewed Gqeberha seed directory.
        val cachedOrSeeded = cacheStore.readCachedFacilities() ?: SeededGqeberhaFacilities
        val reviewedDate = cacheStore.readLastReviewedDate() ?: FacilityDirectionsHelper.REVIEWED_DATE_ISO
        val notice = if (!isOnline) {
            LocaleHelper.stringOr(R.string.rt_facility_notice_offline, "Offline mode: showing cached Gqeberha / Port Elizabeth public facility directory (last reviewed %1\$s).", reviewedDate)
        } else {
            LocaleHelper.stringOr(R.string.rt_facility_notice_cached, "Showing locally cached Gqeberha / Port Elizabeth public facility directory (last reviewed %1\$s).", reviewedDate)
        }

        return filterAndBuildState(
            sourceList = cachedOrSeeded,
            query = query,
            facilityType = facilityType,
            isFromOfflineCache = true,
            municipality = FacilityDirectionsHelper.MUNICIPALITY_NAME,
            lastReviewedDate = reviewedDate,
            clinicalDisclaimer = FacilityDirectionsHelper.clinicalDisclaimer(),
            statusNotice = notice
        )
    }

    fun getFacilityByIdFromCacheOrSeed(facilityId: String?): PublicFacilityDto? {
        if (facilityId.isNullOrBlank()) return null
        val cleaned = facilityId.trim()
        val list = cacheStore.readCachedFacilities() ?: SeededGqeberhaFacilities
        return list.firstOrNull { it.facilityId.equals(cleaned, ignoreCase = true) }
    }

    private fun filterAndBuildState(
        sourceList: List<PublicFacilityDto>,
        query: String?,
        facilityType: String?,
        isFromOfflineCache: Boolean,
        municipality: String,
        lastReviewedDate: String,
        clinicalDisclaimer: String,
        statusNotice: String?
    ): FacilityDirectoryState {
        var filtered = sourceList
        if (!facilityType.isNullOrBlank()) {
            val typeNeedle = facilityType.trim()
            filtered = filtered.filter { it.facilityType.contains(typeNeedle, ignoreCase = true) }
        }
        if (!query.isNullOrBlank()) {
            val qNeedle = query.trim()
            filtered = filtered.filter {
                it.name.contains(qNeedle, ignoreCase = true) ||
                    it.area.contains(qNeedle, ignoreCase = true) ||
                    it.address.contains(qNeedle, ignoreCase = true) ||
                    it.facilityType.contains(qNeedle, ignoreCase = true)
            }
        }

        if (filtered.isEmpty()) {
            return FacilityDirectoryState.Empty(
                query = query?.trim().orEmpty(),
                isFromOfflineCache = isFromOfflineCache,
                lastReviewedDate = lastReviewedDate,
                clinicalDisclaimer = clinicalDisclaimer
            )
        }

        return FacilityDirectoryState.Ready(
            facilities = filtered,
            isFromOfflineCache = isFromOfflineCache,
            municipality = municipality,
            lastReviewedDate = lastReviewedDate,
            clinicalDisclaimer = clinicalDisclaimer,
            statusNotice = statusNotice
        )
    }

    companion object {
        private fun createSeed(
            id: String,
            name: String,
            type: String,
            area: String,
            address: String,
            lat: Double,
            lng: Double,
            source: String
        ): PublicFacilityDto {
            return PublicFacilityDto(
                facilityId = id,
                name = name,
                facilityType = type,
                area = area,
                address = address,
                entranceLatitude = lat,
                entranceLongitude = lng,
                source = source,
                lastCheckedDate = FacilityDirectionsHelper.REVIEWED_DATE_ISO,
                validatedGoogleMapsUrl = FacilityDirectionsHelper.buildValidatedGoogleMapsUrl(lat, lng).orEmpty()
            )
        }

        val SeededGqeberhaFacilities: List<PublicFacilityDto> = listOf(
            createSeed(
                id = "facility-ec-nmb-livingstone",
                name = "Livingstone Tertiary Hospital",
                type = "Tertiary Hospital",
                area = "Korsten, Gqeberha",
                address = "Stanford Road, Korsten, Gqeberha, 6020",
                lat = -33.925240,
                lng = 25.569760,
                source = "Eastern Cape Department of Health / National Health Facility Directory"
            ),
            createSeed(
                id = "facility-ec-nmb-dora-nginza",
                name = "Dora Nginza Provincial Hospital",
                type = "Provincial Hospital",
                area = "Zwide (Ibhayi), Gqeberha",
                address = "Spondo Street, Zwide, Gqeberha, 6201",
                lat = -33.877300,
                lng = 25.560000,
                source = "Eastern Cape Department of Health / National Health Facility Directory"
            ),
            createSeed(
                id = "facility-ec-nmb-pe-provincial",
                name = "Port Elizabeth Provincial Hospital",
                type = "Regional Hospital",
                area = "Mount Croix, Gqeberha",
                address = "Gipson Street & Buckingham Road, Mount Croix, Gqeberha, 6001",
                lat = -33.958300,
                lng = 25.599190,
                source = "Eastern Cape Department of Health / National Health Facility Directory"
            ),
            createSeed(
                id = "facility-ec-nmb-motherwell-chc",
                name = "Motherwell Community Health Centre (CHC)",
                type = "Community Health Centre",
                area = "Motherwell (NU 8), Gqeberha",
                address = "81 Tynira Street, Motherwell, Gqeberha, 6211",
                lat = -33.798700,
                lng = 25.585900,
                source = "Eastern Cape Department of Health / National Health Facility Directory"
            ),
            createSeed(
                id = "facility-ec-nmb-central-clinic",
                name = "Central Clinic (Gqeberha Central)",
                type = "Public Primary Healthcare Clinic",
                area = "Gqeberha Central",
                address = "6 Rose Street, Gqeberha Central, Gqeberha, 6001",
                lat = -33.964300,
                lng = 25.613300,
                source = "Eastern Cape Department of Health / Nelson Mandela Bay Health District"
            ),
            createSeed(
                id = "facility-ec-nmb-walmer-14th",
                name = "Walmer 14th Avenue Clinic",
                type = "Public Primary Healthcare Clinic",
                area = "Walmer, Gqeberha",
                address = "14th Avenue, Walmer, Gqeberha, 6070",
                lat = -33.975830,
                lng = 25.585830,
                source = "Eastern Cape Department of Health / Nelson Mandela Bay Health District"
            ),
            createSeed(
                id = "facility-ec-nmb-schauderville",
                name = "Schauderville Clinic",
                type = "Public Primary Healthcare Clinic",
                area = "Schauderville, Gqeberha",
                address = "Corner of Highfield Street & Jan Hofmeyr Road, Schauderville, Gqeberha, 6060",
                lat = -33.934800,
                lng = 25.579400,
                source = "Eastern Cape Department of Health / Nelson Mandela Bay Health District"
            ),
            createSeed(
                id = "facility-ec-nmb-new-brighton",
                name = "New Brighton Clinic",
                type = "Public Primary Healthcare Clinic",
                area = "New Brighton, Gqeberha",
                address = "Mendi Road, New Brighton, Gqeberha, 6200",
                lat = -33.904500,
                lng = 25.592800,
                source = "Eastern Cape Department of Health / Nelson Mandela Bay Health District"
            )
        )
    }
}
