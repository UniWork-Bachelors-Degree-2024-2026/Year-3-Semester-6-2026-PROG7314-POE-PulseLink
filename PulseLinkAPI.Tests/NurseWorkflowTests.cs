using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Models.Domain;
using PulseLinkAPI.Transport;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class NurseWorkflowTests
{
    [Fact]
    public async Task Missing_doctor_is_visible_and_online_assignment_is_scoped_versioned_and_retry_safe()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device);
        var patient = (await app.Store.ReadAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients/P1")).Value!;
        patient.PrimaryDoctorUid = "";
        await app.Store.WriteAsync($"{AccessDirectory.Root}/patients/P1", patient);
        var clinic = (await device.GetFromJsonAsync<ClinicPage>($"/api/device-workflow/clinic?grantId={grant.Id}"))!;
        var shown = Assert.Single(clinic.Patients.Items);
        Assert.True(shown.RequiresDoctorAssignment); Assert.Equal("", shown.DoctorName);
        var request = new ScreenDoctorAssignmentRequest { GrantId = grant.Id, PrimaryDoctorUid = "doc1", ExpectedVersion = 1 };
        var response = await device.PostAsJsonAsync("/api/device-workflow/patients/P1/doctor", request);
        response.EnsureSuccessStatusCode();
        var assigned = (await response.Content.ReadFromJsonAsync<ScreenPatient>())!;
        Assert.False(assigned.RequiresDoctorAssignment); Assert.Equal("doc1", assigned.PrimaryDoctorUid); Assert.Equal(2, assigned.Version);
        var retry = await device.PostAsJsonAsync("/api/device-workflow/patients/P1/doctor", request);
        Assert.Equal(2, (await retry.Content.ReadFromJsonAsync<ScreenPatient>())!.Version);
        request.PrimaryDoctorUid = "doc2";
        Assert.Equal(HttpStatusCode.Conflict, (await device.PostAsJsonAsync("/api/device-workflow/patients/P1/doctor", request)).StatusCode);
        request.ExpectedVersion = 2;
        Assert.Equal(HttpStatusCode.Conflict, (await device.PostAsJsonAsync("/api/device-workflow/patients/P1/doctor", request)).StatusCode);
    }

    [Fact]
    public async Task Suspended_doctor_remains_repairable_without_allowing_cross_site_or_expired_shift_changes()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device);
        var doctor = (await app.Store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/doc1")).Value!;
        doctor.Status = "suspended"; await app.Store.WriteAsync($"{AccessDirectory.Root}/accounts/doc1", doctor);
        var clinic = (await device.GetFromJsonAsync<ClinicPage>($"/api/device-workflow/clinic?grantId={grant.Id}"))!;
        Assert.True(Assert.Single(clinic.Patients.Items).RequiresDoctorAssignment);
        var request = new ScreenDoctorAssignmentRequest { GrantId = grant.Id, PrimaryDoctorUid = "docOther", ExpectedVersion = 1 };
        Assert.Equal(HttpStatusCode.BadRequest, (await device.PostAsJsonAsync("/api/device-workflow/patients/P1/doctor", request)).StatusCode);
        request.PrimaryDoctorUid = "doc2";
        await app.Store.WriteAsync($"{AccessDirectory.Root}/patients/P2", new ClinicalPatient { Id = "P2", SiteId = "S2", Version = 1 });
        Assert.Equal(HttpStatusCode.Forbidden, (await device.PostAsJsonAsync("/api/device-workflow/patients/P2/doctor", request)).StatusCode);
        var response = await device.PostAsJsonAsync("/api/device-workflow/patients/P1/doctor", request);
        response.EnsureSuccessStatusCode();
        (await device.DeleteAsync("/api/device-workflow/authorisation")).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Forbidden, (await device.PostAsJsonAsync("/api/device-workflow/patients/P1/doctor", request)).StatusCode);
    }

    [Fact]
    public async Task Intake_rejects_blank_names_future_dates_and_non_doctors_without_creating_a_record()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device);
        var id = Guid.NewGuid();
        var request = new ScreenPatientRequest { GrantId = grant.Id, RegisteredAtUtc = DateTimeOffset.UtcNow,
            Patient = new PatientRegistrationRequest { RegistrationId = id, SiteId = "S1", PrimaryDoctorUid = "doc1", FullName = "   " } };
        Assert.Equal(HttpStatusCode.BadRequest, (await device.PostAsJsonAsync("/api/device-workflow/patients", request)).StatusCode);
        request.Patient.FullName = "Intake test"; request.Patient.DateOfBirth = DateOnly.FromDateTime(DateTime.UtcNow.AddDays(1));
        Assert.Equal(HttpStatusCode.BadRequest, (await device.PostAsJsonAsync("/api/device-workflow/patients", request)).StatusCode);
        request.Patient.DateOfBirth = null; request.Patient.PrimaryDoctorUid = "nurse1";
        Assert.Equal(HttpStatusCode.BadRequest, (await device.PostAsJsonAsync("/api/device-workflow/patients", request)).StatusCode);
        Assert.Null((await app.Store.ReadAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients/PAT-{id:N}")).Value);
    }
    [Fact]
    public async Task Nurse_can_reuse_own_active_approval_without_a_code_or_expiry_extension()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device);
        using var nurse = app.As("nurse1");
        var restored = (await nurse.GetFromJsonAsync<NurseGrant>("/api/access/devices/D1/authorisation"))!;
        Assert.Equal(grant.Id, restored.Id); Assert.Equal(grant.ExpiresAtUtc, restored.ExpiresAtUtc);
        await app.Store.WriteAsync($"{AccessDirectory.Root}/accounts/nurse2", new AccessAccount
        { Uid = "nurse2", Role = "nurse", Status = "approved", SiteIds = ["S1"], Version = 1 });
        using var colleague = app.As("nurse2"); using var otherSite = app.As("nurseOther"); using var doctor = app.As("doc1");
        Assert.Equal(HttpStatusCode.NoContent, (await colleague.GetAsync("/api/access/devices/D1/authorisation")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await otherSite.GetAsync("/api/access/devices/D1/authorisation")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await doctor.GetAsync("/api/access/devices/D1/authorisation")).StatusCode);
        (await device.DeleteAsync("/api/device-workflow/authorisation")).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.NoContent, (await nurse.GetAsync("/api/access/devices/D1/authorisation")).StatusCode);
    }

    [Fact]
    public async Task Restoring_an_expired_or_revoked_nurse_shift_cannot_keep_it_active()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device); using var nurse = app.As("nurse1");
        var challenge = (await app.Store.ReadAsync<NurseChallenge>("screenAuthorisations/D1")).Value!;
        await app.Store.WriteAsync("screenAuthorisations/D1", challenge with { Grant = grant with { ExpiresAtUtc = DateTimeOffset.UtcNow.AddSeconds(-1) } });
        Assert.Equal(HttpStatusCode.NoContent, (await nurse.GetAsync("/api/access/devices/D1/authorisation")).StatusCode);
        await Approve(app, device);
        var account = (await app.Store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/nurse1")).Value!;
        account.Status = "suspended"; await app.Store.WriteAsync($"{AccessDirectory.Root}/accounts/nurse1", account);
        Assert.Equal(HttpStatusCode.Forbidden, (await nurse.GetAsync("/api/access/devices/D1/authorisation")).StatusCode);
    }
    [Fact]
    public async Task Preflight_checks_current_assignment_without_creating_or_freezing_a_recording()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device);
        var request = new ScreenRecordingRequest
        {
            GrantId = grant.Id, RecordingId = Guid.NewGuid(), PatientId = "P1",
            PatientVersion = 1, PrimaryDoctorUid = "doc1", StartedAtUtc = DateTimeOffset.UtcNow
        };
        Assert.Equal(HttpStatusCode.NoContent, (await device.PostAsJsonAsync("/api/device-workflow/recordings/check", request)).StatusCode);
        var sessionId = "SESSION-" + request.RecordingId.ToString("N");
        Assert.Null((await app.Store.ReadAsync<RecordingManifest>($"{EcgDataService.RecordingRoot}/{sessionId}")).Value);
        Assert.Null((await app.Store.ReadAsync<RecordingUploadPermit>($"deviceUploadPermits/{sessionId}")).Value);
        request.StartedAtUtc = DateTimeOffset.UtcNow;
        (await device.PostAsJsonAsync("/api/device-workflow/recordings", request)).EnsureSuccessStatusCode();
        Assert.Equal(request.StartedAtUtc.UtcDateTime,
            (await app.Store.ReadAsync<RecordingManifest>($"{EcgDataService.RecordingRoot}/{sessionId}")).Value!.StartTimeUtc);
    }

    [Fact]
    public async Task Preflight_rejects_changed_doctor_short_shift_and_logged_out_permission()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device);
        var request = new ScreenRecordingRequest
        {
            GrantId = grant.Id, RecordingId = Guid.NewGuid(), PatientId = "P1",
            PatientVersion = 1, PrimaryDoctorUid = "doc2", StartedAtUtc = DateTimeOffset.UtcNow
        };
        Assert.Equal(HttpStatusCode.Conflict, (await device.PostAsJsonAsync("/api/device-workflow/recordings/check", request)).StatusCode);
        request.PrimaryDoctorUid = "doc1";
        var challenge = (await app.Store.ReadAsync<NurseChallenge>("screenAuthorisations/D1")).Value!;
        var ending = grant with { ExpiresAtUtc = DateTimeOffset.UtcNow.AddSeconds(60) };
        await app.Store.WriteAsync("screenAuthorisations/D1", challenge with { Grant = ending });
        await app.Store.WriteAsync($"screenNurseGrants/{grant.Id}", ending);
        Assert.Equal(HttpStatusCode.Forbidden, (await device.PostAsJsonAsync("/api/device-workflow/recordings/check", request)).StatusCode);
        (await device.DeleteAsync("/api/device-workflow/authorisation")).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Forbidden, (await device.PostAsJsonAsync("/api/device-workflow/recordings/check", request)).StatusCode);
    }

    [Fact]
    public async Task Expired_codes_and_grants_used_on_another_device_are_rejected()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (code, grant) = await Approve(app, device);
        var challenge = (await app.Store.ReadAsync<NurseChallenge>("screenAuthorisations/D1")).Value!;
        await app.Store.WriteAsync("screenAuthorisations/D1", challenge with { ExpiresAtUtc = DateTimeOffset.UtcNow.AddMinutes(-1) });
        using var nurse = app.As("nurse1");
        Assert.Equal(HttpStatusCode.Gone,
            (await nurse.PostAsJsonAsync("/api/access/devices/D1/authorisation", new ScreenApprovalRequest { Code = code.Code })).StatusCode);
        await app.Store.WriteAsync($"{AccessDirectory.Root}/devices/D2", new SiteDevice { DeviceId = "D2", SiteId = "S1" });
        using var admin = app.As("admin");
        var reply = await admin.PostAsync("/api/admin/devices/D2/credential", null); reply.EnsureSuccessStatusCode();
        var key = (await reply.Content.ReadFromJsonAsync<IssuedDeviceCredential>())!;
        using var other = app.As(); other.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Device", $"D2:{key.Token}");
        var request = new ScreenRecordingRequest
        {
            GrantId = grant.Id, RecordingId = Guid.NewGuid(), PatientId = "P1",
            PatientVersion = 1, PrimaryDoctorUid = "doc1", StartedAtUtc = DateTimeOffset.UtcNow
        };
        Assert.Equal(HttpStatusCode.Forbidden, (await other.PostAsJsonAsync("/api/device-workflow/recordings", request)).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await other.PostAsJsonAsync("/api/device-workflow/recordings/check", request)).StatusCode);
    }
    private static async Task<HttpClient> Device(AccessTestApplication app)
    {
        using var admin = app.As("admin");
        var reply = await admin.PostAsync("/api/admin/devices/D1/credential", null); reply.EnsureSuccessStatusCode();
        var key = (await reply.Content.ReadFromJsonAsync<IssuedDeviceCredential>())!;
        var client = app.As(); client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Device", $"D1:{key.Token}"); return client;
    }
    private static async Task<(ScreenCode Code, NurseGrant Grant)> Approve(AccessTestApplication app, HttpClient device)
    {
        var begin = await device.PostAsync("/api/device-workflow/authorisation", null); begin.EnsureSuccessStatusCode();
        var code = (await begin.Content.ReadFromJsonAsync<ScreenCode>())!;
        using var nurse = app.As("nurse1");
        var approved = await nurse.PostAsJsonAsync("/api/access/devices/D1/authorisation", new ScreenApprovalRequest { Code = code.Code });
        approved.EnsureSuccessStatusCode(); return (code, (await approved.Content.ReadFromJsonAsync<NurseGrant>())!);
    }
    [Fact]
    public async Task Approval_is_site_scoped_and_patient_routes_require_current_nurse_permission()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        Assert.Equal(HttpStatusCode.Forbidden, (await device.GetAsync("/api/device-workflow/clinic?grantId=missing")).StatusCode);
        var reply = await device.PostAsync("/api/device-workflow/authorisation", null);
        var code = (await reply.Content.ReadFromJsonAsync<ScreenCode>())!;
        using var other = app.As("nurseOther"); using var doctor = app.As("doc1");
        Assert.Equal(HttpStatusCode.Forbidden, (await other.PostAsJsonAsync("/api/access/devices/D1/authorisation", new ScreenApprovalRequest { Code = code.Code })).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await doctor.PostAsJsonAsync("/api/access/devices/D1/authorisation", new ScreenApprovalRequest { Code = code.Code })).StatusCode);
        using var nurse = app.As("nurse1");
        var approved = await nurse.PostAsJsonAsync("/api/access/devices/D1/authorisation", new ScreenApprovalRequest { Code = code.Code }); approved.EnsureSuccessStatusCode();
        var grant = (await approved.Content.ReadFromJsonAsync<NurseGrant>())!;
        Assert.Equal(TimeSpan.FromHours(8), grant.ExpiresAtUtc - grant.ApprovedAtUtc);
        var clinic = (await device.GetFromJsonAsync<ClinicPage>($"/api/device-workflow/clinic?grantId={grant.Id}"))!;
        Assert.Equal("P1", Assert.Single(clinic.Patients.Items).Id);
        Assert.DoesNotContain(clinic.Doctors.Items, d => d.Uid == "docOther");
        Assert.Equal(HttpStatusCode.Unauthorized, (await nurse.GetAsync($"/api/device-workflow/clinic?grantId={grant.Id}")).StatusCode);
        (await device.DeleteAsync("/api/device-workflow/authorisation")).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Forbidden, (await device.GetAsync($"/api/device-workflow/clinic?grantId={grant.Id}")).StatusCode);
    }
    [Fact]
    public async Task Wrong_codes_lock_after_five_attempts_and_reapproval_rotates_the_active_shift()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var begin = await device.PostAsync("/api/device-workflow/authorisation", null);
        var code = (await begin.Content.ReadFromJsonAsync<ScreenCode>())!;
        using var nurse = app.As("nurse1");
        for (var i = 0; i < 5; i++) Assert.Equal(HttpStatusCode.Forbidden,
            (await nurse.PostAsJsonAsync("/api/access/devices/D1/authorisation", new ScreenApprovalRequest { Code = code.Code == "000000" ? "000001" : "000000" })).StatusCode);
        Assert.Equal(HttpStatusCode.Gone, (await nurse.PostAsJsonAsync("/api/access/devices/D1/authorisation", new ScreenApprovalRequest { Code = code.Code })).StatusCode);
        var (_, first) = await Approve(app, device); var (_, second) = await Approve(app, device);
        Assert.NotEqual(first.Id, second.Id);
        Assert.Equal(HttpStatusCode.Forbidden, (await device.GetAsync($"/api/device-workflow/clinic?grantId={first.Id}")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await device.GetAsync($"/api/device-workflow/clinic?grantId={second.Id}")).StatusCode);
    }
    [Fact]
    public async Task Offline_registration_and_recording_retries_preserve_patient_operator_and_capture_identity()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app);
        var (_, grant) = await Approve(app, device);
        var patient = new ScreenPatientRequest
        {
            GrantId = grant.Id,
            RegisteredAtUtc = DateTimeOffset.UtcNow,
            Patient = new PatientRegistrationRequest { RegistrationId = Guid.NewGuid(), FullName = "Offline test patient", SiteId = "S1", PrimaryDoctorUid = "doc1" }
        };
        var first = await device.PostAsJsonAsync("/api/device-workflow/patients", patient); first.EnsureSuccessStatusCode();
        var saved = (await first.Content.ReadFromJsonAsync<ClinicalPatient>())!;
        var retry = await device.PostAsJsonAsync("/api/device-workflow/patients", patient); retry.EnsureSuccessStatusCode();
        Assert.Equal(saved.Id, (await retry.Content.ReadFromJsonAsync<ClinicalPatient>())!.Id);
        patient.Patient.FullName = "Different patient";
        Assert.Equal(HttpStatusCode.Conflict, (await device.PostAsJsonAsync("/api/device-workflow/patients", patient)).StatusCode);
        var recording = new ScreenRecordingRequest
        {
            GrantId = grant.Id,
            RecordingId = Guid.NewGuid(),
            PatientId = saved.Id,
            PatientVersion = saved.Version,
            PrimaryDoctorUid = saved.PrimaryDoctorUid,
            StartedAtUtc = DateTimeOffset.UtcNow
        };
        var started = await device.PostAsJsonAsync("/api/device-workflow/recordings", recording); started.EnsureSuccessStatusCode();
        var id = (await started.Content.ReadFromJsonAsync<StartSessionResponseDto>())!.SessionId;
        (await device.PostAsJsonAsync("/api/device-workflow/recordings", recording)).EnsureSuccessStatusCode();
        var manifest = (await app.Store.ReadAsync<RecordingManifest>($"{EcgDataService.RecordingRoot}/{id}")).Value!;
        Assert.Equal("nurse1", manifest.OperatorUid); Assert.Equal(grant.Id, manifest.AuthorisationGrantId);
        Assert.Equal(recording.StartedAtUtc.UtcDateTime, manifest.StartTimeUtc);
        Assert.Equal(HttpStatusCode.OK, (await device.GetAsync($"/api/device-transport/recordings/{id}/progress")).StatusCode);
        recording.StartedAtUtc = recording.StartedAtUtc.AddSeconds(-1);
        Assert.NotEqual(HttpStatusCode.OK, (await device.PostAsJsonAsync("/api/device-workflow/recordings", recording)).StatusCode);
    }
    [Fact]
    public async Task Expired_capture_permission_allows_bounded_old_action_sync_but_not_new_work_or_suspended_nurses()
    {
        using var app = new AccessTestApplication(); using var device = await Device(app); var (_, grant) = await Approve(app, device);
        var old = grant with { ApprovedAtUtc = DateTimeOffset.UtcNow.AddHours(-9), ExpiresAtUtc = DateTimeOffset.UtcNow.AddHours(-1) };
        await app.Store.WriteAsync($"screenNurseGrants/{grant.Id}", old);
        var challenge = (await app.Store.ReadAsync<NurseChallenge>("screenAuthorisations/D1")).Value!;
        await app.Store.WriteAsync("screenAuthorisations/D1", challenge with { Grant = old });
        Assert.Equal(HttpStatusCode.Forbidden, (await device.GetAsync($"/api/device-workflow/clinic?grantId={grant.Id}")).StatusCode);
        var action = new ScreenRecordingRequest
        {
            GrantId = grant.Id,
            RecordingId = Guid.NewGuid(),
            PatientId = "P1",
            PatientVersion = 1,
            PrimaryDoctorUid = "doc1",
            StartedAtUtc = DateTimeOffset.UtcNow.AddHours(-2)
        };
        (await device.PostAsJsonAsync("/api/device-workflow/recordings", action)).EnsureSuccessStatusCode();
        action.RecordingId = Guid.NewGuid(); action.StartedAtUtc = DateTimeOffset.UtcNow;
        Assert.Equal(HttpStatusCode.Forbidden, (await device.PostAsJsonAsync("/api/device-workflow/recordings", action)).StatusCode);
        action.StartedAtUtc = DateTimeOffset.UtcNow.AddHours(-2);
        var nurse = (await app.Store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/nurse1")).Value!;
        nurse.Status = "suspended"; await app.Store.WriteAsync($"{AccessDirectory.Root}/accounts/nurse1", nurse);
        Assert.Equal(HttpStatusCode.Forbidden, (await device.PostAsJsonAsync("/api/device-workflow/recordings", action)).StatusCode);
    }
}
