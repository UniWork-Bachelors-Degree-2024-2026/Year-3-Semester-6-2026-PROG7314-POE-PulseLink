using System.Text.Json.Serialization;
using PulseLinkAPI.Models.DTOs;

namespace PulseLinkAPI.Models.Domain;

// Samples live in immutable chunks. Only references committed here are acknowledged.
public sealed class RecordingManifest
{
    public int SchemaVersion { get; set; } = 2;
    public string SessionId { get; set; } = "";
    public string PatientId { get; set; } = "";
    public string? PatientName { get; set; }
    public string DeviceId { get; set; } = "";
    public string? OperatorUid { get; set; }
    public string? SiteId { get; set; }
    public string? AuthorisationGrantId { get; set; }
    public DateTime StartTimeUtc { get; set; }
    public DateTime? EndTimeUtc { get; set; }
    public int SampleRateHz { get; set; } = 250;
    public string Status { get; set; } = "Active";
    public int? ExpectedBatchCount { get; set; }
    public int? ExpectedSampleCount { get; set; }
    public int? RecordedDurationSeconds { get; set; }
    public long Revision { get; set; }
    public Dictionary<string, RecordingBatchReference> Batches { get; set; } = [];
    public ReportStateRecord? StaffReport { get; set; }
}

public sealed class ReportStateRecord
{
    public string State { get; set; } = "Unavailable"; // "Generating", "Ready", "Failed", "Unavailable"
    public string FileName { get; set; } = "";
    public long ManifestRevision { get; set; }
    public int NoteCount { get; set; }
    public DateTimeOffset? UpdatedAtUtc { get; set; }
    public string? LastError { get; set; }
}

public sealed class RecordingBatchReference
{
    public string ChunkKey { get; set; } = "";
    public string Sha256 { get; set; } = "";
    public int Sequence { get; set; }
    public int FirstSampleIndex { get; set; }
    public int SampleCount { get; set; }
    public string BootId { get; set; } = "";
    public long Timestamp { get; set; }
    public DateTimeOffset? CapturedAtUtc { get; set; }
    public DateTime ReceivedAtUtc { get; set; }
    public bool LeadConnected { get; set; }
    public int EstimatedBpm { get; set; }
    public string SignalQuality { get; set; } = "Unknown";
    public long? LastSampleTimestampUs { get; set; }
    public long? FirstSampleTimestampUs { get; set; }
}

public sealed class RecordingChunk
{
    public string Sha256 { get; set; } = "";
    public IngestEcgPayloadDto Payload { get; set; } = new();
}

public sealed record PatientRecordingIndex(string SessionId);
