/*
 * Retrofit2 API service interface declaring all REST endpoints exposed by
 * the PulseLink ASP.NET Core C# backend deployed on Azure App Service.
 *
 * Reference List:
 * - Square, 2024. OkHttp: An HTTP & HTTP/2 client for Android and Java applications. [online] Available at: <https://square.github.io/okhttp/> [Accessed 21 September 2026].
 * - Square, 2024. Retrofit: A type-safe HTTP client for Android and Java. [online] Available at: <https://square.github.io/retrofit/> [Accessed 21 September 2026].
 */

package com.example.prog7314_poepart23.data.remote.api

import com.example.prog7314_poepart23.data.remote.models.AlertDto
import com.example.prog7314_poepart23.data.remote.models.CreateDoctorNoteDto
import com.example.prog7314_poepart23.data.remote.models.DeviceRegistrationDto
import com.example.prog7314_poepart23.data.remote.models.DeviceStatusDto
import com.example.prog7314_poepart23.data.remote.models.DoctorNoteDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSamplesResponseDto
import com.example.prog7314_poepart23.data.remote.models.EcgSessionSummaryDto
import com.example.prog7314_poepart23.data.remote.models.IngestEcgPayloadDto
import com.example.prog7314_poepart23.data.remote.models.StartSessionRequestDto
import com.example.prog7314_poepart23.data.remote.models.StartSessionResponseDto
import okhttp3.ResponseBody                       // (Square, 2024)
import retrofit2.Response                           // (Square, 2024)
import retrofit2.http.Body                         // (Square, 2024)
import retrofit2.http.GET                          // (Square, 2024)
import retrofit2.http.POST                         // (Square, 2024)
import retrofit2.http.Query
import retrofit2.http.Path                         // (Square, 2024)
import retrofit2.http.Streaming                    // (Square, 2024)
import retrofit2.http.PUT
import com.example.prog7314_poepart23.data.remote.models.*

interface PulseLinkApiService {
    @GET("api/notifications")
    suspend fun getClinicalNotifications(@Query("after") after: String? = null): Response<AccessPageDto<com.example.prog7314_poepart23.messaging.ClinicalNotificationDto>>
    @PUT("api/notifications/registration")
    suspend fun registerClinicalPushToken(@Body request: com.example.prog7314_poepart23.messaging.PushRegistrationDto): Response<Unit>

    @PUT("api/access/patients/{id}/login")
    suspend fun linkPatientLogin(@Path("id") id: String, @Body request: PatientLoginRequestDto): Response<PatientLoginLinkDto>
    @POST("api/access/sessions/{id}/release")
    suspend fun releasePatientResults(@Path("id") id: String): Response<PatientReleaseDto>
    @GET("api/patient/record")
    suspend fun getOwnPatientRecord(): Response<PatientProfileDto>
    @GET("api/patient/sessions")
    suspend fun getOwnPatientSessions(@Query("after") after: String? = null, @Query("limit") limit: Int = 20): Response<AccessPageDto<PatientSessionStatusDto>>
    @GET("api/patient/sessions/{id}/results")
    suspend fun getReleasedPatientResults(@Path("id") id: String): Response<PatientReleaseDto>
    @GET("api/patient/sessions/{id}/report")
    suspend fun getReleasedPatientReportStatus(@Path("id") id: String): Response<ReportStatusDto>
    @Streaming
    @GET("api/patient/sessions/{id}/report/pdf")
    suspend fun downloadReleasedPatientReportPdf(@Path("id") id: String): Response<ResponseBody>
    @POST("api/access/devices/{id}/authorisation")
    suspend fun approveNurseScreen(@Path("id") id: String, @Body request: ScreenApprovalRequestDto): Response<NurseGrantDto>
    @GET("api/access/devices/{id}/authorisation")
    suspend fun getNurseScreenAuthorisation(@Path("id") id: String): Response<NurseGrantDto>
    @GET("api/access/me") 
    suspend fun getAccessAccount(): Response<AccessAccountDto>
    
