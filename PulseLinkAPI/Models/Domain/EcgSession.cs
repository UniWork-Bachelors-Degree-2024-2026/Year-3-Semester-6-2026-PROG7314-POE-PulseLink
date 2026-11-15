//Core domain entity representing an individual ECG monitoring session recorded by a patient.

using PulseLinkAPI.Models.DTOs;

namespace PulseLinkAPI.Models.Domain;

// Domain model encapsulating ECG recording session metadata and aggregate telemetry.
public class EcgSession
{
    public string SessionId { get; set; } = Guid.NewGuid().ToString();
    public string PatientId { get; set; } = string.Empty;
    public string? PatientName { get; set; }            
    public string DeviceId { get; set; } = string.Empty;
    public string? SiteId { get; set; }
    public string? OperatorUid { get; set; }
    public DateTime StartTimeUtc { get; set; } = DateTime.UtcNow;
    public DateTime? EndTimeUtc { get; set; }
    public int DurationSeconds { get; set; }
    public int SampleRateHz { get; set; } = 250;
    public string Status { get; set; } = "Active"; // "Active", "Completed", "Aborted"
    public string SignalQuality { get; set; } = "Unknown";
    public int TotalSamplesCount { get; set; }
    public int RollingSumBpm { get; set; }
    public int BpmReadingCount { get; set; }
    public int AverageBpm => BpmReadingCount > 0 ? RollingSumBpm / BpmReadingCount : 0;
    public int MinBpm { get; set; } = int.MaxValue;
    public int MaxBpm { get; set; } = 0;
    public List<int> SampleBuffer { get; set; } = new();
    public List<byte> SampleFlags { get; set; } = new();
    public List<RecordingBatchReference> Segments { get; set; } = new();
    public List<RecordingTimeGap> TimeGaps { get; set; } = new();
    public bool HasSampleGaps { get; set; }
    public bool ClockContinuityUnverified { get; set; }
    public bool IsReleasedPatientCopy { get; set; }
    public DateTimeOffset? ReleasedAtUtc { get; set; }
    public string? ReleasedCareInstructions { get; set; }
    public string? ReportPdfBlobUrl { get; set; }
}
