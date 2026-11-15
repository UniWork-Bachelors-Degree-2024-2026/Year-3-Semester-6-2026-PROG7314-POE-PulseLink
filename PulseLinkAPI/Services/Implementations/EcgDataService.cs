using System.ComponentModel.DataAnnotations;
using System.Security.Cryptography;
using System.Text.Json;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.Domain;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Services.Implementations;

// Firebase is authoritative. No process-local collection controls recording identity or recovery.
public sealed class EcgDataService(IAccessStore store, IPdfReportGenerator pdf,
    IBlobStorageService blobs, ILogger<EcgDataService> logger) : IEcgDataService
{
    public const string RecordingRoot = "recordingsV2";
    public const string ChunkRoot = "recordingChunksV2";
    public static string StaffReportFileName(string sessionId) => $"PulseLink_Report_{Key(sessionId)}.pdf";
    public static string ReleasedPatientReportFileName(string sessionId) => $"PulseLink_Released_Report_{Key(sessionId)}.pdf";

    private static string Key(string id)
    {
        FirebaseAccessStore.ValidatePath(id);
        if (id.Contains('/')) throw new AccessProblem(400, "Invalid identifier.");
        return id;
    }
    private static string Path(string id) => $"{RecordingRoot}/{Key(id)}";
    private Task<StoredValue<RecordingManifest>> Read(string id) => store.ReadAsync<RecordingManifest>(Path(id));
    private static string SequenceKey(int sequence) => $"B{sequence:D6}";

    // Firebase ETag compare-and-set handles concurrent requests across API instances.
    private async Task<RecordingManifest?> Change(string id, Func<RecordingManifest, bool> change)
    {
        for (var attempt = 0; attempt < 8; attempt++)
        {
            var row = await Read(id);
            if (row.Value == null)
                return null;

            if (!change(row.Value))

                return row.Value;
            row.Value.Revision++;

            try
            {
                await store.WriteAsync(Path(id), row.Value, row.ETag); return row.Value;
            }
            catch (AccessProblem e) when (e.Status == 409) { }
        }
        throw new AccessProblem(503, "The recording is busy. Retry the same request.");
    }

    public async Task<SessionAccessContext?> GetSessionAccessContextAsync(string id)
    {
        var m = (await Read(id)).Value;
        return m == null ? null : new(m.PatientId, m.DeviceId, m.Status);
    }

    public async Task<StartSessionResponseDto> StartSessionAsync(StartSessionRequestDto request)
    {
        Key(request.PatientId); Key(request.DeviceId);
        var id = $"SESSION-{(request.RecordingId ?? Guid.NewGuid()):N}";

        var row = await Read(id);
        var manifest = row.Value;

        if (manifest == null)
        {
            manifest = new RecordingManifest
            {
                SessionId = id,
                PatientId = request.PatientId,
                PatientName = request.PatientName,
                DeviceId = request.DeviceId,
                StartTimeUtc = request.CaptureStartedAtUtc?.UtcDateTime ?? DateTime.UtcNow,
                OperatorUid = request.OperatorUid,
                SiteId = request.SiteId,
                AuthorisationGrantId = request.AuthorisationGrantId,
                Revision = 1
            };
            // Index before creation: an interrupted start leaves an invisible index entry, repaired by retry.

            await store.WriteAsync($"patientRecordingsV2/{Key(request.PatientId)}/{id}", new PatientRecordingIndex(id));
            try
            {
                await store.WriteAsync(Path(id), manifest, row.ETag);
            }
            catch (AccessProblem e) when (e.Status == 409) { manifest = (await Read(id)).Value!; }
        }
        if (manifest.PatientId != request.PatientId || manifest.DeviceId != request.DeviceId)
            throw new AccessProblem(409, "This recording identity is already bound to another patient or device.");
        if (request.AuthorisationGrantId != null && (manifest.AuthorisationGrantId != request.AuthorisationGrantId ||
            manifest.OperatorUid != request.OperatorUid || manifest.StartTimeUtc != request.CaptureStartedAtUtc?.UtcDateTime))
            throw new AccessProblem(409, "The recording's original nurse and capture time cannot be changed.");
        return new()
        {
            SessionId = id,
            PatientId = manifest.PatientId,
            DeviceId = manifest.DeviceId,
            StartTimeUtc = manifest.StartTimeUtc,
            Status = manifest.Status
        };
    }

    public static string BatchChecksum(IngestEcgPayloadDto payload) =>
        Convert.ToHexString(SHA256.HashData(JsonSerializer.SerializeToUtf8Bytes(payload))).ToLowerInvariant();

    private static void Validate(IngestEcgPayloadDto p)
    {
        var errors = new List<ValidationResult>();
        if (!Validator.TryValidateObject(p, new ValidationContext(p), errors, true) ||
            p.Samples.Any(s => s is < 0 or > 4095) || p.FirstSampleIndex + p.Samples.Count > 150000 ||
            p.Timestamp > long.MaxValue - 10000 || p.FirmwareVer == null || p.FirmwareVer.Length > 100 ||
            p.SignalQuality is not ("Good" or "Degraded" or "LeadsDisconnected" or "Unknown"))
            throw new AccessProblem(400, "Use schema 2 with a boot ID, sequence, first sample index and 1-1000 raw 12-bit samples at 250 Hz.");
        if (p.FirstSampleTimestampUs != null || p.SampleOffsetsUs != null || p.SampleFlags != null)
        {
            if (p.FirstSampleTimestampUs is not long first || first < 0 || first > long.MaxValue - 10000000 ||
                first / 1000 != p.Timestamp || p.SampleOffsetsUs?.Count != p.Samples.Count || p.SampleFlags?.Count != p.Samples.Count ||
                p.SampleOffsetsUs[0] != 0 || p.SampleFlags.Any(f => f > 15) ||
                p.SampleOffsetsUs.Any(t => t is < 0 or > 10000000) ||
                p.SampleOffsetsUs.Zip(p.SampleOffsetsUs.Skip(1)).Any(pair => pair.First >= pair.Second))
                throw new AccessProblem(400, "Provide matching monotonic sample offsets and quality flags with the original capture timestamp.");
        }
    }

    public async Task<bool> IngestBatchAsync(IngestEcgPayloadDto payload)
    {
        await IngestRecordingBatchAsync(payload); return true;
    }

    public async Task<RecordingReceipt> IngestRecordingBatchAsync(IngestEcgPayloadDto payload)
    {
        Validate(payload);
        // Exclude caller-supplied labels from chunk identity. Patient identity comes from registration.
        var initial = (await Read(payload.SessionId)).Value ?? throw new AccessProblem(404, "Start the recording before sending samples.");
        if (initial.DeviceId != payload.DeviceId || payload.PatientId != null && initial.PatientId != payload.PatientId)
            throw new AccessProblem(409, "The recording and telemetry identities do not match.");

        payload.PatientId = initial.PatientId; payload.PatientName = initial.PatientName;
        var checksum = BatchChecksum(payload);
        var key = SequenceKey(payload.Sequence!.Value);
        var chunkKey = $"{key}-{checksum}";
        var duplicate = false;
        void Check(RecordingManifest m)
        {
            if (m.Status == "Discarded")
                throw new AccessProblem(410, "This recording was discarded.");

            if (m.Batches.TryGetValue(key, out var existing))
            {
                if (existing.Sha256 != checksum)
                    throw new AccessProblem(409, "This sequence was already saved with different content.");
                return;
            }

            if (m.Status is not ("Active" or "Finalising"))
                throw new AccessProblem(409, "The recording does not accept new batches in its current state.");
            if (m.SampleRateHz != payload.SampleRateHz || m.Batches.Count >= 600 ||
                m.Batches.Values.Sum(b => b.SampleCount) + payload.Samples.Count > 150000 ||
                m.ExpectedBatchCount is int expected && payload.Sequence >= expected ||
                m.ExpectedSampleCount is int total && payload.FirstSampleIndex + payload.Samples.Count > total)
                throw new AccessProblem(409, "The batch exceeds the declared recording limits.");

            foreach (var b in m.Batches.Values)
            {
                var first = payload.FirstSampleIndex!.Value;
                if (first < b.FirstSampleIndex + b.SampleCount && first + payload.Samples.Count > b.FirstSampleIndex ||
                    payload.Sequence > b.Sequence && first < b.FirstSampleIndex + b.SampleCount ||
                    payload.Sequence < b.Sequence && first + payload.Samples.Count > b.FirstSampleIndex)
                    throw new AccessProblem(409, "Batch sample ranges overlap or disagree with sequence order.");
                if (b.BootId == payload.BootId)
                {
                    var overlaps = b.LastSampleTimestampUs is long oldLast && payload.FirstSampleTimestampUs is long firstUs
                        ? payload.Sequence > b.Sequence ? firstUs <= oldLast : firstUs + payload.SampleOffsetsUs![^1] >= (b.FirstSampleTimestampUs ?? b.Timestamp * 1000)
                        : payload.Sequence > b.Sequence ? payload.Timestamp < b.Timestamp + b.SampleCount * 1000L / payload.SampleRateHz
                        : payload.Timestamp + payload.Samples.Count * 1000L / payload.SampleRateHz > b.Timestamp;
                    if (overlaps)
                        throw new AccessProblem(409, "Acquisition timestamps overlap within one boot.");
                }
            }
        }
        Check(initial);
        if (!initial.Batches.ContainsKey(key))
        {
            // An unreferenced chunk is not accepted data. Content-addressing avoids overwrites in races.
            var chunkPath = $"{ChunkRoot}/{Key(payload.SessionId)}/{chunkKey}";
            var chunk = await store.ReadAsync<RecordingChunk>(chunkPath);
            if (chunk.Value == null)
            {
                try
                {
                    await store.WriteAsync(chunkPath, new RecordingChunk { Sha256 = checksum, Payload = payload }, chunk.ETag);
                }
                catch (AccessProblem e) when (e.Status == 409) { /* Identical content-addressed writer won. */ }
            }
        }
        var manifest = await Change(payload.SessionId, m =>
        {
            Check(m);
            duplicate = m.Batches.ContainsKey(key);
            if (duplicate)
                return false;

            m.Batches[key] = new()
            {
                ChunkKey = chunkKey,
                Sha256 = checksum,
                Sequence = payload.Sequence.Value,
                FirstSampleIndex = payload.FirstSampleIndex!.Value,
                SampleCount = payload.Samples.Count,
                BootId = payload.BootId,
                Timestamp = payload.Timestamp,
                CapturedAtUtc = payload.CapturedAtUtc,
                ReceivedAtUtc = DateTime.UtcNow,
                LeadConnected = payload.LeadConnected,
                EstimatedBpm = payload.EstimatedBpm,
                SignalQuality = payload.SignalQuality,
                LastSampleTimestampUs = payload.FirstSampleTimestampUs is long first ? first + payload.SampleOffsetsUs![^1] : null,
                FirstSampleTimestampUs = payload.FirstSampleTimestampUs
            };
            return true;

        }) ?? throw new AccessProblem(404, "Recording not found.");
        // Secondary views can be repaired on duplicate retry; never determine the durable receipt.
        try
        {
            await UpdateViews(manifest, payload);
        }
        catch (Exception e)
        {
            logger.LogWarning("Recording saved; secondary telemetry view unavailable ({Type}).", e.GetType().Name);
        }
        return new(manifest.SessionId, payload.Sequence.Value, checksum, duplicate, true,
            manifest.Batches.Count, manifest.Batches.Values.Sum(b => b.SampleCount), manifest.Revision);
    }

    private static RecordingProgress Progress(RecordingManifest m)
    {
        var ordered = m.Batches.Values.OrderBy(b => b.Sequence).ToList();

        var expected = m.ExpectedBatchCount ?? (ordered.Count == 0 ? 0 : ordered[^1].Sequence + 1);

        var missing = Enumerable.Range(0, expected).Where(i => !m.Batches.ContainsKey(SequenceKey(i))).ToList();
        var next = 0; var gaps = missing.Count > 0;

        foreach (var b in ordered)
        {
            if (b.FirstSampleIndex != next) gaps = true; next = b.FirstSampleIndex + b.SampleCount;
        }
        if (m.ExpectedSampleCount is int count && next != count) gaps = true;
        // Clock resets and missing UTC remain explicit rather than being replaced with receive time.
        var clockUnknown = ordered.Any(b => b.CapturedAtUtc == null) || ordered.Select(b => b.BootId).Distinct().Count() > 1;
        var timeGaps = new List<RecordingTimeGap>();
        for (var i = 1; i < ordered.Count; i++)
        {
            var previous = ordered[i - 1]; var current = ordered[i];
            if (previous.BootId != current.BootId)
                timeGaps.Add(new(previous.Sequence, current.Sequence, null, "Clock reset; interval unknown"));
            else
            {
                var intervalUs = current.FirstSampleTimestampUs is long currentUs && previous.LastSampleTimestampUs is long lastUs
                    ? currentUs - lastUs - 1000000 / m.SampleRateHz
                    : (current.Timestamp - previous.Timestamp - previous.SampleCount * 1000L / m.SampleRateHz) * 1000;
                if (intervalUs > 1000) timeGaps.Add(new(previous.Sequence, current.Sequence, intervalUs / 1000, "Unobserved acquisition interval"));
            }
        }
        return new(m.SessionId, m.Status, m.Revision, ordered.Count, ordered.Sum(b => b.SampleCount),
            m.ExpectedBatchCount, m.ExpectedSampleCount, missing, gaps, clockUnknown, ordered, timeGaps);
    }

    public async Task<RecordingProgress?> GetRecordingProgressAsync(string id)
    {
        var m = (await Read(id)).Value; return m == null ? null : Progress(m);
    }

    public async Task<RecordingProgress?> CompleteRecordingAsync(string id, CompleteRecordingRequest request)
    {
        if (!Validator.TryValidateObject(request, new ValidationContext(request), [], true))
            throw new AccessProblem(400, "Provide valid expected batch and sample totals.");
        var manifest = await Change(id, m =>
        {
            if (m.Status == "Discarded") throw new AccessProblem(410, "This recording was discarded.");

            if (m.ExpectedBatchCount != null && (m.ExpectedBatchCount != request.ExpectedBatchCount ||
                m.ExpectedSampleCount != request.ExpectedSampleCount || m.RecordedDurationSeconds != request.RecordedDurationSeconds))
                throw new AccessProblem(409, "Completion totals cannot change after finalisation begins.");

            if (m.Status == "Completed")
                return false;
            if (m.Batches.Values.Any(b => b.Sequence >= request.ExpectedBatchCount ||
                b.FirstSampleIndex + b.SampleCount > request.ExpectedSampleCount))
                throw new AccessProblem(409, "Completion totals exclude samples already received.");

            m.ExpectedBatchCount = request.ExpectedBatchCount; m.ExpectedSampleCount = request.ExpectedSampleCount;
            m.RecordedDurationSeconds = request.RecordedDurationSeconds;
            m.Status = "Finalising";
            var progress = Progress(m);
            if (!progress.HasSampleGaps && progress.ReceivedSampleCount == request.ExpectedSampleCount)
            {
                m.Status = "Completed"; m.EndTimeUtc = DateTime.UtcNow;
            }
            return true;
        });
        if (manifest is { Status: "Completed" } && manifest.StaffReport?.State != "Ready")
            await store.WriteAsync($"clinicalReportJobs/{id}", new ClinicalReportJob(id, DateTimeOffset.MinValue));
        return manifest == null ? null : Progress(manifest);
    }

    public Task<EcgSessionSummaryDto?> StopSessionAsync(string id, int? recordedDurationSeconds = null) =>
        throw new AccessProblem(409, "Use the complete endpoint with expected batch and sample totals. A stop without totals cannot confirm a complete upload.");

    public async Task<bool> SetSessionPausedAsync(string id, bool paused) => await Change(id, m =>
    {
        if (m.Status is not ("Active" or "Paused")) throw new AccessProblem(409, "Only an active or paused recording can change recording state.");
        var status = paused ? "Paused" : "Active"; if (m.Status == status) return false;
        m.Status = status; return true;
    }) != null;

    public async Task<bool> DiscardSessionAsync(string id) => await Change(id, m =>
    {
        if (m.Status == "Discarded") return false;
        m.Status = "Discarded"; m.EndTimeUtc = DateTime.UtcNow; return true;
    }) != null;

    private static EcgSessionSummaryDto Summary(RecordingManifest m)
    {
        var bpm = m.Batches.Values.Where(b => b.LeadConnected && b.SignalQuality == "Good" && b.EstimatedBpm > 0)
            .Select(b => b.EstimatedBpm).ToList();
        return new()
        {
            SessionId = m.SessionId,
            PatientId = m.PatientId,
            PatientName = m.PatientName,
            DeviceId = m.DeviceId,
            StartTimeUtc = m.StartTimeUtc,
            EndTimeUtc = m.EndTimeUtc,
            DurationSeconds = m.RecordedDurationSeconds ?? m.Batches.Values.Sum(b => b.SampleCount) / m.SampleRateHz,
            AverageBpm = bpm.Count == 0 ? 0 : (int)bpm.Average(),
            MinBpm = bpm.Count == 0 ? 0 : bpm.Min(),
            MaxBpm = bpm.Count == 0 ? 0 : bpm.Max(),
            TotalSamplesCount = m.Batches.Values.Sum(b => b.SampleCount),
            SignalQuality = m.Batches.Count == 0 ? "Unknown" : m.Batches.Values.MaxBy(b => b.Sequence)!.SignalQuality,
            Status = m.Status,
            ReportAvailable = m.Status == "Completed" && m.StaffReport?.State == "Ready"
        };
    }

    public async Task<List<EcgSessionSummaryDto>> GetPatientSessionsAsync(string patientId)
    {
        return (await GetPatientSessionPageAsync(patientId, limit: 100)).Items.ToList();
    }

    public async Task<AccessPage<EcgSessionSummaryDto>> GetPatientSessionPageAsync(string patientId, string? after = null, int limit = 20)
    {
        if (after != null) Key(after);
        var index = await store.ListAsync<PatientRecordingIndex>($"patientRecordingsV2/{Key(patientId)}", after, limit);
        var carePlan = (await store.ReadAsync<CarePlan>($"clinicalCarePlans/{Key(patientId)}")).Value ?? new CarePlan();
        var result = new List<EcgSessionSummaryDto>();
        foreach (var item in index.Items)
        {
            var m = (await Read(item.SessionId)).Value;
            if (m != null && m.PatientId == patientId && m.Status != "Discarded")
            {
                var summary = Summary(m);
                var notes = await GetDoctorNotesAsync(m.SessionId);
                summary.HasDoctorNotes = notes.Count > 0;
                var release = (await store.ReadAsync<PatientRelease>($"patientReleases/{Key(m.SessionId)}")).Value;
                var (releaseState, _) = DoctorChartService.EvaluateReleaseAndReviewState(notes, carePlan, release, patientId);
                summary.ResultsReleased = releaseState.ResultsReleased;
                summary.HasUnreleasedDrafts = m.Status == "Completed" && releaseState.HasUnreleasedDrafts;
                summary.ReportAvailable = m.Status == "Completed" && m.StaffReport?.State == "Ready" &&
                                          m.StaffReport.NoteCount == notes.Count &&
                                          await blobs.ReportBlobExistsAsync(StaffReportFileName(m.SessionId));
                result.Add(summary);
            }
        }
        return new(result.OrderByDescending(s => s.StartTimeUtc).ToList(), index.NextCursor);
    }

    public async Task<RecordingBatchPage?> GetRecordingBatchesAsync(string id, int afterSequence = -1, int limit = 20)
    {
        var m = (await Read(id)).Value;
        if (m == null || m.Status == "Discarded") return null;
        var refs = m.Batches.Values.Where(b => b.Sequence > afterSequence).OrderBy(b => b.Sequence).Take(Math.Clamp(limit, 1, 100) + 1).ToList();
        var chunks = new List<RecordingChunk>();
        foreach (var reference in refs.Take(Math.Clamp(limit, 1, 100)))
        {
            var chunk = (await store.ReadAsync<RecordingChunk>($"{ChunkRoot}/{Key(id)}/{reference.ChunkKey}")).Value;
            if (chunk == null || chunk.Sha256 != reference.Sha256 || BatchChecksum(chunk.Payload) != reference.Sha256)
                throw new AccessProblem(503, "A committed recording chunk is missing or failed its integrity check.");
            chunks.Add(chunk);
        }
        return new(id, chunks, refs.Count > Math.Clamp(limit, 1, 100) ? refs[chunks.Count - 1].Sequence : null);
    }

    public async Task<EcgSessionSamplesResponseDto?> GetSessionSamplesAsync(string id)
    {
        var m = (await Read(id)).Value;
        if (m == null || m.Status == "Discarded") return null;
        var samples = new List<int>();
        // A bounded compatibility response. New clients should use the paginated batch endpoint.
        foreach (var b in m.Batches.Values.OrderBy(b => b.Sequence))
        {
            var chunk = (await store.ReadAsync<RecordingChunk>($"{ChunkRoot}/{Key(id)}/{b.ChunkKey}")).Value;
            if (chunk == null || BatchChecksum(chunk.Payload) != b.Sha256) throw new AccessProblem(503, "Recording integrity check failed.");
            samples.AddRange(chunk.Payload.Samples);
        }
        return new()
        {
            SessionId = id,
            SampleRateHz = m.SampleRateHz,
            TotalSamples = samples.Count,
            Samples = samples,
            HasSampleGaps = Progress(m).HasSampleGaps,
            Segments = m.Batches.Values.OrderBy(b => b.Sequence).ToList()
        };
    }

    public async Task<bool> RegisterDeviceAsync(DeviceRegistrationDto registration)
    {
        await store.WriteAsync($"deviceStateV2/{Key(registration.DeviceId)}", new DeviceStatusDto
        {
            DeviceId = registration.DeviceId,
            PatientId = registration.PatientId,
            DeviceName = registration.DeviceName,
            LastHeartbeatUtc = DateTime.MinValue,
            FirmwareVersion = "",
            SignalQuality = "Unknown"
        });
        return true;
    }
    public async Task<DeviceStatusDto?> GetDeviceStatusAsync(string id)
    {
        var device = (await store.ReadAsync<DeviceStatusDto>($"deviceStateV2/{Key(id)}")).Value;
        if (device != null) device.IsOnline = false; // Historical uploads do not prove a live device connection.
        return device;
    }

    private async Task UpdateViews(RecordingManifest m, IngestEcgPayloadDto payload)
    {
        if (m.Status == "Discarded") return;
        var newest = m.Batches.Values.MaxBy(b => b.Sequence)!;
        if (newest.Sequence != payload.Sequence)
            return;

        await store.WriteAsync($"deviceStateV2/{Key(m.DeviceId)}", new DeviceStatusDto
        {
            DeviceId = m.DeviceId,
            PatientId = m.PatientId,
            DeviceName = "PulseLink AD8232 Monitor",
            LeadConnected = payload.LeadConnected,
            EstimatedBpm = payload.LeadConnected ? payload.EstimatedBpm : 0,
            FirmwareVersion = payload.FirmwareVer,
            SignalQuality = payload.SignalQuality,
            AdaptiveThreshold = payload.AdaptiveThreshold,
            LastHeartbeatUtc = newest.ReceivedAtUtc
        });
        string? type = !payload.LeadConnected ? "LeadsDetached" : payload.EstimatedBpm > 120 ? "ElevatedHeartRate" :
            payload.EstimatedBpm is > 0 and < 50 ? "LowHeartRate" : null;

        if (type == null)
            return;
        var alert = new AlertDto
        {
            AlertId = $"ALERT-{m.SessionId}-{type}",
            PatientId = m.PatientId,
            PatientName = m.PatientName,
            DeviceId = m.DeviceId,
            SessionId = m.SessionId,
            AlertType = type,
            Severity = "Warning",
            Message = type == "LeadsDetached" ? "Lead-off indication reported." : "Estimated BPM outside configured prototype thresholds.",
            EstimatedBpm = payload.LeadConnected ? payload.EstimatedBpm : 0,
            TimestampUtc = newest.ReceivedAtUtc
        };

        var path = $"recordingAlertsV2/{alert.AlertId}";
        var existing = await store.ReadAsync<AlertDto>(path);
        if (existing.Value == null || existing.Value.TimestampUtc < alert.TimestampUtc)
            await store.WriteAsync(path, alert, existing.ETag);
    }

    public async Task<DoctorNoteDto> AddDoctorNoteAsync(CreateDoctorNoteDto request)
    {
        var sanitisedPrescriptions = (request.Prescriptions ?? [])
            .Where(m => !string.IsNullOrWhiteSpace(m.Name))
            .Select(m => new MedicationEntry
            {
                Name = m.Name.Trim(),
                Dosage = m.Dosage?.Trim() ?? string.Empty,
                Frequency = m.Frequency?.Trim() ?? string.Empty
            })
            .Take(30)
            .ToList();

        var note = new DoctorNoteDto
        {
            NoteId = $"NOTE-{Guid.NewGuid():N}",
            SessionId = request.SessionId,
            PatientId = request.PatientId,
            DoctorId = request.DoctorId,
            DoctorName = request.DoctorName,
            ClinicalImpression = request.ClinicalImpression.Trim(),
            Recommendations = string.IsNullOrWhiteSpace(request.Recommendations) ? null : request.Recommendations.Trim(),
            FollowUpRequired = request.FollowUpRequired,
            FollowUpInstructions = string.IsNullOrWhiteSpace(request.FollowUpInstructions) ? null : request.FollowUpInstructions.Trim(),
            FollowUpInDays = request.FollowUpInDays,
            Prescriptions = sanitisedPrescriptions,
            CreatedAtUtc = DateTime.UtcNow
        };
        await store.WriteAsync($"recordingNotesV2/{Key(request.SessionId)}/{note.NoteId}", note); return note;
    }
    public async Task<List<DoctorNoteDto>> GetDoctorNotesAsync(string id) =>
        (await store.ListAsync<DoctorNoteDto>($"recordingNotesV2/{Key(id)}", limit: 100)).Items.ToList();
    public async Task<List<AlertDto>> GetActiveAlertsAsync()
    {
        var alerts = (await store.ListAsync<AlertDto>("recordingAlertsV2", limit: 100)).Items;
        var active = new List<AlertDto>();
        foreach (var alert in alerts.Where(a => !a.IsAcknowledged))
            if ((await Read(alert.SessionId)).Value is { Status: not "Discarded" }) active.Add(alert);
        return active;
    }
    public async Task<bool> DismissAlertAsync(string id)
    {
        var path = $"recordingAlertsV2/{Key(id)}";
        var row = await store.ReadAsync<AlertDto>(path); if (row.Value == null) return false;
        row.Value.IsAcknowledged = true; await store.WriteAsync(path, row.Value, row.ETag); return true;
    }
    public async Task<bool> DismissAllAlertsAsync()
    {
        foreach (var alert in await GetActiveAlertsAsync()) await DismissAlertAsync(alert.AlertId); return true;
    }

    private async Task<(EcgSession Session, RecordingManifest Manifest)> BuildReportDomainSessionAsync(
        string id,
        bool isReleasedPatientCopy,
        string? releasedCareInstructions,
        DateTimeOffset? releasedAtUtc)
    {
        var m = (await Read(id)).Value ?? throw new AccessProblem(404, "Session not found.");
        if (m.Status == "Discarded") throw new AccessProblem(409, "A discarded recording has no clinical report.");
        if (m.Status != "Completed") throw new AccessProblem(409, "A report requires a complete, finalised recording.");

        var progress = Progress(m);
        var samples = new List<int>();
        var flags = new List<byte>();

        foreach (var b in progress.Batches)
        {
            var chunk = (await store.ReadAsync<RecordingChunk>($"{ChunkRoot}/{Key(id)}/{b.ChunkKey}")).Value;
            if (chunk == null || BatchChecksum(chunk.Payload) != b.Sha256)
                throw new AccessProblem(503, "Recording integrity check failed.");
            samples.AddRange(chunk.Payload.Samples);
            if (chunk.Payload.SampleFlags is { Count: > 0 } chunkFlags && chunkFlags.Count == chunk.Payload.Samples.Count)
            {
                flags.AddRange(chunkFlags);
            }
            else
            {
                var defaultFlag = (byte)(b.LeadConnected && b.SignalQuality != "LeadsDisconnected" ? 0 : 1);
                flags.AddRange(Enumerable.Repeat(defaultFlag, chunk.Payload.Samples.Count));
            }
        }

        var s = Summary(m);
        var session = new EcgSession
        {
            SessionId = id,
            PatientId = m.PatientId,
            PatientName = m.PatientName,
            DeviceId = m.DeviceId,
            SiteId = m.SiteId,
            OperatorUid = m.OperatorUid,
            StartTimeUtc = m.StartTimeUtc,
            EndTimeUtc = m.EndTimeUtc,
            DurationSeconds = s.DurationSeconds,
            SampleRateHz = m.SampleRateHz,
            Status = m.Status,
            TotalSamplesCount = s.TotalSamplesCount,
            MinBpm = s.MinBpm,
            MaxBpm = s.MaxBpm,
            RollingSumBpm = s.AverageBpm,
            BpmReadingCount = s.AverageBpm > 0 ? 1 : 0,
            SignalQuality = s.SignalQuality,
            SampleBuffer = samples,
            SampleFlags = flags,
            Segments = progress.Batches.ToList(),
            TimeGaps = progress.TimeGaps.ToList(),
            HasSampleGaps = progress.HasSampleGaps,
            ClockContinuityUnverified = progress.ClockContinuityUnverified,
            IsReleasedPatientCopy = isReleasedPatientCopy,
            ReleasedAtUtc = releasedAtUtc,
            ReleasedCareInstructions = releasedCareInstructions
        };

        return (session, m);
    }

    public async Task<byte[]?> GenerateSessionReportPdfAsync(string id, bool forceRegenerate = false)
    {
        var m = (await Read(id)).Value;
        if (m == null || m.Status == "Discarded") return null;
        if (m.Status != "Completed") throw new AccessProblem(409, "A report requires a complete, finalised recording.");

        var fileName = StaffReportFileName(id);
        var rawNotes = await GetDoctorNotesAsync(id);

        if (!forceRegenerate &&
            m.StaffReport is { State: "Ready" } existingState &&
            existingState.NoteCount == rawNotes.Count)
        {
            var cached = await blobs.DownloadReportBlobAsync(fileName);
            if (cached is { Length: > 0 })
            {
                await new ClinicalNotifications(store).EnqueueAsync(m.PatientId, id, "REPORT_READY");
                return cached;
            }
        }

        await Change(id, current =>
        {
            current.StaffReport = new ReportStateRecord
            {
                State = "Generating",
                FileName = fileName,
                ManifestRevision = current.Revision,
                NoteCount = rawNotes.Count,
                UpdatedAtUtc = DateTimeOffset.UtcNow
            };
            return true;
        });

        try
        {
            var (session, _) = await BuildReportDomainSessionAsync(id, isReleasedPatientCopy: false, releasedCareInstructions: null, releasedAtUtc: null);
            var notes = rawNotes.Select(n => new DoctorClinicalNote
            {
                NoteId = n.NoteId,
                SessionId = n.SessionId,
                PatientId = n.PatientId,
                DoctorId = n.DoctorId,
                DoctorName = n.DoctorName,
                ClinicalImpression = n.ClinicalImpression,
                Recommendations = n.Recommendations,
                FollowUpRequired = n.FollowUpRequired,
                FollowUpInstructions = n.FollowUpInstructions,
                FollowUpInDays = n.FollowUpInDays,
                Prescriptions = n.Prescriptions?.ToList() ?? [],
                CreatedAtUtc = n.CreatedAtUtc
            }).ToList();

            var bytes = pdf.GenerateSessionPdf(session, notes);
            if (bytes == null || bytes.Length == 0)
            {
                throw new AccessProblem(503, "Report generation returned an empty PDF payload.");
            }

            await blobs.UploadReportBlobAsync(fileName, bytes, "application/pdf");

            await Change(id, current =>
            {
                current.StaffReport = new ReportStateRecord
                {
                    State = "Ready",
                    FileName = fileName,
                    ManifestRevision = current.Revision,
                    NoteCount = rawNotes.Count,
                    UpdatedAtUtc = DateTimeOffset.UtcNow,
                    LastError = null
                };
                return true;
            });

            await new ClinicalNotifications(store).EnqueueAsync(m.PatientId, id, "REPORT_READY");
            return bytes;
        }
        catch (Exception ex)
        {
            await Change(id, current =>
            {
                current.StaffReport = new ReportStateRecord
                {
                    State = "Failed",
                    FileName = fileName,
                    ManifestRevision = current.Revision,
                    NoteCount = rawNotes.Count,
                    UpdatedAtUtc = DateTimeOffset.UtcNow,
                    LastError = "Report generation or private blob persistence failed."
                };
                return true;
            });

            if (ex is AccessProblem) throw;
            throw new AccessProblem(503, "Unable to generate or store the ECG PDF report.");
        }
    }

    public async Task<ReportStatusDto?> GetStaffReportStatusAsync(string id, bool generateIfMissing = true)
    {
        var m = (await Read(id)).Value;
        if (m == null || m.Status == "Discarded") return null;

        var fileName = StaffReportFileName(id);
        var downloadPath = $"/api/reports/{fileName}";
        var streamPath = $"/api/reports/session/{Key(id)}/pdf";

        if (m.Status != "Completed")
        {
            return new ReportStatusDto(
                SessionId: id,
                PatientId: m.PatientId,
                Audience: "staff",
                State: "Unavailable",
                ReportAvailable: false,
                FileName: fileName,
                DownloadPath: downloadPath,
                StreamPath: streamPath,
                AuthenticatedEmailLinkPath: streamPath,
                GeneratedAtUtc: null,
                Revision: m.Revision,
                Message: "Recording is not yet completed and finalised.");
        }

        var notes = await GetDoctorNotesAsync(id);
        var state = m.StaffReport;
        var isCurrentReady = state is { State: "Ready" } &&
                             state.NoteCount == notes.Count &&
                             await blobs.ReportBlobExistsAsync(fileName);

        if (!isCurrentReady && generateIfMissing)
        {
            try
            {
                await GenerateSessionReportPdfAsync(id, forceRegenerate: true);
                m = (await Read(id)).Value!;
                state = m.StaffReport;
                isCurrentReady = state is { State: "Ready" };
            }
            catch (AccessProblem problem)
            {
                m = (await Read(id)).Value ?? m;
                state = m.StaffReport;
                return new ReportStatusDto(
                    SessionId: id,
                    PatientId: m.PatientId,
                    Audience: "staff",
                    State: "Failed",
                    ReportAvailable: false,
                    FileName: fileName,
                    DownloadPath: downloadPath,
                    StreamPath: streamPath,
                    AuthenticatedEmailLinkPath: streamPath,
                    GeneratedAtUtc: state?.UpdatedAtUtc,
                    Revision: m.Revision,
                    Message: problem.Message);
            }
        }

        var effectiveState = isCurrentReady ? "Ready" : state?.State ?? "Unavailable";
        return new ReportStatusDto(
            SessionId: id,
            PatientId: m.PatientId,
            Audience: "staff",
            State: effectiveState,
            ReportAvailable: isCurrentReady,
            FileName: fileName,
            DownloadPath: downloadPath,
            StreamPath: streamPath,
            AuthenticatedEmailLinkPath: streamPath,
            GeneratedAtUtc: state?.UpdatedAtUtc,
            Revision: m.Revision,
            Message: state?.LastError);
    }

    public async Task<byte[]> GenerateReleasedPatientPdfAsync(
        string id,
        IReadOnlyList<DoctorNoteDto> releasedNotes,
        string? releasedCareInstructions,
        DateTimeOffset releasedAtUtc)
    {
        var (session, _) = await BuildReportDomainSessionAsync(
            id,
            isReleasedPatientCopy: true,
            releasedCareInstructions: releasedCareInstructions,
            releasedAtUtc: releasedAtUtc);

        var notes = releasedNotes.Select(n => new DoctorClinicalNote
        {
            NoteId = n.NoteId,
            SessionId = n.SessionId,
            PatientId = n.PatientId,
            DoctorId = n.DoctorId,
            DoctorName = n.DoctorName,
            ClinicalImpression = n.ClinicalImpression,
            Recommendations = n.Recommendations,
            FollowUpRequired = n.FollowUpRequired,
            FollowUpInstructions = n.FollowUpInstructions,
            FollowUpInDays = n.FollowUpInDays,
            Prescriptions = n.Prescriptions?.ToList() ?? [],
            CreatedAtUtc = n.CreatedAtUtc
        }).ToList();

        var bytes = pdf.GenerateSessionPdf(session, notes);
        if (bytes == null || bytes.Length == 0)
        {
            throw new AccessProblem(503, "Released patient report generation produced an empty PDF document.");
        }

        var fileName = ReleasedPatientReportFileName(id);
        await blobs.UploadReportBlobAsync(fileName, bytes, "application/pdf");
        return bytes;
    }
}
