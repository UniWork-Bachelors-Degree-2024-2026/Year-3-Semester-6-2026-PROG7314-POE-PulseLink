// Data Transfer Objects for the doctor clinical chart, assigned patient roster,
// dashboard counters, prior clinical visits, session review state, and draft/release tracking.

using System.ComponentModel.DataAnnotations;
using PulseLinkAPI.Access;

namespace PulseLinkAPI.Models.DTOs;

public sealed class DoctorPatientSummaryDto
{
    public string PatientId { get; set; } = string.Empty;
    public string FullName { get; set; } = string.Empty;
    public DateOnly? DateOfBirth { get; set; }
    public string SiteId { get; set; } = string.Empty;
    public string SiteName { get; set; } = string.Empty;
    public string PrimaryDoctorUid { get; set; } = string.Empty;
    public List<string> AdditionalDoctorUids { get; set; } = [];
    public bool HasLinkedLogin { get; set; }
    public int Version { get; set; }
    public int CompletedSessionsCount { get; set; }
    public int AwaitingReviewCount { get; set; }
    public int DraftAssessedCount { get; set; }
    public int ReleasedSessionsCount { get; set; }
    public int ReportReadyCount { get; set; }
    public int PriorVisitsCount { get; set; }
    public int ActiveAlertsCount { get; set; }
    public bool FollowUpRequired { get; set; }
    public string? FollowUpInstructions { get; set; }
    public int? FollowUpInDays { get; set; }
    public string? LatestCompletedSessionId { get; set; }
    public DateTime? LatestRecordingAtUtc { get; set; }
    public int? LatestAverageBpm { get; set; }
    public int? LatestDurationSeconds { get; set; }
    public string? LatestSignalQuality { get; set; }
    public string ReviewStatus { get; set; } = "NoCompletedRecordings";
}

public sealed class DoctorQueueItemDto
{
    public string SessionId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string PatientName { get; set; } = string.Empty;
    public string SiteId { get; set; } = string.Empty;
    public string SiteName { get; set; } = string.Empty;
    public string? OperatorUid { get; set; }
    public DateTime StartTimeUtc { get; set; }
    public DateTime? EndTimeUtc { get; set; }
    public int DurationSeconds { get; set; }
    public int AverageBpm { get; set; }
    public string SignalQuality { get; set; } = "Unknown";
    public bool HasDoctorNotes { get; set; }
    public bool ResultsReleased { get; set; }
    public bool HasUnreleasedDrafts { get; set; }
    public bool ReportAvailable { get; set; }
    public bool FollowUpRequired { get; set; }
    public string ReviewState { get; set; } = "AwaitingReview";
}

public sealed class DoctorDashboardDto
{
    public int AssignedPatientsCount { get; set; }
    public int CompletedSessionsCount { get; set; }
    public int AwaitingReviewCount { get; set; }
    public int DraftAssessedCount { get; set; }
    public int PendingReleaseCount { get; set; }
    public int ReleasedCount { get; set; }
    public int CompletedReportsReadyCount { get; set; }
    public int FollowUpRequiredCount { get; set; }
    public int CriticalAlertsCount { get; set; }
    public int UnacknowledgedAlertsCount { get; set; }
    public List<DoctorPatientSummaryDto> AssignedPatients { get; set; } = [];
    public List<DoctorQueueItemDto> CompletedSessionQueue { get; set; } = [];
}

public sealed class CreateClinicalVisitDto
{
    [StringLength(128)]
    public string? SessionId { get; set; }

    [Required, RegularExpression("^(ClinicFollowUp|RemoteReview|InitialConsultation)$")]
    public string VisitType { get; set; } = "RemoteReview";

    [StringLength(500)]
    public string ChiefComplaint { get; set; } = string.Empty;

    [Required, StringLength(2000, MinimumLength = 2)]
    public string Assessment { get; set; } = string.Empty;

    [StringLength(2000)]
    public string? Recommendations { get; set; }

    [MaxLength(30)]
    public List<MedicationEntry> Prescriptions { get; set; } = [];

    public bool FollowUpRequired { get; set; }

    [StringLength(1000)]
    public string? FollowUpInstructions { get; set; }

    [Range(0, 365)]
    public int? FollowUpInDays { get; set; }

    // Server-controlled fields: any client-supplied values are ignored and overwritten by the server.
    public string DoctorId { get; set; } = string.Empty;
    public string DoctorName { get; set; } = string.Empty;
    public DateTimeOffset? RecordedAtUtc { get; set; }
}

public sealed class ClinicalVisitDto
{
    public string VisitId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string SiteId { get; set; } = string.Empty;
    public string? SessionId { get; set; }
    public string VisitType { get; set; } = "RemoteReview";
    public string ChiefComplaint { get; set; } = string.Empty;
    public string Assessment { get; set; } = string.Empty;
    public string? Recommendations { get; set; }
    public List<MedicationEntry> Prescriptions { get; set; } = [];
    public bool FollowUpRequired { get; set; }
    public string? FollowUpInstructions { get; set; }
    public int? FollowUpInDays { get; set; }
    public string DoctorId { get; set; } = string.Empty;
    public string DoctorName { get; set; } = string.Empty;
    public DateTimeOffset RecordedAtUtc { get; set; }
}

public sealed class SessionReleaseStateDto
{
    public bool ResultsReleased { get; set; }
    public DateTimeOffset? ReleasedAtUtc { get; set; }
    public string? ReleasedByUid { get; set; }
    public int ReleasedNoteCount { get; set; }
    public int DraftNoteCount { get; set; }
    public bool HasUnreleasedDrafts { get; set; }
    public string ReportState { get; set; } = "Unavailable";
    public bool ReportAvailable { get; set; }
}

public sealed class DoctorSessionReviewSummaryDto
{
    public EcgSessionSummaryDto Session { get; set; } = new();
    public string? SiteId { get; set; }
    public string? OperatorUid { get; set; }
    public ReportStatusDto? StaffReportStatus { get; set; }
    public List<DoctorNoteDto> Notes { get; set; } = [];
    public SessionReleaseStateDto ReleaseState { get; set; } = new();
    public string ReviewState { get; set; } = "AwaitingReview";
}

public sealed class DoctorSessionReviewDto
{
    public EcgSessionSummaryDto Session { get; set; } = new();
    public string PatientId { get; set; } = string.Empty;
    public string PatientName { get; set; } = string.Empty;
    public DateOnly? DateOfBirth { get; set; }
    public string SiteId { get; set; } = string.Empty;
    public string SiteName { get; set; } = string.Empty;
    public string? OperatorUid { get; set; }
    public ReportStatusDto StaffReportStatus { get; set; } = default!;
    public List<DoctorNoteDto> DraftNotes { get; set; } = [];
    public CarePlan DraftCarePlan { get; set; } = new();
    public SessionReleaseStateDto ReleaseState { get; set; } = new();
    public string ReviewState { get; set; } = "AwaitingReview";
}

public sealed class PatientChartDto
{
    public DoctorPatientSummaryDto Patient { get; set; } = new();
    public CarePlan CarePlan { get; set; } = new();
    public List<ClinicalVisitDto> PriorVisits { get; set; } = [];
    public List<DoctorSessionReviewSummaryDto> CompletedRecordings { get; set; } = [];
    public List<EcgSessionSummaryDto> RecordingHistory { get; set; } = [];
    public List<AlertDto> ActiveAlerts { get; set; } = [];
}
