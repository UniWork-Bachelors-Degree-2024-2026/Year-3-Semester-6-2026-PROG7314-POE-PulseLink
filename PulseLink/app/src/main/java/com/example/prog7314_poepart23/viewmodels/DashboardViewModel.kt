/*
 * ViewModel managing telemetry data, recent recording statistics, hardware health indicators,
 * and unacknowledged alerts for PatientDashboardActivity.
 *
 * Reference List:
 * - Google Developers, 2024. ViewModel Overview - Android Developers. [online] Available at: <https://developer.android.com/topic/libraries/architecture/viewmodel> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.viewmodels

import androidx.lifecycle.LiveData               // (Google Developers, 2024)
import androidx.lifecycle.MutableLiveData        // (Google Developers, 2024)
import androidx.lifecycle.ViewModel              // (Google Developers, 2024)
import androidx.lifecycle.viewModelScope         // (Google Developers, 2024)
import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.remote.models.DeviceStatusDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.remote.models.PatientProfileDto
import com.example.prog7314_poepart23.data.remote.models.PatientReleaseDto
import com.example.prog7314_poepart23.data.repository.EcgRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalRepository
import com.example.prog7314_poepart23.data.repository.PatientPortalResult
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.Resource
import kotlinx.coroutines.Job                    // (JetBrains, 2024)
import kotlinx.coroutines.launch                 // (JetBrains, 2024)

// Snapshot of the patient's sessions shared by the dashboard and the session status screen.
// Counts come only from fields the API already returns; no clinical label is inferred here.
data class SessionOverview(
    val latest: EcgSessionSummaryDto?,
    val totalSessions: Int,
    val completedReports: Int,
    val releasedNotes: Int,
    val sessions: List<EcgSessionSummaryDto> = emptyList()
) {
    companion object {
        // A patient is only told about reports and notes the care team has released, so a report
        // or a note on a session whose results are still unreleased is never counted.
        fun from(sessions: List<EcgSessionSummaryDto>): SessionOverview = SessionOverview(
            latest = sessions.maxByOrNull { it.startTimeUtc },
            totalSessions = sessions.size,
            completedReports = sessions.count { it.resultsReleased && it.reportAvailable },
            releasedNotes = sessions.count { it.resultsReleased && it.hasDoctorNotes },
            sessions = sessions
        )
    }
}

class DashboardViewModel( // (Google Developers, 2024)
    private val ecgRepository: EcgRepository = EcgRepository(),
    private val patientPortalRepository: PatientPortalRepository = PatientPortalRepository()
) : ViewModel() {

    constructor(ecgRepository: EcgRepository) : this(
        ecgRepository = ecgRepository,
        patientPortalRepository = PatientPortalRepository()
    )

    private val _recentSession = MutableLiveData<Resource<EcgSessionSummaryDto?>>() // (Google Developers, 2024)
    val recentSession: LiveData<Resource<EcgSessionSummaryDto?>> = _recentSession        // (Google Developers, 2024)
    private var recentSessionJob: Job? = null

    private val _sessionOverview = MutableLiveData<Resource<SessionOverview>>()      // (Google Developers, 2024)
    val sessionOverview: LiveData<Resource<SessionOverview>> = _sessionOverview          // (Google Developers, 2024)
    private var overviewJob: Job? = null

    private val _deviceStatus = MutableLiveData<Resource<DeviceStatusDto>>()         // (Google Developers, 2024)
    val deviceStatus: LiveData<Resource<DeviceStatusDto>> = _deviceStatus                // (Google Developers, 2024)
    private var deviceStatusJob: Job? = null

    private val _alertsCount = MutableLiveData<Resource<Int>>()                         // (Google Developers, 2024)
    val alertsCount: LiveData<Resource<Int>> = _alertsCount                              // (Google Developers, 2024)
    private var alertsCountJob: Job? = null

    private val _linkedPatientRecord = MutableLiveData<PatientPortalResult<PatientProfileDto>>()
    val linkedPatientRecord: LiveData<PatientPortalResult<PatientProfileDto>> = _linkedPatientRecord
    private var linkedPatientJob: Job? = null

    // Refreshes all patient dashboard telemetry metrics concurrently. deviceId is null
    // when the patient has not paired a device yet; callers should show an empty state
    // for hardware status rather than calling this with a fabricated ID.
    fun loadDashboardData(patientId: String, deviceId: String?) {
        fetchRecentSession(patientId)
        fetchActiveAlertsCount()
        if (deviceId != null) {
            fetchDeviceStatus(deviceId)
        }
    }

    // Loads the signed-in patient's linked clinical PAT-* record from the server.
    fun fetchLinkedPatientRecord() {
        linkedPatientJob?.cancel()
        linkedPatientJob = viewModelScope.launch {
            _linkedPatientRecord.value = patientPortalRepository.record()
        }
    }

    // Loads the patient's sessions once and derives the latest session plus the report and note counts.
    // A newer call cancels an older one, so a slow earlier response can never overwrite a later
    // empty or error result with stale data.
    fun fetchSessionOverview(patientId: String) {
        overviewJob?.cancel()
        _sessionOverview.value = Resource.Loading()
        overviewJob = viewModelScope.launch {
            val result = ecgRepository.getPatientSessions(patientId)
            val sessions = result.data
            if (result is Resource.Success && sessions != null) {
                _sessionOverview.value = Resource.Success(SessionOverview.from(sessions))
            } else {
                _sessionOverview.value = Resource.Error(result.message ?: LocaleHelper.string(R.string.l10n_err_latest_session))
            }
        }
    }

    // Fetches released snapshot payloads for the given session IDs so clinical updates can populate.
    suspend fun loadReleasedSessionDetails(sessionIds: List<String>): List<PatientReleaseDto> {
        val released = mutableListOf<PatientReleaseDto>()
        for (id in sessionIds.take(10)) {
            val result = patientPortalRepository.results(id)
            if (result is PatientPortalResult.Ready) {
                released.add(result.value)
            }
        }
        return released
    }

    // Fetches the most recent ECG session for the patient.
    fun fetchRecentSession(patientId: String) {
        recentSessionJob?.cancel()
        _recentSession.value = Resource.Loading()
        recentSessionJob = viewModelScope.launch {
            val result = ecgRepository.getPatientSessions(patientId)
            if (result is Resource.Success && result.data != null) {
                val latest = result.data.maxByOrNull { it.startTimeUtc }
                _recentSession.value = Resource.Success(latest)
            } else {
                _recentSession.value = Resource.Error(result.message ?: LocaleHelper.string(R.string.l10n_err_latest_session))
            }
        }
    }

    // Fetches the live connection status and lead contact for the paired ESP32-C3 hardware.
    fun fetchDeviceStatus(deviceId: String) {
        deviceStatusJob?.cancel()
        _deviceStatus.value = Resource.Loading()
        deviceStatusJob = viewModelScope.launch {
            val result = ecgRepository.getDeviceStatus(deviceId)
            _deviceStatus.value = result
        }
    }

    // Fetches the count of unresolved clinical alerts.
    fun fetchActiveAlertsCount() {
        alertsCountJob?.cancel()
        _alertsCount.value = Resource.Loading()
        alertsCountJob = viewModelScope.launch {
            val result = ecgRepository.getActiveAlerts()
            if (result is Resource.Success && result.data != null) {
                val unacknowledged = result.data.count { !it.isAcknowledged }
                _alertsCount.value = Resource.Success(unacknowledged)
            } else {
                _alertsCount.value = Resource.Error(result.message ?: LocaleHelper.string(R.string.l10n_err_query_alerts))
            }
        }
    }

    // Cancels all in-flight jobs so stale responses cannot overwrite state after account change.
    fun cancelAll() {
        overviewJob?.cancel()
        recentSessionJob?.cancel()
        deviceStatusJob?.cancel()
        alertsCountJob?.cancel()
        linkedPatientJob?.cancel()
    }
}
