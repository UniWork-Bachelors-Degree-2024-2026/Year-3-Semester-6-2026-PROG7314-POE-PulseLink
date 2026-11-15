//Data Transfer Objects for ECG recording session lifecycle management, summary telemetry,
//and high-resolution sample retrieval for mobile visualisation.

using System.ComponentModel.DataAnnotations;

namespace PulseLinkAPI.Models.DTOs;

// Request payload to initialise a new remote ECG recording session.
public class StartSessionRequestDto
{
    [System.Text.Json.Serialization.JsonIgnore] 
    public DateTimeOffset? CaptureStartedAtUtc { get; set; }
    
    [System.Text.Json.Serialization.JsonIgnore] 
    public string? OperatorUid { get; set; }
    
    [System.Text.Json.Serialization.JsonIgnore] 
    public string? SiteId { get; set; }
    
    [System.Text.Json.Serialization.JsonIgnore] 
    public string? AuthorisationGrantId { get; set; }
    // Persist this UUID locally before starting; retries return the same session.
    public Guid? RecordingId { get; set; }
    [Required]
    public string PatientId { get; set; } = string.Empty;

    [Required]
    public string DeviceId { get; set; } = string.Empty;

    public string? PatientName { get; set; }
}

// Response payload returned when an ECG session is successfully initialised.
public class StartSessionResponseDto
{
    public string SessionId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string DeviceId { get; set; } = string.Empty;
    public DateTime StartTimeUtc { get; set; }
    public string Status { get; set; } = "Active";
}

// Request payload to conclude an active ECG recording session.
public class StopSessionRequestDto
{
    [Required]
    public string SessionId { get; set; } = string.Empty;
}

// Summary metrics for an ECG recording session utilised by history lists and doctor reviews.
public class EcgSessionSummaryDto
{
    public string SessionId { get; set; } = string.Empty;
    public string PatientId { get; set; } = string.Empty;
    public string? PatientName { get; set; }
    public string DeviceId { get; set; } = string.Empty;
    public DateTime StartTimeUtc { get; set; }
    public DateTime? EndTimeUtc { get; set; }
    public int DurationSeconds { get; set; }
    public int AverageBpm { get; set; }
    public int MinBpm { get; set; }
    public int MaxBpm { get; set; }
    public int TotalSamplesCount { get; set; }
    public string SignalQuality { get; set; } = "Unknown";
    public string Status { get; set; } = "Completed";
    public bool HasDoctorNotes { get; set; }
    public bool ResultsReleased { get; set; }
    public bool HasUnreleasedDrafts { get; set; }
    public bool ReportAvailable { get; set; }
}

// Full sample waveform response for rendering the oscilloscope strip chart on the mobile app.
public class EcgSessionSamplesResponseDto
{
    public bool HasSampleGaps { get; set; }
    public List<PulseLinkAPI.Models.Domain.RecordingBatchReference> Segments { get; set; } = [];
    public string SessionId { get; set; } = string.Empty;
    public int SampleRateHz { get; set; } = 250;
    public int TotalSamples { get; set; }
    public List<int> Samples { get; set; } = new();
    public DateTime RetrievedAtUtc { get; set; } = DateTime.UtcNow;
}

// Status and authenticated access contract for an authoritative ECG PDF report.
// State is one of: "Generating", "Ready", "Failed", "Unavailable".
public sealed record ReportStatusDto(
    string SessionId,
    string PatientId,
    string Audience,
    string State,
    bool ReportAvailable,
    string FileName,
    string DownloadPath,
    string StreamPath,
    string AuthenticatedEmailLinkPath,
    DateTimeOffset? GeneratedAtUtc = null,
    long? Revision = null,
    string? Message = null);