    @GET("api/access/patients")
    suspend fun getAssignedClinicalPatients(
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<AccessPageDto<DoctorPatientSummaryDto>>

    @GET("api/access/patients/{id}") 
    suspend fun getClinicalPatient(@Path("id") id: String): Response<ClinicalPatientDto>

    @GET("api/access/patients/{id}/care-plan") 
    suspend fun getClinicalCarePlan(@Path("id") id: String): Response<com.example.prog7314_poepart23.data.models.CarePlan>

    @PUT("api/access/patients/{id}/care-plan") 
    suspend fun saveClinicalCarePlan(@Path("id") id: String, @Body plan: com.example.prog7314_poepart23.data.models.CarePlan): Response<com.example.prog7314_poepart23.data.models.CarePlan>
    
    @POST("api/access/role-request") 
    suspend fun requestRole(@Body request: RoleRequestDto): Response<AccessAccountDto>

    
    @GET("api/access/sites") 
    suspend fun getAssignedSites(): Response<List<ClinicSiteDto>>
    
    @GET("api/admin/accounts") 
    suspend fun getAccessAccounts(@Query("after") after: String? = null): Response<AccessPageDto<AccessAccountDto>>
    
    @PUT("api/admin/accounts/{uid}/approval") 
    suspend fun approveAccount(@Path("uid") uid: String, @Body request: ApprovalRequestDto): Response<AccessAccountDto>
    
    @GET("api/admin/sites") 
    suspend fun getAdministrationSites(@Query("after") after: String? = null): Response<AccessPageDto<ClinicSiteDto>>
    

    @POST("api/admin/sites") 
    suspend fun createSite(@Body request: SiteRequestDto): Response<ClinicSiteDto>
    
    @GET("api/admin/patients") 
    suspend fun getAdministrationPatients(@Query("after") after: String? = null): Response<AccessPageDto<ClinicalPatientDto>>
    
    @POST("api/access/patients") 
    suspend fun registerClinicalPatient(@Body request: PatientRegistrationDto): Response<ClinicalPatientDto>
    
    @PUT("api/admin/patients/{id}/assignment") 
    suspend fun assignClinicalPatient(@Path("id") id: String, @Body request: PatientAssignmentDto): Response<ClinicalPatientDto>
    
    @PUT("api/admin/devices/{id}/site") 
    suspend fun assignDeviceSite(@Path("id") id: String, @Body request: DeviceSiteRequestDto): Response<Map<String, Any>>

    // 1. ECG Telemetry Ingestion
    
    // Ingests a 250 Hz analogue ECG telemetry batch and estimated BPM from hardware/client. // (Square, 2024)
    @POST("api/ecg/ingest")
    suspend fun ingestTelemetry(
        @Body payload: IngestEcgPayloadDto
    ): Response<Map<String, Any>>


    // 2. ECG session lifecycle and sample retrieval

    // Initialises a new remote ECG recording session for a registered patient. // (Square, 2024)
    @POST("api/ecg/sessions/start")
    suspend fun startSession(
        @Body request: StartSessionRequestDto
    ): Response<StartSessionResponseDto>

    
    // Concludes an active recording session and calculates final session statistics. // (Square, 2024)
    @POST("api/ecg/sessions/{id}/stop")
    suspend fun stopSession(
        @Path("id") sessionId: String,
        @Query("recordedDurationSeconds") recordedDurationSeconds: Int? = null
    ): Response<EcgSessionSummaryDto>

    @POST("api/ecg/sessions/{id}/complete")
    suspend fun completeRecording(
        @Path("id") sessionId: String,
        @Body request: CompleteRecordingRequestDto
    ): Response<RecordingProgressDto>

    @GET("api/ecg/sessions/{id}/progress")
    suspend fun getRecordingProgress(
        @Path("id") sessionId: String
    ): Response<RecordingProgressDto>

    
    @POST("api/ecg/sessions/{id}/recording-state")
    suspend fun setRecordingState(@Path("id") sessionId: String, @Query("paused") paused: Boolean): Response<Unit>

    @POST("api/ecg/sessions/{id}/discard")
    suspend fun discardSession(@Path("id") sessionId: String): Response<Unit>

    // Retrieves all historical ECG monitoring sessions for a specific patient. // (Square, 2024)
    @GET("api/ecg/sessions/patient/{patientId}")
    suspend fun getPatientSessions(
        @Path("patientId") patientId: String
    ): Response<List<EcgSessionSummaryDto>>

    
    // Retrieves high-frequency 250 Hz sample points for oscilloscope waveform rendering. // (Square, 2024)
    @GET("api/ecg/sessions/{id}/samples")
    suspend fun getSessionSamples(
        @Path("id") sessionId: String
    ): Response<EcgSessionSamplesResponseDto>

    
    // 3. ESP32 Hardware device registration and status
    
    // Registers and binds an ESP32-C3 hardware monitor to a patient account. // (Square, 2024)
    @POST("api/devices/register")
    suspend fun registerDevice(
        @Body registration: DeviceRegistrationDto
    ): Response<Map<String, Any>>

    
    // Retrieves live connection, lead detachment, and health telemetry for a hardware device. // (Square, 2024)
    @GET("api/devices/{deviceId}/status")
    suspend fun getDeviceStatus(
        @Path("deviceId") deviceId: String
    ): Response<DeviceStatusDto>

    // 4. System telemetry alerts
    
    // Retrieves all active clinical alerts (e.g. Lead detachment or abnormal estimated BPM). // (Square, 2024)
    @GET("api/alerts")
    suspend fun getActiveAlerts(): Response<List<AlertDto>>
    
    // 5. Doctor clinical notes, dashboard, assigned patients, visits, and evaluation
    
    @GET("api/doctor/dashboard")
    suspend fun getDoctorDashboard(): Response<DoctorDashboardDto>

    @GET("api/doctor/patients")
    suspend fun getDoctorAssignedPatients(
        @Query("after") after: String? = null,
        @Query("limit") limit: Int = 50
    ): Response<AccessPageDto<DoctorPatientSummaryDto>>

    @GET("api/doctor/patients/{patientId}/chart")
    suspend fun getDoctorPatientChart(
        @Path("patientId") patientId: String
    ): Response<PatientChartDto>

    @GET("api/doctor/patients/{patientId}/visits")
    suspend fun getDoctorPatientVisits(
        @Path("patientId") patientId: String
    ): Response<List<ClinicalVisitDto>>

    @POST("api/doctor/patients/{patientId}/visits")
    suspend fun createDoctorPatientVisit(
        @Path("patientId") patientId: String,
        @Body request: CreateClinicalVisitDto
    ): Response<ClinicalVisitDto>

    @GET("api/doctor/sessions/{sessionId}/review")
    suspend fun getDoctorSessionReview(
        @Path("sessionId") sessionId: String
    ): Response<DoctorSessionReviewDto>

    @POST("api/doctor/sessions/{sessionId}/release")
    suspend fun releaseDoctorSessionResults(
        @Path("sessionId") sessionId: String
    ): Response<PatientReleaseDto>

    // Attaches clinical impression and recommendations to a recorded ECG session. // (Square, 2024)
    @POST("api/doctor/notes")
    suspend fun addDoctorNote(
        @Body request: CreateDoctorNoteDto
    ): Response<DoctorNoteDto>

    
    // Retrieves all clinical assessment notes associated with an ECG recording session. // (Square, 2024)
    @GET("api/doctor/notes/session/{sessionId}")
    suspend fun getDoctorNotes(
        @Path("sessionId") sessionId: String
    ): Response<List<DoctorNoteDto>>

    // 6. Clinical ECG PDF report generation and private Azure blob stream

    @GET("api/reports/session/{sessionId}/status")
    suspend fun getSessionReportStatus(
        @Path("sessionId") sessionId: String,
        @Query("generateIfMissing") generateIfMissing: Boolean = true
    ): Response<ReportStatusDto>
    
    // Downloads the authoritative single-lead ECG PDF report generated for an ECG session. // (Square, 2024)
    @Streaming
    @GET("api/reports/session/{sessionId}/pdf")
    suspend fun downloadSessionReportPdf(
        @Path("sessionId") sessionId: String,
        @Query("forceRegenerate") forceRegenerate: Boolean = false
    ): Response<ResponseBody>

    // 7. Verified public facilities directory (Gqeberha / Port Elizabeth, Eastern Cape)

    @GET("api/facilities")
    suspend fun getPublicFacilities(
        @Query("query") query: String? = null,
        @Query("type") type: String? = null
    ): Response<PublicFacilityDirectoryResponse>

    @GET("api/facilities/{facilityId}")
    suspend fun getPublicFacilityById(
        @Path("facilityId") facilityId: String
    ): Response<PublicFacilityDto>
}
