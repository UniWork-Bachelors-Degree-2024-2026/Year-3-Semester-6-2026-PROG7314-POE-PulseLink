using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.Domain;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class PatientPortalTests
{
    private static async Task SignIn(AccessTestApplication app, string uid = "patient")
    {
        using var patient = app.As(uid);
        Assert.Equal(HttpStatusCode.OK, (await patient.GetAsync("/api/access/me")).StatusCode);
    }
    private static object LinkRequest(int version = 1, string uid = "patient") =>
        new { accountUid = uid, confirmedEmail = $"{uid}@example.test", expectedVersion = version };
    private static async Task Link(AccessTestApplication app)
    {
        await SignIn(app);
        using var nurse = app.As("nurse1");
        Assert.Equal(HttpStatusCode.OK, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest())).StatusCode);
    }
    private static async Task<string> Recording(AccessTestApplication app, bool completed = true)
    {
        using var scope = app.Services.CreateScope();
        var ecg = scope.ServiceProvider.GetRequiredService<IEcgDataService>();
        var id = (await ecg.StartSessionAsync(new() { PatientId = "P1", DeviceId = "D1" })).SessionId;
        if (completed)
        {
            await ecg.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id));
            await ecg.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 1, ExpectedSampleCount = 4 });
        }
        await ecg.AddDoctorNoteAsync(new() { SessionId = id, PatientId = "P1", DoctorId = "doc1", ClinicalImpression = "Reviewed assessment" });
        return id;
    }

    [Fact]
    public async Task Unlinked_patients_have_a_distinct_empty_state_and_cannot_claim_a_record()
    {
        using var app = new AccessTestApplication(); 
        using var patient = app.As("patient");
        Assert.Equal(HttpStatusCode.NoContent, (await patient.GetAsync("/api/patient/record")).StatusCode);
        Assert.Empty((await patient.GetFromJsonAsync<AccessPage<PatientSessionStatus>>("/api/patient/sessions"))!.Items);
        Assert.Equal(HttpStatusCode.Forbidden, (await patient.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest())).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await patient.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
    }

    [Theory]
    [InlineData("nurse1", HttpStatusCode.OK)]
    [InlineData("admin", HttpStatusCode.OK)]
    [InlineData("nurseOther", HttpStatusCode.Forbidden)]
    [InlineData("doc1", HttpStatusCode.Forbidden)]
    public async Task Only_administrator_or_same_site_nurse_can_confirm_a_verified_login(string uid, HttpStatusCode expected)
    {
        using var app = new AccessTestApplication(); 
        await SignIn(app); 
        using var staff = app.As(uid);
        Assert.Equal(expected, (await staff.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest())).StatusCode);
    }

    [Fact]
    public async Task Linking_requires_verified_patient_identity_matching_email_and_current_record()
    {
        using var app = new AccessTestApplication(); 
        using var nurse = app.As("nurse1");
        Assert.Equal(HttpStatusCode.BadRequest, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest())).StatusCode);
        await SignIn(app);
        Assert.Equal(HttpStatusCode.BadRequest, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login",
            new { accountUid = "patient", confirmedEmail = "wrong@example.test", expectedVersion = 1 })).StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest(99))).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest(uid: "doc1"))).StatusCode);
    }

    [Fact]
    public async Task Link_is_idempotent_one_to_one_and_does_not_change_clinical_assignment()
    {
        using var app = new AccessTestApplication(); 
        await Link(app); 
        using var nurse = app.As("nurse1");
        Assert.Equal(HttpStatusCode.OK, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest())).StatusCode);
        await SignIn(app, "secondPatient");
        Assert.Equal(HttpStatusCode.Conflict, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest(2, "secondPatient"))).StatusCode);
        await app.Store.WriteAsync("accessDirectory/patients/P2", new ClinicalPatient { Id = "P2", FullName = "Other patient", SiteId = "S1", PrimaryDoctorUid = "doc1", Version = 1 });
        Assert.Equal(HttpStatusCode.Conflict, (await nurse.PutAsJsonAsync("/api/access/patients/P2/login", LinkRequest())).StatusCode);
        using var patient = app.As("patient");
        Assert.Equal("P1", (await patient.GetFromJsonAsync<PatientProfile>("/api/patient/record"))!.PatientId);
        var record = (await app.Store.ReadAsync<ClinicalPatient>("accessDirectory/patients/P1")).Value!;
        Assert.Equal("doc1", record.PrimaryDoctorUid); Assert.Equal(2, record.Version);
    }

    [Fact]
    public async Task One_sided_or_tampered_links_fail_closed()
    {
        using var app = new AccessTestApplication(); 
        await SignIn(app);
        await app.Store.WriteAsync("accessDirectory/patientLogins/patient", new PatientLoginLink("P1", "patient", "nurse1", DateTimeOffset.UtcNow));
        using var patient = app.As("patient");
        Assert.Equal(HttpStatusCode.Conflict, (await patient.GetAsync("/api/patient/record")).StatusCode);
        using var nurse = app.As("nurse1");
        Assert.Equal(HttpStatusCode.OK, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login", LinkRequest())).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await patient.GetAsync("/api/patient/record")).StatusCode);
    }

    [Fact]
    public async Task Patient_status_contains_no_waveform_bpm_draft_notes_or_device_details()
    {
        using var app = new AccessTestApplication(); 
        await Link(app); 
        var id = await Recording(app);
        using var patient = app.As("patient");
        var response = await patient.GetAsync("/api/ecg/sessions/patient/patient");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var row = Assert.Single(json.RootElement.EnumerateArray());
        Assert.False(row.GetProperty("resultsReleased").GetBoolean()); Assert.False(row.GetProperty("hasDoctorNotes").GetBoolean());
        Assert.False(row.GetProperty("reportAvailable").GetBoolean());
        foreach (var field in new[] { "samples", "averageBpm", "signalQuality", "deviceId", "patientName", "clinicalImpression" }) Assert.False(row.TryGetProperty(field, out _));
        Assert.Equal(HttpStatusCode.NotFound, (await patient.GetAsync($"/api/doctor/notes/session/{id}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await patient.GetAsync($"/api/patient/sessions/{id}/report/pdf")).StatusCode);
        foreach (var route in new[] { $"/api/ecg/sessions/{id}/samples", $"/api/ecg/sessions/{id}/batches", $"/api/reports/session/{id}/pdf", "/api/devices/D1/status" })
            Assert.Equal(HttpStatusCode.Forbidden, (await patient.GetAsync(route)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await patient.PostAsJsonAsync($"/api/ecg/sessions/{id}/stop", new { })).StatusCode);
    }

    [Fact]
    public async Task Only_assigned_doctor_releases_completed_assessed_recordings()
    {
        using var app = new AccessTestApplication(); 
        var id = await Recording(app);
        foreach (var uid in new[] { "nurse1", "admin", "patient", "doc2", "docOther" })
        {
            using var denied = app.As(uid);
            Assert.Equal(HttpStatusCode.Forbidden, (await denied.PostAsync($"/api/access/sessions/{id}/release", null)).StatusCode);
        }
        using var doctor = app.As("doc1");
        Assert.Equal(HttpStatusCode.OK, (await doctor.PostAsync($"/api/access/sessions/{id}/release", null)).StatusCode);
        var active = await Recording(app, false);
        Assert.Equal(HttpStatusCode.Conflict, (await doctor.PostAsync($"/api/access/sessions/{active}/release", null)).StatusCode);
    }

    [Fact]
    public async Task Released_snapshot_and_PDF_hide_later_drafts_until_explicit_release_and_are_patient_scoped()
    {
        using var app = new AccessTestApplication(); 
        await Link(app); 
        var id = await Recording(app);
        using var doctor = app.As("doc1"); using var patient = app.As("patient"); using var other = app.As("otherPatient");
        await app.Store.WriteAsync("clinicalCarePlans/P1", new CarePlan { ClinicalInstructions = "Released instructions" });

        // Before release, patient report status is Unavailable and reportAvailable is false
        var preStatus = await patient.GetFromJsonAsync<ReportStatusDto>($"/api/patient/sessions/{id}/report");
        Assert.Equal("Unavailable", preStatus!.State);
        Assert.False(preStatus.ReportAvailable);

        // Doctor releases the assessed session
        Assert.Equal(HttpStatusCode.OK, (await doctor.PostAsync($"/api/access/sessions/{id}/release", null)).StatusCode);

        // Patient session status now shows both resultsReleased and reportAvailable as true
        var patientSessions = await patient.GetFromJsonAsync<List<PatientSessionStatus>>("/api/ecg/sessions/patient/patient");
        var sessionRow = Assert.Single(patientSessions!);
        Assert.True(sessionRow.ResultsReleased);
        Assert.True(sessionRow.ReportAvailable);

        var postStatus = await patient.GetFromJsonAsync<ReportStatusDto>($"/api/patient/sessions/{id}/report");
        Assert.Equal("Ready", postStatus!.State);
        Assert.True(postStatus.ReportAvailable);
        Assert.Equal($"/api/patient/sessions/{id}/report", postStatus.AuthenticatedEmailLinkPath);

        // Doctor adds a new draft note and updates care plan WITHOUT re-releasing
        using (var scope = app.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<IEcgDataService>().AddDoctorNoteAsync(new() { SessionId = id, PatientId = "P1", DoctorId = "doc1", ClinicalImpression = "Unreleased new draft" });
        await app.Store.WriteAsync("clinicalCarePlans/P1", new CarePlan { ClinicalInstructions = "Unreleased care plan" });

        // Staff PDF contains the new draft note
        var staffPdfResp = await doctor.GetAsync($"/api/reports/session/{id}/pdf");
        Assert.Equal(HttpStatusCode.OK, staffPdfResp.StatusCode);
        Assert.Equal("no-store", staffPdfResp.Headers.CacheControl?.ToString());
        var staffPdfText = Encoding.Latin1.GetString(await staffPdfResp.Content.ReadAsByteArrayAsync());
        Assert.Contains("Unreleased new draft", staffPdfText);

        // Patient released PDF and JSON snapshot do NOT contain the new draft note or updated care plan
        var release = await patient.GetFromJsonAsync<PatientRelease>($"/api/patient/sessions/{id}/results");
        Assert.Single(release!.Notes); Assert.Equal("Released instructions", release.CarePlan.ClinicalInstructions);
        Assert.Single((await patient.GetFromJsonAsync<List<DoctorNoteDto>>($"/api/doctor/notes/session/{id}"))!);

        var patientPdfResp = await patient.GetAsync($"/api/patient/sessions/{id}/report/pdf");
        Assert.Equal(HttpStatusCode.OK, patientPdfResp.StatusCode);
        Assert.Equal("no-store", patientPdfResp.Headers.CacheControl?.ToString());
        var patientPdfText = Encoding.Latin1.GetString(await patientPdfResp.Content.ReadAsByteArrayAsync());
        Assert.StartsWith("%PDF-1.4", patientPdfText);
        Assert.Contains("Reviewed assessment", patientPdfText);
        Assert.Contains("Released instructions", patientPdfText);
        Assert.DoesNotContain("Unreleased new draft", patientPdfText);
        Assert.DoesNotContain("Unreleased care plan", patientPdfText);

        Assert.Equal(HttpStatusCode.Forbidden, (await other.GetAsync($"/api/patient/sessions/{id}/results")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await other.GetAsync($"/api/patient/sessions/{id}/report/pdf")).StatusCode);

        // Doctor explicitly releases again -> patient PDF and snapshot now include the second note and updated care plan
        await doctor.PostAsync($"/api/access/sessions/{id}/release", null);
        Assert.Equal(2, (await patient.GetFromJsonAsync<PatientRelease>($"/api/patient/sessions/{id}/results"))!.Notes.Count);
        var updatedPatientPdfText = Encoding.Latin1.GetString(await (await patient.GetAsync($"/api/patient/sessions/{id}/report/pdf")).Content.ReadAsByteArrayAsync());
        Assert.Contains("Unreleased new draft", updatedPatientPdfText);
        Assert.Contains("Unreleased care plan", updatedPatientPdfText);

        await app.Store.WriteAsync("accessDirectory/sites/S1", new ClinicSite { Id = "S1", Active = false });
        Assert.Equal(HttpStatusCode.BadRequest, (await patient.GetAsync($"/api/patient/sessions/{id}/results")).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await patient.GetAsync($"/api/patient/sessions/{id}/report/pdf")).StatusCode);
    }

    [Fact]
    public async Task Authoritative_PDF_is_A4_landscape_paginated_and_represents_lead_off_and_limitations_honestly()
    {
        var generator = new PdfReportGenerator(NullLogger<PdfReportGenerator>.Instance);
        // 35 seconds at 250 Hz = 8,750 samples -> 4 strips of 10s -> 2 pages (3 strips on Page 1, 1 strip on Page 2)
        var samples = new List<int>(8750);
        var flags = new List<byte>(8750);
        for (int i = 0; i < 8750; i++)
        {
            samples.Add(2048 + (int)(Math.Sin(i * 0.1) * 400));
            flags.Add(i is >= 1000 and < 1250 ? (byte)0x01 : (byte)0);
        }

        var session = new EcgSession
        {
            SessionId = "session-landscape-test",
            PatientId = "PAT-0402",
            PatientName = "Thabo Molefe",
            DeviceId = "PL-CYD-01",
            StartTimeUtc = new DateTime(2026, 4, 19, 10, 0, 0, DateTimeKind.Utc),
            EndTimeUtc = new DateTime(2026, 4, 19, 10, 0, 35, DateTimeKind.Utc),
            DurationSeconds = 35,
            TotalSamplesCount = samples.Count,
            Status = "Completed",
            RollingSumBpm = 72,
            BpmReadingCount = 1,
            MinBpm = 68,
            MaxBpm = 76,
            SignalQuality = "Lead-off intervals recorded",
            SampleBuffer = samples,
            SampleFlags = flags,
            Segments =
            [
                new RecordingBatchReference { Sequence = 0, FirstSampleIndex = 0, SampleCount = 500, LeadConnected = true },
                new RecordingBatchReference { Sequence = 1, FirstSampleIndex = 500, SampleCount = 8250, LeadConnected = true }
            ],
            TimeGaps = [new RecordingTimeGap(0, 1, 1200, "AcquisitionTimestampGap")]
        };

        var pdfBytes = generator.GenerateSessionPdf(session, [
            new DoctorClinicalNote
            {
                NoteId = "N1",
                SessionId = session.SessionId,
                PatientId = session.PatientId,
                DoctorId = "doc1",
                DoctorName = "Test doctor",
                CreatedAtUtc = new DateTime(2026, 4, 19, 10, 15, 0, DateTimeKind.Utc),
                ClinicalImpression = "Sinus rhythm observed on single-lead prototype trace.",
                Recommendations = "Continue clinic follow-up."
            }
        ]);

        var pdfText = Encoding.Latin1.GetString(pdfBytes);
        Assert.StartsWith("%PDF-1.4", pdfText);
        // A4 landscape dimensions: 841.89 x 595.28 pt
        Assert.Contains("/MediaBox [0 0 841.89 595.28]", pdfText);
        Assert.Contains("/Count 2", pdfText);
        Assert.Contains("Page 1 of 2", pdfText);
        Assert.Contains("Page 2 of 2", pdfText);
        Assert.Contains("PAT-0402", pdfText);
        Assert.Contains("72 BPM", pdfText);
        Assert.Contains(@"Uncalibrated 12-bit ADC \(0-4095, midpoint 2048\)", pdfText);
        Assert.Contains("uncalibrated in millivolts", pdfText);
        Assert.Contains("LEAD-OFF", pdfText);
        Assert.Contains("GAP 1200 ms", pdfText);
        Assert.Contains("No respiration, SpO2, blood pressure, extra leads or autonomous rhythm diagnoses are claimed.", pdfText);
    }

    [Fact]
    public void Authoritative_PDF_renders_extended_a3_landscape_for_full_90s_recording_without_compression()
    {
        var generator = new PdfReportGenerator(NullLogger<PdfReportGenerator>.Instance);
        // Full 90-second recording at 250 Hz = 22,500 samples
        // StripsPerPage = 3 (10s per strip = 30s per page) -> 9 strips -> 3 pages
        var samples = new List<int>(22500);
        var flags = new List<byte>(22500);
        for (int i = 0; i < 22500; i++)
        {
            samples.Add(2048 + (int)(Math.Sin(i * 0.08) * 500));
            flags.Add((byte)0);
        }

        var session = new EcgSession
        {
            SessionId = "session-90s-extended-test",
            PatientId = "PAT-0900",
            PatientName = "Sipho Sithole",
            DeviceId = "PulseLink-CYD-02",
            StartTimeUtc = new DateTime(2026, 4, 19, 11, 0, 0, DateTimeKind.Utc),
            EndTimeUtc = new DateTime(2026, 4, 19, 11, 1, 30, DateTimeKind.Utc),
            DurationSeconds = 90,
            TotalSamplesCount = samples.Count,
            Status = "Completed",
            RollingSumBpm = 75,
            BpmReadingCount = 1,
            MinBpm = 70,
            MaxBpm = 80,
            SignalQuality = "Good",
            SampleBuffer = samples,
            SampleFlags = flags,
            Segments =
            [
                new RecordingBatchReference { Sequence = 0, FirstSampleIndex = 0, SampleCount = 22500, LeadConnected = true }
            ]
        };

        var pdfBytes = generator.GenerateSessionPdf(session, [
            new DoctorClinicalNote
            {
                NoteId = "N2",
                SessionId = session.SessionId,
                PatientId = session.PatientId,
                DoctorId = "doc1",
                DoctorName = "Test doctor",
                CreatedAtUtc = new DateTime(2026, 4, 19, 11, 20, 0, DateTimeKind.Utc),
                ClinicalImpression = "Full 90-second single-lead rhythm strip evaluated.",
                Recommendations = "Routine primary care review."
            }
        ]);

        var pdfText = Encoding.Latin1.GetString(pdfBytes);
        Assert.StartsWith("%PDF-1.4", pdfText);
        // Extended A3 landscape dimensions: 1190.55 x 841.89 pt
        Assert.Contains("/MediaBox [0 0 1190.55 841.89]", pdfText);
        Assert.Contains("/Count 3", pdfText);
        Assert.Contains("Page 1 of 3", pdfText);
        Assert.Contains("Page 3 of 3", pdfText);
        Assert.Contains("Extended A3 Landscape", pdfText);
        Assert.Contains("PAT-0900", pdfText);
        Assert.Contains("75 BPM", pdfText);
        Assert.Contains("22500 samples", pdfText);
    }

    [Fact]
    public async Task Private_storage_failure_marks_report_failed_and_never_caches_plain_text_or_exposes_public_URLs()
    {
        using var app = new AccessTestApplication();
        await Link(app);
        var id = await Recording(app);
        using var doctor = app.As("doc1");
        using var patient = app.As("patient");

        // Simulate private blob storage outage during generation
        app.Blobs.FailUploads = true;
        Assert.Equal(HttpStatusCode.ServiceUnavailable, (await doctor.GetAsync($"/api/reports/session/{id}/pdf")).StatusCode);

        var failedStatus = await doctor.GetFromJsonAsync<ReportStatusDto>($"/api/reports/session/{id}/status?generateIfMissing=false");
        Assert.Equal("Failed", failedStatus!.State);
        Assert.False(failedStatus.ReportAvailable);

        // Release still succeeds for clinical notes, but reportAvailable remains false until PDF storage succeeds
        Assert.Equal(HttpStatusCode.OK, (await doctor.PostAsync($"/api/access/sessions/{id}/release", null)).StatusCode);
        var unreleasedPdfStatus = await patient.GetFromJsonAsync<ReportStatusDto>($"/api/patient/sessions/{id}/report");
        Assert.Equal("Failed", unreleasedPdfStatus!.State);
        Assert.False(unreleasedPdfStatus.ReportAvailable);
        Assert.Equal(HttpStatusCode.ServiceUnavailable, (await patient.GetAsync($"/api/patient/sessions/{id}/report/pdf")).StatusCode);

        // Restore private storage and retry
        app.Blobs.FailUploads = false;
        var recoveredStaffStatus = await doctor.GetFromJsonAsync<ReportStatusDto>($"/api/reports/session/{id}/status?generateIfMissing=true");
        Assert.Equal("Ready", recoveredStaffStatus!.State);
        Assert.True(recoveredStaffStatus.ReportAvailable);
        Assert.DoesNotContain("sig=", recoveredStaffStatus.DownloadPath ?? "", StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("blob.core.windows.net", recoveredStaffStatus.DownloadPath ?? "", StringComparison.OrdinalIgnoreCase);

        var recoveredPatientPdf = await patient.GetAsync($"/api/patient/sessions/{id}/report/pdf");
        Assert.Equal(HttpStatusCode.OK, recoveredPatientPdf.StatusCode);
        Assert.StartsWith("%PDF-1.4", Encoding.Latin1.GetString(await recoveredPatientPdf.Content.ReadAsByteArrayAsync()));

        // Path traversal filenames are rejected
        Assert.Throws<AccessProblem>(() => BlobStorageService.ValidateReportFileName("../secrets.pdf"));
        Assert.Throws<AccessProblem>(() => BlobStorageService.ValidateReportFileName("PulseLink_Report_123.txt"));
    }

    [Fact]
    public async Task Unassessed_recordings_cannot_be_released_and_suspension_revokes_patient_reads()
    {
        using var app = new AccessTestApplication(); 
        await Link(app);
        using var scope = app.Services.CreateScope();
        var ecg = scope.ServiceProvider.GetRequiredService<IEcgDataService>();
        var id = (await ecg.StartSessionAsync(new() { PatientId = "P1", DeviceId = "D1" })).SessionId;
        await ecg.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id));
        await ecg.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 1, ExpectedSampleCount = 4 });
        using var doctor = app.As("doc1"); using var patient = app.As("patient");
        Assert.Equal(HttpStatusCode.Conflict, (await doctor.PostAsync($"/api/access/sessions/{id}/release", null)).StatusCode);
        var account = (await app.Store.ReadAsync<AccessAccount>("accessDirectory/accounts/patient")).Value!;
        account.Status = "suspended";
        await app.Store.WriteAsync("accessDirectory/accounts/patient", account);
        Assert.Equal(HttpStatusCode.Forbidden, (await patient.GetAsync("/api/patient/sessions")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await patient.GetAsync("/api/ecg/sessions/patient/patient")).StatusCode);
    }

    [Fact]
    public async Task Linked_other_patient_cannot_read_a_guessed_release_identifier()
    {
        using var app = new AccessTestApplication(); 
        await Link(app); 
        var id = await Recording(app);

        await SignIn(app, "otherPatient");
        await app.Store.WriteAsync("accessDirectory/patients/P2", new ClinicalPatient { Id = "P2", FullName = "Other patient", SiteId = "S1", PrimaryDoctorUid = "doc1", Version = 1 });
        using var nurse = app.As("nurse1"); using var doctor = app.As("doc1"); using var other = app.As("otherPatient");
        Assert.Equal(HttpStatusCode.OK, (await nurse.PutAsJsonAsync("/api/access/patients/P2/login", LinkRequest(uid: "otherPatient"))).StatusCode);
        await doctor.PostAsync($"/api/access/sessions/{id}/release", null);
        Assert.Equal(HttpStatusCode.NotFound, (await other.GetAsync($"/api/patient/sessions/{id}/results")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await other.GetAsync($"/api/patient/sessions/{id}/report")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await other.GetAsync($"/api/patient/sessions/{id}/report/pdf")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await other.GetAsync($"/api/doctor/notes/session/{id}")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await other.GetAsync("/api/ecg/sessions/patient/P1/page")).StatusCode);
    }
}
