using System.Net;
using System.Net.Http.Json;
using System.Text;
using Microsoft.Extensions.DependencyInjection;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class ClinicalNotificationTests
{
    private sealed class Push : IClinicalPushSender
    {
        public bool Fail { get; set; }
        public List<string> Recipients { get; } = [];
        public Task SendAsync(string token, ClinicalNotification notification, CancellationToken ct)
        {
            if (Fail) throw new IOException("Simulated push outage");
            Recipients.Add(notification.RecipientUid);
            return Task.CompletedTask;
        }
    }

    [Fact]
    public async Task Report_ready_retries_produce_one_doctor_inbox_event_and_no_patient_or_unassigned_doctor_event()
    {
        using var app = new AccessTestApplication();
        using var scope = app.Services.CreateScope();
        var ecg = scope.ServiceProvider.GetRequiredService<IEcgDataService>();
        var id = (await ecg.StartSessionAsync(new() { PatientId = "P1", DeviceId = "D1" })).SessionId;
        await ecg.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id));
        await ecg.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 1, ExpectedSampleCount = 4 });
        Assert.Equal(id, (await app.Store.ReadAsync<ClinicalReportJob>($"clinicalReportJobs/{id}")).Value!.SessionId);
        await ecg.GenerateSessionReportPdfAsync(id);
        await ecg.GenerateSessionReportPdfAsync(id);
        var queue = await app.Store.ListAsync<PendingClinicalNotification>("clinicalNotificationOutbox");
        Assert.Equal("REPORT_READY", Assert.Single(queue.Items).Notification.Type);
        var push = new Push();
        var events = new ClinicalNotifications(app.Store);
        await events.RegisterAsync("doc1", new string('a', 30), default);
        await new ClinicalNotificationDispatcher(app.Store, events, push).DispatchAsync(default);
        Assert.Equal("doc1", Assert.Single(push.Recipients));
        using var doctor = app.As("doc1");
        using var other = app.As("doc2");
        using var patient = app.As("patient");
        Assert.Single((await doctor.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items);
        Assert.Empty((await other.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items);
        Assert.Empty((await patient.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items);
        // Revoking an assignment also hides historical inbox links.
        var record = (await app.Store.ReadAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients/P1")).Value!;
        record.PrimaryDoctorUid = "doc2";
        await app.Store.WriteAsync($"{AccessDirectory.Root}/patients/P1", record);
        Assert.Empty((await doctor.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items);
    }

    [Fact]
    public async Task Push_outage_preserves_inbox_and_queue_and_a_new_dispatcher_recovers_without_duplicate_rows()
    {
        using var app = new AccessTestApplication();
        var events = new ClinicalNotifications(app.Store);
        await events.RegisterAsync("doc1", new string('a', 30), default);
        await events.EnqueueAsync("P1", "S1", "REPORT_READY");
        var push = new Push { Fail = true };
        await Assert.ThrowsAsync<IOException>(() => new ClinicalNotificationDispatcher(app.Store, events, push).DispatchAsync(default));
        Assert.Single((await app.Store.ListAsync<ClinicalNotification>("clinicalInbox/doc1")).Items);
        var pending = Assert.Single((await app.Store.ListAsync<PendingClinicalNotification>("clinicalNotificationOutbox")).Items);
        pending.RetryAtUtc = DateTimeOffset.MinValue;
        await app.Store.WriteAsync($"clinicalNotificationOutbox/{pending.Notification.NotificationId}", pending);
        push.Fail = false;
        await new ClinicalNotificationDispatcher(app.Store, events, push).DispatchAsync(default);
        Assert.Single(push.Recipients);
        Assert.Single((await app.Store.ListAsync<ClinicalNotification>("clinicalInbox/doc1")).Items);
    }

    [Fact]
    public async Task Shared_phone_token_cannot_deliver_previous_accounts_push()
    {
        using var app = new AccessTestApplication();
        var events = new ClinicalNotifications(app.Store);
        var token = new string('a', 30);
        await events.RegisterAsync("doc1", token, default);
        await events.RegisterAsync("patient", token, default);
        await events.EnqueueAsync("P1", "S1", "REPORT_READY");
        var push = new Push();
        await new ClinicalNotificationDispatcher(app.Store, events, push).DispatchAsync(default);
        Assert.Empty(push.Recipients);
        using var anonymous = app.As();
        Assert.Equal(HttpStatusCode.Unauthorized, (await anonymous.GetAsync("/api/notifications")).StatusCode);
    }

    [Fact]
    public async Task Draft_assessment_stays_private_and_repeated_release_notifies_only_linked_patient_once()
    {
        using var app = new AccessTestApplication();
        using var patient = app.As("patient");
        await patient.GetAsync("/api/access/me");
        using var nurse = app.As("nurse1");
        var link = await nurse.PutAsJsonAsync("/api/access/patients/P1/login", new
        { accountUid = "patient", confirmedEmail = "patient@example.test", expectedVersion = 1 });
        Assert.Equal(HttpStatusCode.OK, link.StatusCode);
        using var scope = app.Services.CreateScope();
        var ecg = scope.ServiceProvider.GetRequiredService<IEcgDataService>();
        var id = (await ecg.StartSessionAsync(new() { PatientId = "P1", DeviceId = "D1" })).SessionId;
        await ecg.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id));
        await ecg.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 1, ExpectedSampleCount = 4 });
        await ecg.AddDoctorNoteAsync(new() { SessionId = id, PatientId = "P1", DoctorId = "doc1", ClinicalImpression = "Draft assessment" });
        Assert.Empty((await app.Store.ListAsync<PendingClinicalNotification>("clinicalNotificationOutbox")).Items);
        using var doctor = app.As("doc1");
        Assert.Equal(HttpStatusCode.OK, (await doctor.PostAsync($"/api/doctor/sessions/{id}/release", null)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await doctor.PostAsync($"/api/doctor/sessions/{id}/release", null)).StatusCode);
        var item = Assert.Single((await app.Store.ListAsync<PendingClinicalNotification>("clinicalNotificationOutbox")).Items);
        Assert.Equal("RESULTS_RELEASED", item.Notification.Type);
        Assert.Equal("patient", item.Notification.RecipientUid);
        await new ClinicalNotificationDispatcher(app.Store, new(app.Store), new Push()).DispatchAsync(default);
        Assert.Single((await patient.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items);
    }

    [Fact]
    public async Task Full_telemetry_pipeline_generates_blob_pdf_and_dispatches_doctor_and_patient_clinical_notifications()
    {
        using var app = new AccessTestApplication();
        using var patientClient = app.As("patient");
        await patientClient.GetAsync("/api/access/me");
        using var nurseClient = app.As("nurse1");
        var link = await nurseClient.PutAsJsonAsync("/api/access/patients/P1/login", new
        { accountUid = "patient", confirmedEmail = "patient@example.test", expectedVersion = 1 });
        Assert.Equal(HttpStatusCode.OK, link.StatusCode);

        var events = new ClinicalNotifications(app.Store);
        await events.RegisterAsync("doc1", new string('d', 30), default);
        await events.RegisterAsync("patient", new string('p', 30), default);

        using var scope = app.Services.CreateScope();
        var ecg = scope.ServiceProvider.GetRequiredService<IEcgDataService>();
        var blobs = scope.ServiceProvider.GetRequiredService<IBlobStorageService>();

        var id = (await ecg.StartSessionAsync(new() { PatientId = "P1", DeviceId = "D1", PatientName = "Sipho Dlamini" })).SessionId;
        const int batchCount = 4;
        const int samplesPerBatch = 250;
        for (var seq = 0; seq < batchCount; seq++)
        {
            var samples = Enumerable.Range(0, samplesPerBatch)
                .Select(i => 2048 + (int)(260 * Math.Sin((seq * samplesPerBatch + i) * 0.12)))
                .ToList();
            await ecg.IngestRecordingBatchAsync(new IngestEcgPayloadDto
            {
                SessionId = id,
                DeviceId = "D1",
                BootId = "esp32-screen-boot",
                Sequence = seq,
                FirstSampleIndex = seq * samplesPerBatch,
                Timestamp = seq * 1000L,
                LeadConnected = true,
                EstimatedBpm = 72,
                FirmwareVer = "PulseLinkScreen-1.0",
                Samples = samples
            });
        }
        await ecg.CompleteRecordingAsync(id, new()
        {
            ExpectedBatchCount = batchCount,
            ExpectedSampleCount = batchCount * samplesPerBatch,
            RecordedDurationSeconds = 4
        });

        var staffPdf = await ecg.GenerateSessionReportPdfAsync(id);
        Assert.NotNull(staffPdf);
        Assert.Equal("%PDF-", Encoding.ASCII.GetString(staffPdf!, 0, 5));
        Assert.True(await blobs.ReportBlobExistsAsync(EcgDataService.StaffReportFileName(id)));

        var push = new Push();
        await new ClinicalNotificationDispatcher(app.Store, events, push).DispatchAsync(default);
        Assert.Equal(["doc1"], push.Recipients);

        using var doctorClient = app.As("doc1");
        var doctorInbox = (await doctorClient.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items;
        var reportNotification = Assert.Single(doctorInbox);
        Assert.Equal("REPORT_READY", reportNotification.Type);
        Assert.Equal(id, reportNotification.SessionId);
        Assert.False(string.IsNullOrWhiteSpace(reportNotification.PatientName));

        // Before release, patient sees session as un-released and receives zero clinical notifications.
        var preReleaseSessions = (await patientClient.GetFromJsonAsync<AccessPage<PatientSessionStatus>>("/api/patient/sessions"))!.Items;
        Assert.False(Assert.Single(preReleaseSessions).ResultsReleased);
        Assert.Empty((await patientClient.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items);

        await ecg.AddDoctorNoteAsync(new CreateDoctorNoteDto
        {
            SessionId = id,
            PatientId = "P1",
            DoctorId = "doc1",
            DoctorName = "Dr N. Khumalo",
            ClinicalImpression = "Sinus rhythm trace reviewed on uncalibrated prototype lead.",
            Recommendations = "Maintain hydration and monitor resting pulse.",
            FollowUpRequired = true,
            FollowUpInstructions = "Return to clinic in 14 days for follow-up review.",
            FollowUpInDays = 14,
            Prescriptions =
            [
                new MedicationEntry { Name = "Bisoprolol", Dosage = "5 mg", Frequency = "Once daily" }
            ]
        });

        var releaseResp = await doctorClient.PostAsync($"/api/doctor/sessions/{id}/release", null);
        Assert.Equal(HttpStatusCode.OK, releaseResp.StatusCode);
        Assert.True(await blobs.ReportBlobExistsAsync(EcgDataService.ReleasedPatientReportFileName(id)));

        push.Recipients.Clear();
        await new ClinicalNotificationDispatcher(app.Store, events, push).DispatchAsync(default);
        Assert.Equal(3, push.Recipients.Count(r => r == "patient"));

        var patientInbox = (await patientClient.GetFromJsonAsync<AccessPage<ClinicalNotification>>("/api/notifications"))!.Items;
        Assert.Equal(3, patientInbox.Count);
        Assert.Contains(patientInbox, n => n.Type == "RESULTS_RELEASED" && n.DoctorName == "Dr N. Khumalo");
        Assert.Contains(patientInbox, n => n.Type == "PRESCRIPTION_ISSUED" && n.PrescriptionSummary!.Contains("Bisoprolol 5 mg Once daily"));
        Assert.Contains(patientInbox, n => n.Type == "CLINICAL_ADVICE" && n.ClinicalAdvice!.Contains("Return to clinic in 14 days"));

        var postReleaseSessions = (await patientClient.GetFromJsonAsync<AccessPage<PatientSessionStatus>>("/api/patient/sessions"))!.Items;
        var releasedStatus = Assert.Single(postReleaseSessions);
        Assert.True(releasedStatus.ResultsReleased);
        Assert.True(releasedStatus.ReportAvailable);
    }
}
