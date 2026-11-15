using System.Net;
using System.Net.Http.Json;
using System.Text;
using Microsoft.Extensions.DependencyInjection;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;
using PulseLinkAPI.Transport;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class DoctorChartTests
{
    private static async Task<string> CreateSession(AccessTestApplication app, string patientId = "P1", bool completed = true)
    {
        using var scope = app.Services.CreateScope();
        var ecg = scope.ServiceProvider.GetRequiredService<IEcgDataService>();
        var id = (await ecg.StartSessionAsync(new() { PatientId = patientId, DeviceId = "D1", PatientName = "Test Patient" })).SessionId;
        if (completed)
        {
            await ecg.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id));
            await ecg.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 1, ExpectedSampleCount = 4, RecordedDurationSeconds = 90 });
        }
        return id;
    }

    [Fact]
    public async Task Assigned_patients_and_dashboard_reflect_clinical_roster_without_alerts_and_exclude_unassigned_or_admin_callers()
    {
        using var app = new AccessTestApplication();
        await app.Store.WriteAsync("accessDirectory/patients/P2", new ClinicalPatient
        {
            Id = "P2",
            FullName = "Unassigned Patient",
            SiteId = "S1",
            PrimaryDoctorUid = "doc2",
            Version = 1
        });
        await app.Store.WriteAsync("accessDirectory/patients/P3", new ClinicalPatient
        {
            Id = "P3",
            FullName = "Additional Doctor Patient",
            SiteId = "S1",
            PrimaryDoctorUid = "doc2",
            AdditionalDoctorUids = ["doc1"],
            Version = 1
        });

        using var doc1 = app.As("doc1");
        var assignedFromDoctorRoute = await doc1.GetFromJsonAsync<AccessPage<DoctorPatientSummaryDto>>("/api/doctor/patients");
        var assignedFromAccessRoute = await doc1.GetFromJsonAsync<AccessPage<DoctorPatientSummaryDto>>("/api/access/patients");
        Assert.NotNull(assignedFromDoctorRoute);
        Assert.NotNull(assignedFromAccessRoute);
        Assert.Equal(["P1", "P3"], assignedFromDoctorRoute!.Items.Select(p => p.PatientId).OrderBy(x => x).ToArray());
        Assert.Equal(["P1", "P3"], assignedFromAccessRoute!.Items.Select(p => p.PatientId).OrderBy(x => x).ToArray());
        Assert.All(assignedFromDoctorRoute.Items, p => Assert.Equal(0, p.ActiveAlertsCount));

        var dashboard = await doc1.GetFromJsonAsync<DoctorDashboardDto>("/api/doctor/dashboard");
        Assert.NotNull(dashboard);
        Assert.Equal(2, dashboard!.AssignedPatientsCount);
        Assert.Equal(0, dashboard.CriticalAlertsCount);

        using var nurse1 = app.As("nurse1");
        var sitePatients = await nurse1.GetFromJsonAsync<AccessPage<DoctorPatientSummaryDto>>("/api/doctor/patients");
        Assert.Equal(["P1", "P2", "P3"], sitePatients!.Items.Select(p => p.PatientId).OrderBy(x => x).ToArray());

        // Administrative role management and patient accounts must never gain clinical chart or review access
        foreach (var callerUid in new[] { "admin", "patient" })
        {
            using var client = app.As(callerUid);
            Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/doctor/dashboard")).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/doctor/patients")).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/access/patients")).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/doctor/patients/P1/chart")).StatusCode);
            Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/doctor/patients/P1/visits")).StatusCode);
        }

        // Unassigned doctor cannot open P1's chart or visits
        using var doc2 = app.As("doc2");
        Assert.Equal(HttpStatusCode.Forbidden, (await doc2.GetAsync("/api/doctor/patients/P1/chart")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await doc2.GetAsync("/api/doctor/patients/P1/visits")).StatusCode);
    }

    [Fact]
    public async Task Dashboard_counts_and_session_review_require_completed_recordings()
    {
        using var app = new AccessTestApplication();
        var completedId = await CreateSession(app, "P1", completed: true);
        var activeId = await CreateSession(app, "P1", completed: false);

        using var doc1 = app.As("doc1");
        var dashboard = await doc1.GetFromJsonAsync<DoctorDashboardDto>("/api/doctor/dashboard");
        Assert.NotNull(dashboard);
        Assert.Equal(1, dashboard!.AssignedPatientsCount);
        Assert.Equal(1, dashboard.CompletedSessionsCount);
        Assert.Equal(1, dashboard.AwaitingReviewCount);
        Assert.Equal(0, dashboard.DraftAssessedCount);
        Assert.Equal(1, dashboard.PendingReleaseCount);
        Assert.Equal(0, dashboard.ReleasedCount);

        var queueItem = Assert.Single(dashboard.CompletedSessionQueue);
        Assert.Equal(completedId, queueItem.SessionId);
        Assert.Equal("AwaitingReview", queueItem.ReviewState);

        // Incomplete session cannot be opened in the completed-session review endpoint
        Assert.Equal(HttpStatusCode.Conflict, (await doc1.GetAsync($"/api/doctor/sessions/{activeId}/review")).StatusCode);

        // Completed session opens cleanly and prepares staff report status
        var review = await doc1.GetFromJsonAsync<DoctorSessionReviewDto>($"/api/doctor/sessions/{completedId}/review");
        Assert.NotNull(review);
        Assert.Equal(completedId, review!.Session.SessionId);
        Assert.Equal("AwaitingReview", review.ReviewState);
        Assert.True(review.StaffReportStatus.ReportAvailable);
        Assert.Equal("Ready", review.StaffReportStatus.State);
    }

    [Fact]
    public async Task Notes_visits_prescriptions_and_follow_up_enforce_server_controlled_authorship_and_timestamps()
    {
        using var app = new AccessTestApplication();
        var sessionId = await CreateSession(app, "P1", completed: true);
        using var doc1 = app.As("doc1");

        var before = DateTimeOffset.UtcNow.AddSeconds(-5);
        var noteResp = await doc1.PostAsJsonAsync("/api/doctor/notes", new CreateDoctorNoteDto
        {
            SessionId = sessionId,
            PatientId = "P1",
            DoctorId = "spoofed-doctor-id",
            DoctorName = "Spoofed Doctor Name",
            ClinicalImpression = "Sinus rhythm with occasional premature beats on single-lead trace.",
            Recommendations = "Continue beta-blocker therapy and monitor blood pressure at clinic.",
            FollowUpRequired = true,
            FollowUpInstructions = "Return to rural clinic in 14 days for repeat 90s ECG.",
            FollowUpInDays = 14,
            Prescriptions =
            [
                new MedicationEntry { Name = "Atenolol", Dosage = "50mg", Frequency = "Once daily" }
            ]
        });
        Assert.Equal(HttpStatusCode.Created, noteResp.StatusCode);
        var createdNote = await noteResp.Content.ReadFromJsonAsync<DoctorNoteDto>();
        Assert.NotNull(createdNote);
        Assert.Equal("doc1", createdNote!.DoctorId);
        Assert.Equal("doc1", createdNote.DoctorName);
        Assert.True(createdNote.FollowUpRequired);
        Assert.Equal("Return to rural clinic in 14 days for repeat 90s ECG.", createdNote.FollowUpInstructions);
        Assert.Equal(14, createdNote.FollowUpInDays);
        Assert.Single(createdNote.Prescriptions);
        Assert.Equal("Atenolol", createdNote.Prescriptions[0].Name);

        var visitResp = await doc1.PostAsJsonAsync("/api/doctor/patients/P1/visits", new CreateClinicalVisitDto
        {
            SessionId = sessionId,
            VisitType = "RemoteReview",
            ChiefComplaint = "Palpitations reported at rural clinic",
            Assessment = "Reviewed 90-second single-lead ECG; stable ventricular rate.",
            Recommendations = "Maintain current regimen.",
            FollowUpRequired = true,
            FollowUpInstructions = "Clinic nurse to recheck in 2 weeks.",
            FollowUpInDays = 14,
            Prescriptions =
            [
                new MedicationEntry { Name = "Aspirin", Dosage = "81mg", Frequency = "Once daily" }
            ],
            DoctorId = "spoofed-visit-doc",
            DoctorName = "Spoofed Visit Name",
            RecordedAtUtc = new DateTimeOffset(2000, 1, 1, 0, 0, 0, TimeSpan.Zero)
        });
        Assert.Equal(HttpStatusCode.Created, visitResp.StatusCode);
        var createdVisit = await visitResp.Content.ReadFromJsonAsync<ClinicalVisitDto>();
        Assert.NotNull(createdVisit);
        Assert.Equal("doc1", createdVisit!.DoctorId);
        Assert.Equal("doc1", createdVisit.DoctorName);
        Assert.Equal("S1", createdVisit.SiteId);
        Assert.True(createdVisit.RecordedAtUtc >= before);
        Assert.Single(createdVisit.Prescriptions);

        // Nurses, unassigned doctors, and administrators cannot create clinical visits
        foreach (var deniedUid in new[] { "nurse1", "doc2", "admin", "patient" })
        {
            using var denied = app.As(deniedUid);
            Assert.Equal(HttpStatusCode.Forbidden, (await denied.PostAsJsonAsync("/api/doctor/patients/P1/visits", new CreateClinicalVisitDto
            {
                VisitType = "ClinicFollowUp",
                Assessment = "Attempted unauthorised visit entry"
            })).StatusCode);
        }

        var chart = await doc1.GetFromJsonAsync<PatientChartDto>("/api/doctor/patients/P1/chart");
        Assert.NotNull(chart);
        Assert.Single(chart!.PriorVisits);
        Assert.Equal(createdVisit.VisitId, chart.PriorVisits[0].VisitId);
        Assert.True(chart.Patient.FollowUpRequired);
        Assert.Equal(14, chart.Patient.FollowUpInDays);
    }

    [Fact]
    public async Task Draft_and_release_lifecycle_tracks_unreleased_drafts_and_protects_released_patient_snapshot()
    {
        using var app = new AccessTestApplication();
        // Link verified patient login to P1
        using (var patientSignIn = app.As("patient"))
        {
            Assert.Equal(HttpStatusCode.OK, (await patientSignIn.GetAsync("/api/access/me")).StatusCode);
        }
        using (var nurse = app.As("nurse1"))
        {
            Assert.Equal(HttpStatusCode.OK, (await nurse.PutAsJsonAsync("/api/access/patients/P1/login",
                new { accountUid = "patient", confirmedEmail = "patient@example.test", expectedVersion = 1 })).StatusCode);
        }

        var sessionId = await CreateSession(app, "P1", completed: true);
        using var doc1 = app.As("doc1");
        using var patient = app.As("patient");

        // 1. Save initial draft note with prescription and follow-up
        await doc1.PostAsJsonAsync("/api/doctor/notes", new CreateDoctorNoteDto
        {
            SessionId = sessionId,
            PatientId = "P1",
            ClinicalImpression = "Initial released assessment",
            Recommendations = "Continue clinic checkups",
            FollowUpRequired = true,
            FollowUpInstructions = "Follow up in 7 days",
            FollowUpInDays = 7,
            Prescriptions = [new MedicationEntry { Name = "Enalapril", Dosage = "10mg", Frequency = "Daily" }]
        });

        var draftReview = await doc1.GetFromJsonAsync<DoctorSessionReviewDto>($"/api/doctor/sessions/{sessionId}/review");
        Assert.Equal("DraftSaved", draftReview!.ReviewState);
        Assert.False(draftReview.ReleaseState.ResultsReleased);
        Assert.True(draftReview.ReleaseState.HasUnreleasedDrafts);

        var draftDashboard = await doc1.GetFromJsonAsync<DoctorDashboardDto>("/api/doctor/dashboard");
        Assert.Equal(0, draftDashboard!.AwaitingReviewCount);
        Assert.Equal(1, draftDashboard.DraftAssessedCount);
        Assert.Equal(1, draftDashboard.PendingReleaseCount);
        Assert.Equal(0, draftDashboard.ReleasedCount);

        // 2. Explicitly release via /api/doctor/sessions/{sessionId}/release
        var releaseResp = await doc1.PostAsync($"/api/doctor/sessions/{sessionId}/release", null);
        Assert.Equal(HttpStatusCode.OK, releaseResp.StatusCode);

        var releasedReview = await doc1.GetFromJsonAsync<DoctorSessionReviewDto>($"/api/doctor/sessions/{sessionId}/review");
        Assert.Equal("Released", releasedReview!.ReviewState);
        Assert.True(releasedReview.ReleaseState.ResultsReleased);
        Assert.False(releasedReview.ReleaseState.HasUnreleasedDrafts);

        var patientSnapshot = await patient.GetFromJsonAsync<PatientRelease>($"/api/patient/sessions/{sessionId}/results");
        Assert.NotNull(patientSnapshot);
        Assert.Single(patientSnapshot!.Notes);
        Assert.Contains(patientSnapshot.CarePlan.Medications, m => m.Name == "Enalapril");

        var patientPdfBytes = await (await patient.GetAsync($"/api/patient/sessions/{sessionId}/report/pdf")).Content.ReadAsByteArrayAsync();
        var patientPdfText = Encoding.Latin1.GetString(patientPdfBytes);
        Assert.Contains("Enalapril", patientPdfText);
        Assert.Contains("Follow up in 7 days", patientPdfText);

        // 3. Add a post-release draft note and update care plan without re-releasing
        await doc1.PostAsJsonAsync("/api/doctor/notes", new CreateDoctorNoteDto
        {
            SessionId = sessionId,
            PatientId = "P1",
            ClinicalImpression = "Post-release internal draft note",
            Prescriptions = [new MedicationEntry { Name = "Furosemide", Dosage = "20mg", Frequency = "Daily" }]
        });
        await doc1.PutAsJsonAsync("/api/access/patients/P1/care-plan", new CarePlan
        {
            PrimaryCondition = "Updated draft condition",
            ClinicalInstructions = "Draft instructions not yet released"
        });

        var postDraftReview = await doc1.GetFromJsonAsync<DoctorSessionReviewDto>($"/api/doctor/sessions/{sessionId}/review");
        Assert.Equal("ReleasedWithDrafts", postDraftReview!.ReviewState);
        Assert.True(postDraftReview.ReleaseState.ResultsReleased);
        Assert.True(postDraftReview.ReleaseState.HasUnreleasedDrafts);
        Assert.Equal(1, postDraftReview.ReleaseState.ReleasedNoteCount);
        Assert.Equal(2, postDraftReview.ReleaseState.DraftNoteCount);

        // Patient snapshot remains isolated from the post-release draft
        var isolatedSnapshot = await patient.GetFromJsonAsync<PatientRelease>($"/api/patient/sessions/{sessionId}/results");
        Assert.Single(isolatedSnapshot!.Notes);
        Assert.DoesNotContain(isolatedSnapshot.CarePlan.Medications, m => m.Name == "Furosemide");
        Assert.NotEqual("Draft instructions not yet released", isolatedSnapshot.CarePlan.ClinicalInstructions);
    }

    [Fact]
    public async Task Doctor_can_send_coordination_message_to_nurse_device_and_nurse_acknowledges_receipt()
    {
        using var app = new AccessTestApplication();
        var sessionId = await CreateSession(app);

        // 1. Unauthorised callers (nurse, patient, unassigned doctor) cannot send coordination messages
        using var nurse = app.As("nurse1");
        using var doc2 = app.As("doc2");
        var msgPayload = new SendDoctorMessageDto("Please check electrode contact on LA before second recording.");
        Assert.Equal(HttpStatusCode.Forbidden, (await nurse.PostAsJsonAsync($"/api/doctor/sessions/{sessionId}/messages", msgPayload)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await doc2.PostAsJsonAsync($"/api/doctor/sessions/{sessionId}/messages", msgPayload)).StatusCode);

        // 2. Assigned doctor sends coordination message
        using var doc1 = app.As("doc1");
        var sendResp = await doc1.PostAsJsonAsync($"/api/doctor/sessions/{sessionId}/messages", msgPayload);
        Assert.Equal(HttpStatusCode.Created, sendResp.StatusCode);
        var created = await sendResp.Content.ReadFromJsonAsync<PulseLinkAPI.Transport.DeviceEvent>();
        Assert.NotNull(created);
        Assert.Equal("doctor-coordination", created!.Kind);
        Assert.Equal(msgPayload.Message, created.Message);
        Assert.Null(created.ReceivedAtUtc);

        // 3. Coordination message is retrievable by clinical staff for session
        var sessionMessages = await doc1.GetFromJsonAsync<List<PulseLinkAPI.Transport.DeviceEvent>>($"/api/doctor/sessions/{sessionId}/messages");
        Assert.Single(sessionMessages!);
        Assert.Equal(created.Id, sessionMessages![0].Id);

        // 4. Device queries events
        var cred = (await (await app.As("admin").PostAsync("/api/admin/devices/D1/credential", null)).Content.ReadFromJsonAsync<IssuedDeviceCredential>())!;
        using var deviceClient = app.As();
        deviceClient.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Device", $"{cred.DeviceId}:{cred.Token}");
        var eventsPage = await deviceClient.GetFromJsonAsync<AccessPage<PulseLinkAPI.Transport.DeviceEvent>>("/api/device-transport/events");
        Assert.NotNull(eventsPage);
        var devEvent = Assert.Single(eventsPage!.Items);
        Assert.Equal(created.Id, devEvent.Id);
        Assert.Equal(msgPayload.Message, devEvent.Message);

        // 5. Device acknowledges receipt
        var ackResp = await deviceClient.PostAsync($"/api/device-transport/events/{created.Id}/received", null);
        Assert.Equal(HttpStatusCode.NoContent, ackResp.StatusCode);

        // 6. Event now shows received
        var afterAckEvents = await deviceClient.GetFromJsonAsync<AccessPage<PulseLinkAPI.Transport.DeviceEvent>>("/api/device-transport/events");
        Assert.Empty(afterAckEvents!.Items);
    }
}
