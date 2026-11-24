/*
 * Single source of truth repository managing ECG recording session lifecycle,
 * live 250 Hz analogue sample acquisition, device connection telemetry, and clinical alerts.
 *
 * Reference List:
 * - Google Developers, 2024. Guide to app architecture. [online] Available at: <https://developer.android.com/topic/architecture> [Accessed 21 September 2026].
 * - JetBrains, 2024. Coroutines guide - Kotlin Programming Language. [online] Available at: <https://kotlinlang.org/docs/coroutines-guide.html> [Accessed 21 September 2026].
 * - Square, 2024. Retrofit: A type-safe HTTP client for Android and Java. [online] Available at: <https://square.github.io/retrofit/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.repository

import com.example.prog7314_poepart23.R
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiClient
import com.example.prog7314_poepart23.data.remote.api.PulseLinkApiService
import com.example.prog7314_poepart23.data.remote.models.AlertDto
import com.example.prog7314_poepart23.data.remote.models.CreateDoctorNoteDto
import com.example.prog7314_poepart23.data.remote.models.DeviceRegistrationDto
import com.example.prog7314_poepart23.data.remote.models.DeviceStatusDto
import com.example.prog7314_poepart23.data.remote.models.DoctorNoteDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSamplesResponseDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.remote.models.IngestEcgPayloadDto
import com.example.prog7314_poepart23.data.remote.models.ReportStatusDto
import com.example.prog7314_poepart23.data.remote.models.StartSessionRequestDto
import com.example.prog7314_poepart23.data.remote.models.StartSessionResponseDto
import com.example.prog7314_poepart23.utils.LocaleHelper
import com.example.prog7314_poepart23.utils.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers               // (JetBrains, 2024)
import kotlinx.coroutines.withContext               // (JetBrains, 2024)
import okhttp3.ResponseBody                         // (Square, 2024)
import retrofit2.Response

class EcgRepository( // (Google Developers, 2024)
    private val apiService: PulseLinkApiService = PulseLinkApiClient.apiService,
    private val refreshToken: suspend () -> String? = {
        PulseLinkApiClient.getAuthToken()?.takeIf { it.isNotBlank() }
            ?: try { UserRepository().refreshAuthToken() } catch (_: Exception) { null }
    },
    private val currentUid: () -> String? = {
        try { UserRepository().currentUser?.uid } catch (_: Exception) { null }
    }
) {

    private suspend fun <T> executeAuthenticated(
        defaultError: () -> String,
        networkError: () -> String,
        allow404AsSuccessUnit: Boolean = false,
        treatSuccessAsUnit: Boolean = false,
        request: suspend () -> Response<T>
    ): Resource<T> = withContext(Dispatchers.IO) {
        val startingUid = currentUid()
        try {
            val token = refreshToken()
            if (startingUid != null) {
                val nowUid = currentUid()
                if (nowUid != startingUid || token.isNullOrBlank()) {
                    return@withContext Resource.Error(LocaleHelper.stringOr(R.string.rt_err_session_changed, "Session changed. Please sign in again."))
                }
            }
            val response = request()
            if (startingUid != null && currentUid() != startingUid) {
                return@withContext Resource.Error(LocaleHelper.stringOr(R.string.rt_err_session_changed, "Session changed. Please sign in again."))
            }
            if ((allow404AsSuccessUnit && (response.isSuccessful || response.code() == 404)) ||
                (treatSuccessAsUnit && response.isSuccessful)
            ) {
                @Suppress("UNCHECKED_CAST")
                return@withContext Resource.Success(Unit as T)
            }
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Resource.Success(body)
            } else {
                val errorText = try { response.errorBody()?.string()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                Resource.Error(errorText ?: defaultError())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Resource.Error(e.localizedMessage ?: networkError())
        }
    }

    // Initialises a new remote ECG monitoring session. // (JetBrains, 2024)
    suspend fun startSession(
        patientId: String,
        deviceId: String,
        patientName: String? = null
    ): Resource<StartSessionResponseDto> = executeAuthenticated(
        defaultError = { LocaleHelper.string(R.string.l10n_err_start_session) },
        networkError = { LocaleHelper.string(R.string.l10n_err_net_start) }
    ) {
        apiService.startSession(
            StartSessionRequestDto(
                patientId = patientId,
                deviceId = deviceId,
                patientName = patientName
            )
        )
    }

    // Concludes an active ECG recording session and calculates summary metrics.
    suspend fun stopSession(sessionId: String, recordedDurationSeconds: Int? = null): Resource<EcgSessionSummaryDto> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_stop_session) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_stop) }
        ) {
            apiService.stopSession(sessionId, recordedDurationSeconds)
        }

    suspend fun setRecordingState(sessionId: String, paused: Boolean): Resource<Unit> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.live_state_sync_failed) },
            networkError = { LocaleHelper.string(R.string.live_state_sync_failed) },
            treatSuccessAsUnit = true
        ) {
            apiService.setRecordingState(sessionId, paused)
        }

    suspend fun discardSession(sessionId: String): Resource<Unit> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.live_discard_failed) },
            networkError = { LocaleHelper.string(R.string.live_discard_failed) },
            allow404AsSuccessUnit = true
        ) {
            apiService.discardSession(sessionId)
        }

    suspend fun permitSessionUpload(sessionId: String): Resource<Unit> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_start_session) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_start) },
            treatSuccessAsUnit = true
        ) {
            apiService.permitSessionUpload(sessionId)
        }

    suspend fun completeRecording(
        sessionId: String,
        expectedBatchCount: Int,
        expectedSampleCount: Int,
        recordedDurationSeconds: Int? = null
    ): Resource<com.example.prog7314_poepart23.data.remote.models.RecordingProgressDto> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_stop_session) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_stop) }
        ) {
            apiService.completeRecording(
                sessionId,
                com.example.prog7314_poepart23.data.remote.models.CompleteRecordingRequestDto(
                    expectedBatchCount = expectedBatchCount,
                    expectedSampleCount = expectedSampleCount,
                    recordedDurationSeconds = recordedDurationSeconds
                )
            )
        }

    // Retrieves all historical ECG sessions for a given patient.
    suspend fun getPatientSessions(patientId: String): Resource<List<EcgSessionSummaryDto>> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_history) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_history) }
        ) {
            apiService.getPatientSessions(patientId)
        }

    // Retrieves high-frequency 250 Hz sample points for oscilloscope waveform rendering.
    suspend fun getSessionSamples(sessionId: String): Resource<EcgSessionSamplesResponseDto> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_samples) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_samples) }
        ) {
            apiService.getSessionSamples(sessionId)
        }

    // Retrieves real-time connection, lead detachment, and health telemetry for a hardware device.
    suspend fun getDeviceStatus(deviceId: String): Resource<DeviceStatusDto> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_device_status) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_device_status) }
        ) {
            apiService.getDeviceStatus(deviceId)
        }

    // Registers and links an ESP32-C3 hardware monitor to a patient account.
    suspend fun registerDevice(
        deviceId: String,
        patientId: String,
        deviceName: String = "PulseLink AD8232 Monitor",
        macAddress: String? = null
    ): Resource<Map<String, Any>> = executeAuthenticated(
        defaultError = { LocaleHelper.string(R.string.l10n_err_register_device) },
        networkError = { LocaleHelper.string(R.string.l10n_err_net_register_device) }
    ) {
        apiService.registerDevice(
            DeviceRegistrationDto(
                deviceId = deviceId,
                patientId = patientId,
                deviceName = deviceName,
                macAddress = macAddress
            )
        )
    }

    // Retrieves all active clinical alerts (e.g. Lead detachment or abnormal estimated BPM).
    suspend fun getActiveAlerts(): Resource<List<AlertDto>> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_alerts) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_alerts) }
        ) {
            apiService.getActiveAlerts()
        }

    // Ingests a 250 Hz analogue ECG telemetry batch from mobile client or simulated feed.
    suspend fun ingestTelemetry(payload: IngestEcgPayloadDto): Resource<Map<String, Any>> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_ingest) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_ingest) }
        ) {
            apiService.ingestTelemetry(payload)
        }

    // Retrieves all clinical assessment notes associated with an ECG recording session.
    suspend fun getDoctorNotes(sessionId: String): Resource<List<DoctorNoteDto>> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_notes) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_notes) }
        ) {
            apiService.getDoctorNotes(sessionId)
        }

    // Attaches clinical impression and recommendations to a recorded ECG session.
    suspend fun addDoctorNote(note: CreateDoctorNoteDto): Resource<DoctorNoteDto> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_submit_note) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_submit_note) }
        ) {
            apiService.addDoctorNote(note)
        }

    suspend fun getSessionReportStatus(sessionId: String, generateIfMissing: Boolean = true): Resource<ReportStatusDto> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_pdf) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_pdf) }
        ) {
            apiService.getSessionReportStatus(sessionId, generateIfMissing)
        }

    // Downloads the authoritative single-lead ECG PDF report generated for a session.
    suspend fun downloadSessionReportPdf(sessionId: String, forceRegenerate: Boolean = false): Resource<ResponseBody> =
        executeAuthenticated(
            defaultError = { LocaleHelper.string(R.string.l10n_err_pdf) },
            networkError = { LocaleHelper.string(R.string.l10n_err_net_pdf) }
        ) {
            apiService.downloadSessionReportPdf(sessionId, forceRegenerate)
        }

    // Offline Data Capture & Sync Helpers

    // Caches an ECG recording session locally in SQLite when offline.
    suspend fun cacheOfflineSession(session: com.example.prog7314_poepart23.data.local.models.CachedSession, context: android.content.Context) = withContext(Dispatchers.IO) {
        val db = com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(context)
        db.insertSession(session)
        com.example.prog7314_poepart23.sync.SyncScheduler.triggerImmediateSync(context)
    }

    // Caches a high-frequency 250 Hz AD8232 ECG sample batch locally in SQLite when offline.
    suspend fun cacheOfflineSampleBatch(batch: com.example.prog7314_poepart23.data.local.models.CachedSampleBatch, context: android.content.Context) = withContext(Dispatchers.IO) {
        val db = com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(context)
        db.insertSampleBatch(batch)
        com.example.prog7314_poepart23.sync.SyncScheduler.triggerImmediateSync(context)
    }

    // Returns the total number of pending offline ECG sessions and sample batches for one patient.
    suspend fun getOfflinePendingCount(context: android.content.Context, patientId: String): Int = withContext(Dispatchers.IO) {
        val db = com.example.prog7314_poepart23.data.local.OfflineEcgDatabase.getInstance(context)
        db.getPendingSyncCount(patientId)
    }
}
