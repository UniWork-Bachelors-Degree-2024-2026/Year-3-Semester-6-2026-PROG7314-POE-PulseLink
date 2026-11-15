using System.ComponentModel.DataAnnotations;
using PulseLinkAPI.Models.Domain;

namespace PulseLinkAPI.Models.DTOs;

public sealed class CompleteRecordingRequest
{
    [Range(1, 600)]
    public int ExpectedBatchCount { get; set; }

    [Range(1, 150000)]
    public int ExpectedSampleCount { get; set; }

    [Range(0, 86400)]
    public int? RecordedDurationSeconds { get; set; }
}

public sealed record RecordingReceipt(string SessionId, int Sequence, string Sha256,
    bool Duplicate, bool Persisted, int ReceivedBatchCount, int ReceivedSampleCount, long Revision);

public sealed record RecordingProgress(string SessionId, string Status, long Revision,
    int ReceivedBatchCount, int ReceivedSampleCount, int? ExpectedBatchCount, int? ExpectedSampleCount,
    IReadOnlyList<int> MissingSequences, bool HasSampleGaps, bool ClockContinuityUnverified,
    IReadOnlyList<RecordingBatchReference> Batches, IReadOnlyList<RecordingTimeGap> TimeGaps);

public sealed record RecordingTimeGap(int BeforeSequence, int AfterSequence, long? DurationMs, string Reason);

public sealed record RecordingBatchPage(string SessionId, IReadOnlyList<RecordingChunk> Batches, int? NextAfterSequence);
