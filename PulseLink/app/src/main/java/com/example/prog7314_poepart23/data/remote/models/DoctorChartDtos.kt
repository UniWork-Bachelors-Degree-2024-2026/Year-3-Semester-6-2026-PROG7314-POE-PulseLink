package com.example.prog7314_poepart23.data.remote.models

import com.example.prog7314_poepart23.data.models.CarePlan
import com.example.prog7314_poepart23.data.models.MedicationItem

data class DoctorPatientSummaryDto(
    val patientId: String = "",
    val fullName: String = "",
    val dateOfBirth: String? = null,
    val siteId: String = "",
    val siteName: String = "",
    val primaryDoctorUid: String = "",
    val additionalDoctorUids: List<String> = emptyList(),
    val hasLinkedLogin: Boolean = false,
    val version: Int = 0,
    val completedSessionsCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val draftAssessedCount: Int = 0,
    val releasedSessionsCount: Int = 0,
    val reportReadyCount: Int = 0,
    val priorVisitsCount: Int = 0,
    val activeAlertsCount: Int = 0,
    val followUpRequired: Boolean = false,
    val followUpInstructions: String? = null,
    val followUpInDays: Int? = null,
    val latestCompletedSessionId: String? = null,
    val latestRecordingAtUtc: String? = null,
    val latestAverageBpm: Int? = null,
    val latestDurationSeconds: Int? = null,
    val latestSignalQuality: String? = null,
    val reviewStatus: String = "NoCompletedRecordings"
)

data class DoctorQueueItemDto(
    val sessionId: String = "",
    val patientId: String = "",
    val patientName: String = "",
    val siteId: String = "",
    val siteName: String = "",
    val operatorUid: String? = null,
    val startTimeUtc: String = "",
    val endTimeUtc: String? = null,
    val durationSeconds: Int = 0,
    val averageBpm: Int = 0,
    val signalQuality: String = "Unknown",
    val hasDoctorNotes: Boolean = false,
    val resultsReleased: Boolean = false,
    val hasUnreleasedDrafts: Boolean = false,
    val reportAvailable: Boolean = false,
    val followUpRequired: Boolean = false,
    val reviewState: String = "AwaitingReview"
)

data class DoctorDashboardDto(
    val assignedPatientsCount: Int = 0,
    val completedSessionsCount: Int = 0,
    val awaitingReviewCount: Int = 0,
    val draftAssessedCount: Int = 0,
    val pendingReleaseCount: Int = 0,
    val releasedCount: Int = 0,
    val completedReportsReadyCount: Int = 0,
    val followUpRequiredCount: Int = 0,
    val criticalAlertsCount: Int = 0,
    val unacknowledgedAlertsCount: Int = 0,
    val assignedPatients: List<DoctorPatientSummaryDto> = emptyList(),
    val completedSessionQueue: List<DoctorQueueItemDto> = emptyList()
)

data class CreateClinicalVisitDto(
    val sessionId: String? = null,
    val visitType: String = "RemoteReview",
    val chiefComplaint: String = "",
    val assessment: String,
    val recommendations: String? = null,
    val prescriptions: List<MedicationItem> = emptyList(),
    val followUpRequired: Boolean = false,
    val followUpInstructions: String? = null,
    val followUpInDays: Int? = null
)

data class ClinicalVisitDto(
    val visitId: String = "",
    val patientId: String = "",
    val siteId: String = "",
    val sessionId: String? = null,
    val visitType: String = "RemoteReview",
    val chiefComplaint: String = "",
    val assessment: String = "",
    val recommendations: String? = null,
    val prescriptions: List<MedicationItem> = emptyList(),
    val followUpRequired: Boolean = false,
    val followUpInstructions: String? = null,
    val followUpInDays: Int? = null,
    val doctorId: String = "",
    val doctorName: String = "",
    val recordedAtUtc: String = ""
)

data class SessionReleaseStateDto(
    val resultsReleased: Boolean = false,
    val releasedAtUtc: String? = null,
    val releasedByUid: String? = null,
    val releasedNoteCount: Int = 0,
    val draftNoteCount: Int = 0,
    val hasUnreleasedDrafts: Boolean = false,
    val reportState: String = "Unavailable",
    val reportAvailable: Boolean = false
)

data class DoctorSessionReviewSummaryDto(
    val session: EcgSessionSummaryDto = EcgSessionSummaryDto(),
    val siteId: String? = null,
    val operatorUid: String? = null,
    val staffReportStatus: ReportStatusDto? = null,
    val notes: List<DoctorNoteDto> = emptyList(),
    val releaseState: SessionReleaseStateDto = SessionReleaseStateDto(),
    val reviewState: String = "AwaitingReview"
)

data class DoctorSessionReviewDto(
    val session: EcgSessionSummaryDto = EcgSessionSummaryDto(),
    val patientId: String = "",
    val patientName: String = "",
    val dateOfBirth: String? = null,
    val siteId: String = "",
    val siteName: String = "",
    val operatorUid: String? = null,
    val staffReportStatus: ReportStatusDto = ReportStatusDto(sessionId = ""),
    val draftNotes: List<DoctorNoteDto> = emptyList(),
    val draftCarePlan: CarePlan = CarePlan(),
    val releaseState: SessionReleaseStateDto = SessionReleaseStateDto(),
    val reviewState: String = "AwaitingReview"
)

data class PatientChartDto(
    val patient: DoctorPatientSummaryDto = DoctorPatientSummaryDto(),
    val carePlan: CarePlan = CarePlan(),
    val priorVisits: List<ClinicalVisitDto> = emptyList(),
    val completedRecordings: List<DoctorSessionReviewSummaryDto> = emptyList(),
    val recordingHistory: List<EcgSessionSummaryDto> = emptyList(),
    val activeAlerts: List<AlertDto> = emptyList()
)
