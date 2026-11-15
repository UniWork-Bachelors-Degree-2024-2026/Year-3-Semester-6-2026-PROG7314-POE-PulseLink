using System.Net;
using System.Net.Http.Json;
using System.Text;
using Microsoft.Extensions.Logging.Abstractions;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.Domain;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class RecordingPersistenceTests
{
    internal static EcgDataService Service(IAccessStore store, IBlobStorageService? blob = null) =>
        new(store, new PdfReportGenerator(NullLogger<PdfReportGenerator>.Instance), blob ?? new MemoryBlobStorage(), NullLogger<EcgDataService>.Instance);

    internal static Task<StartSessionResponseDto> Start(IEcgDataService s, Guid? recording = null) => s.StartSessionAsync(new()
    {
        RecordingId = recording,
        PatientId = "P1",
        DeviceId = "D1",
        PatientName = "Registered patient"
    });
    internal static IngestEcgPayloadDto Batch(string id, int sequence = 0, string boot = "boot1") => new()
    {
        SessionId = id,
        DeviceId = "D1",
        BootId = boot,
        Sequence = sequence,
        FirstSampleIndex = sequence * 4,
        Timestamp = sequence * 16L,
        LeadConnected = true,
        EstimatedBpm = 72,
        FirmwareVer = "test",
        Samples = [2048, 2100, 1900, 2048],
        CapturedAtUtc = null
    };

    [Fact]
    public async Task Session_identity_is_idempotent_and_cannot_be_rebound()
    {
        var store = new MemoryAccessStore(); var id = Guid.NewGuid();
        var first = await Start(Service(store), id); var second = await Start(Service(store), id);
        Assert.Equal(first.SessionId, second.SessionId); Assert.Equal(first.StartTimeUtc, second.StartTimeUtc);
        var error = await Assert.ThrowsAsync<AccessProblem>(() => Service(store).StartSessionAsync(new()
        {
            RecordingId = id,
            PatientId = "P2",
            DeviceId = "D1"
        }));
        Assert.Equal(409, error.Status);
    }

    [Fact]
    public async Task Restart_preserves_ordered_samples_pause_and_duplicate_receipts()
    {
        var store = new MemoryAccessStore(); var service = Service(store); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id, 1));
        await Service(store).IngestRecordingBatchAsync(Batch(id));
        await Service(store).SetSessionPausedAsync(id, true);
        Assert.Equal("Paused", (await Service(store).GetSessionAccessContextAsync(id))!.Status);
        Assert.Equal(409, (await Assert.ThrowsAsync<AccessProblem>(() => Service(store).IngestRecordingBatchAsync(Batch(id, 2)))).Status);
        var receipt = await Service(store).IngestRecordingBatchAsync(Batch(id));
        Assert.True(receipt.Duplicate); Assert.True(receipt.Persisted); Assert.Equal(8, receipt.ReceivedSampleCount);
        var samples = await Service(store).GetSessionSamplesAsync(id);
        Assert.Equal(Enumerable.Repeat(new[] { 2048, 2100, 1900, 2048 }, 2).SelectMany(x => x), samples!.Samples);
        Assert.False(samples.HasSampleGaps);
    }

    [Fact]
    public async Task Same_sequence_with_different_content_is_rejected()
    {
        var service = Service(new MemoryAccessStore()); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id)); var changed = Batch(id); changed.Samples[0]++;
        Assert.Equal(409, (await Assert.ThrowsAsync<AccessProblem>(() => service.IngestRecordingBatchAsync(changed))).Status);
        Assert.Equal(4, (await service.GetRecordingProgressAsync(id))!.ReceivedSampleCount);
    }

    [Fact]
    public async Task Missing_batches_remain_finalising_until_recovered_and_completed_again()
    {
        var store = new MemoryAccessStore(); var service = Service(store); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id, 1));
        var finish = new CompleteRecordingRequest { ExpectedBatchCount = 2, ExpectedSampleCount = 8 };
        var pending = await service.CompleteRecordingAsync(id, finish);
        Assert.Equal("Finalising", pending!.Status); Assert.Equal([0], pending.MissingSequences); Assert.True(pending.HasSampleGaps);
        await Assert.ThrowsAsync<AccessProblem>(() => service.GenerateSessionReportPdfAsync(id));
        await Service(store).IngestRecordingBatchAsync(Batch(id));
        var completed = await Service(store).CompleteRecordingAsync(id, finish);
        Assert.Equal("Completed", completed!.Status);
        Assert.Equal(completed.Revision, (await Service(store).CompleteRecordingAsync(id, finish))!.Revision);
        Assert.True((await Service(store).IngestRecordingBatchAsync(Batch(id))).Duplicate);
        Assert.Equal(409, (await Assert.ThrowsAsync<AccessProblem>(() => service.IngestRecordingBatchAsync(Batch(id, 2)))).Status);
        Assert.Equal(409, (await Assert.ThrowsAsync<AccessProblem>(() => service.CompleteRecordingAsync(id,
            new() { ExpectedBatchCount = 2, ExpectedSampleCount = 9 }))).Status);
    }

    [Fact]
    public async Task Sample_index_gap_does_not_become_a_complete_recording()
    {
        var service = Service(new MemoryAccessStore()); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id)); var gap = Batch(id, 1); gap.FirstSampleIndex = 8;
        await service.IngestRecordingBatchAsync(gap);
        var result = await service.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 2, ExpectedSampleCount = 12 });
        Assert.Equal("Finalising", result!.Status); Assert.True(result.HasSampleGaps); Assert.Empty(result.MissingSequences);
    }

    [Theory]
    [InlineData("index")]
    [InlineData("time")]
    [InlineData("adc")]
    [InlineData("schema")]
    public async Task Invalid_or_overlapping_samples_are_rejected(string invalid)
    {
        var service = Service(new MemoryAccessStore()); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id)); var next = Batch(id, 1);
        if (invalid == "index") next.FirstSampleIndex = 2;
        if (invalid == "time") next.Timestamp = 0;
        if (invalid == "adc") next.Samples[0] = 4096;
        if (invalid == "schema") next.SchemaVersion = 1;
        var problem = await Assert.ThrowsAsync<AccessProblem>(() => service.IngestRecordingBatchAsync(next));
        Assert.Equal(invalid is "adc" or "schema" ? 400 : 409, problem.Status);
    }

    [Fact]
    public async Task Clock_reset_and_lead_off_are_preserved_without_fabricating_UTC_or_BPM()
    {
        var service = Service(new MemoryAccessStore()); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id)); var reboot = Batch(id, 1, "boot2"); reboot.Timestamp = 0;
        reboot.EstimatedBpm = 180; reboot.LeadConnected = false;
        await service.IngestRecordingBatchAsync(reboot);
        var progress = (await service.GetRecordingProgressAsync(id))!;
        Assert.True(progress.ClockContinuityUnverified); Assert.All(progress.Batches, b => Assert.Null(b.CapturedAtUtc));
        Assert.Equal(72, Assert.Single(await service.GetPatientSessionsAsync("P1")).AverageBpm);
        Assert.Equal(0, (await service.GetDeviceStatusAsync("D1"))!.EstimatedBpm);
        Assert.False((await service.GetDeviceStatusAsync("D1"))!.IsOnline);
        Assert.Null(Assert.Single(progress.TimeGaps).DurationMs);
    }

    [Fact]
    public async Task Acquisition_time_gap_is_preserved_and_rendered_in_gap_aware_report()
    {
        var blobs = new MemoryBlobStorage();
        var service = Service(new MemoryAccessStore(), blobs); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id)); var next = Batch(id, 1); next.Timestamp += 1000;
        await service.IngestRecordingBatchAsync(next);
        var result = (await service.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 2, ExpectedSampleCount = 8 }))!;
        Assert.Equal("Completed", result.Status); Assert.False(result.HasSampleGaps);
        Assert.Equal(1000, Assert.Single(result.TimeGaps).DurationMs);
        var pdf = await service.GenerateSessionReportPdfAsync(id);
        Assert.NotNull(pdf);
        var pdfText = Encoding.Latin1.GetString(pdf!);
        Assert.StartsWith("%PDF-1.4", pdfText);
        Assert.Contains("GAP 1000 ms", pdfText);
        Assert.Contains("Time gaps: 1", pdfText);
    }

    [Fact]
    public async Task Restart_preserves_notes_and_discard_hides_related_alerts()
    {
        var store = new MemoryAccessStore(); var service = Service(store); var id = (await Start(service)).SessionId;
        await service.AddDoctorNoteAsync(new() { SessionId = id, PatientId = "P1", DoctorId = "doc1", ClinicalImpression = "Test assessment" });
        Assert.Equal("Test assessment", Assert.Single(await Service(store).GetDoctorNotesAsync(id)).ClinicalImpression);
        var high = Batch(id); high.EstimatedBpm = 150; await service.IngestRecordingBatchAsync(high);
        Assert.Single(await Service(store).GetActiveAlertsAsync());
        await Service(store).DiscardSessionAsync(id);
        Assert.Empty(await Service(store).GetActiveAlertsAsync());
    }

    [Fact]
    public async Task Discarded_recording_cannot_serve_cached_report_or_notes_via_HTTP()
    {
        using var app = new AccessTestApplication(); using var nurse = app.As("nurse1"); using var doctor = app.As("doc1");
        var id = (await (await nurse.PostAsJsonAsync("/api/ecg/sessions/start", new { patientId = "P1", deviceId = "D1" }))
            .Content.ReadFromJsonAsync<StartSessionResponseDto>())!.SessionId;
        Assert.Equal(HttpStatusCode.NoContent, (await nurse.PostAsync($"/api/ecg/sessions/{id}/discard", null)).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await doctor.GetAsync($"/api/reports/PulseLink_Report_{id}.pdf")).StatusCode);
        Assert.Equal(HttpStatusCode.Gone, (await doctor.PostAsJsonAsync("/api/doctor/notes", new
        {
            sessionId = id,
            patientId = "P1",
            doctorId = "doc1",
            clinicalImpression = "Test assessment"
        })).StatusCode);
    }

    [Fact]
    public async Task Discard_tombstone_survives_restart_and_blocks_delayed_data()
    {
        var store = new MemoryAccessStore(); var service = Service(store); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id)); await service.DiscardSessionAsync(id);
        Assert.True(await Service(store).DiscardSessionAsync(id));
        Assert.Equal(410, (await Assert.ThrowsAsync<AccessProblem>(() => Service(store).IngestRecordingBatchAsync(Batch(id)))).Status);
        Assert.Null(await Service(store).GetSessionSamplesAsync(id)); Assert.Empty(await Service(store).GetPatientSessionsAsync("P1"));
    }

    [Theory]
    [InlineData("chunk")]
    [InlineData("manifest")]
    [InlineData("lost-response")]
    public async Task Failed_writes_never_report_false_success_and_retries_recover(string failure)
    {
        var store = new MemoryAccessStore(); var service = Service(store); var id = (await Start(service)).SessionId;
        var fault = new FaultStore(store)
        {
            FailPrefix = failure == "chunk" ? EcgDataService.ChunkRoot : EcgDataService.RecordingRoot,
            PersistThenFail = failure == "lost-response"
        };
        Assert.Equal(503, (await Assert.ThrowsAsync<AccessProblem>(() => Service(fault).IngestRecordingBatchAsync(Batch(id)))).Status);
        var beforeRetry = (await service.GetRecordingProgressAsync(id))!;
        Assert.Equal(failure == "lost-response" ? 4 : 0, beforeRetry.ReceivedSampleCount);
        var retry = await service.IngestRecordingBatchAsync(Batch(id));
        Assert.True(retry.Persisted); Assert.Equal(failure == "lost-response", retry.Duplicate);
        Assert.Equal(4, retry.ReceivedSampleCount);
    }

    [Fact]
    public async Task Concurrent_API_instances_do_not_lose_or_double_count_batches()
    {
        var inner = new MemoryAccessStore(); var store = new FaultStore(inner) { Yield = true };
        var id = (await Start(Service(store))).SessionId;
        await Task.WhenAll(Service(store).IngestRecordingBatchAsync(Batch(id)), Service(store).IngestRecordingBatchAsync(Batch(id, 1)));
        await Task.WhenAll(Service(store).IngestRecordingBatchAsync(Batch(id)), Service(store).IngestRecordingBatchAsync(Batch(id)));
        Assert.Equal(8, (await Service(store).GetRecordingProgressAsync(id))!.ReceivedSampleCount);
    }

    [Fact]
    public async Task Tampered_chunk_is_not_returned_as_valid_telemetry()
    {
        var store = new MemoryAccessStore(); var service = Service(store); var id = (await Start(service)).SessionId;
        await service.IngestRecordingBatchAsync(Batch(id));
        var reference = Assert.Single((await service.GetRecordingProgressAsync(id))!.Batches);
        await store.WriteAsync($"{EcgDataService.ChunkRoot}/{id}/{reference.ChunkKey}", new RecordingChunk { Payload = Batch(id, 1) });
        Assert.Equal(503, (await Assert.ThrowsAsync<AccessProblem>(() => service.GetRecordingBatchesAsync(id))).Status);
    }

    [Fact]
    public async Task Batch_and_patient_history_pages_have_resume_cursors()
    {
        var service = Service(new MemoryAccessStore()); var id = (await Start(service)).SessionId;
        await Start(service); await Start(service);
        var history = await service.GetPatientSessionPageAsync("P1", limit: 1);
        Assert.Single(history.Items); Assert.NotNull(history.NextCursor);
        Assert.NotEqual(history.Items[0].SessionId, Assert.Single((await service.GetPatientSessionPageAsync("P1", history.NextCursor, 1)).Items).SessionId);
        await service.IngestRecordingBatchAsync(Batch(id)); await service.IngestRecordingBatchAsync(Batch(id, 1));
        var page = (await service.GetRecordingBatchesAsync(id, limit: 1))!;
        Assert.Equal(0, page.NextAfterSequence);
        var next = (await service.GetRecordingBatchesAsync(id, page.NextAfterSequence!.Value, 1))!;
        Assert.Null(next.NextAfterSequence); Assert.Equal(1, Assert.Single(next.Batches).Payload.Sequence);
    }

    [Fact]
    public async Task HTTP_contract_enforces_access_validation_and_incomplete_completion_status()
    {
        using var app = new AccessTestApplication(); using var nurse = app.As("nurse1"); using var unassigned = app.As("doc2");
        var id = (await (await nurse.PostAsJsonAsync("/api/ecg/sessions/start", new { patientId = "P1", deviceId = "D1" }))
            .Content.ReadFromJsonAsync<StartSessionResponseDto>())!.SessionId;
        Assert.Equal(HttpStatusCode.BadRequest, (await nurse.PostAsJsonAsync("/api/ecg/ingest", new { sessionId = id, deviceId = "D1", samples = new[] { 2048 } })).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await nurse.PostAsJsonAsync("/api/ecg/ingest", Batch(id, 1))).StatusCode);
        Assert.Equal(HttpStatusCode.Accepted, (await nurse.PostAsJsonAsync($"/api/ecg/sessions/{id}/complete",
            new CompleteRecordingRequest { ExpectedBatchCount = 2, ExpectedSampleCount = 8 })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await unassigned.GetAsync($"/api/ecg/sessions/{id}/progress")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await unassigned.GetAsync($"/api/ecg/sessions/{id}/batches")).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await nurse.PostAsync($"/api/ecg/sessions/{id}/stop", null)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await nurse.PostAsJsonAsync("/api/ecg/ingest", Batch(id))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await nurse.PostAsJsonAsync($"/api/ecg/sessions/{id}/complete",
            new CompleteRecordingRequest { ExpectedBatchCount = 2, ExpectedSampleCount = 8 })).StatusCode);
    }

    private sealed class FaultStore(IAccessStore inner) : IAccessStore
    {
        public string? FailPrefix { get; set; }
        public bool PersistThenFail { get; set; }
        public bool Yield { get; set; }
        public async Task<StoredValue<T>> ReadAsync<T>(string path, CancellationToken ct = default)
        {
            if (Yield) await Task.Yield(); return await inner.ReadAsync<T>(path, ct);
        }
        public async Task WriteAsync<T>(string path, T value, string? etag = null, CancellationToken ct = default)
        {
            if (Yield) await Task.Yield();
            if (FailPrefix != null && path.StartsWith(FailPrefix, StringComparison.Ordinal))
            {
                FailPrefix = null; if (PersistThenFail) await inner.WriteAsync(path, value, etag, ct);
                throw new AccessProblem(503, "Simulated unavailable database or lost response.");
            }
            await inner.WriteAsync(path, value, etag, ct);
        }
        public Task<AccessPage<T>> ListAsync<T>(string path, string? after = null, int limit = 50, CancellationToken ct = default) => inner.ListAsync<T>(path, after, limit, ct);
    }
}
