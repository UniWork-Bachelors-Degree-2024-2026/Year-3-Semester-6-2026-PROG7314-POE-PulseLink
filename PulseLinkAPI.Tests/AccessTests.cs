using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Authentication;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging.Abstractions;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.Domain;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class MemoryAccessStore : IAccessStore
{
    private readonly Dictionary<string, (string Json, int Version)> records = [];
    private readonly object gate = new();
    public Task<StoredValue<T>> ReadAsync<T>(string path, CancellationToken ct = default)
    {
        lock (gate)
        {
            if (!records.TryGetValue(path, out var row))

                return Task.FromResult(new StoredValue<T>(default, "\"0\""));

            return Task.FromResult(new StoredValue<T>(JsonSerializer.Deserialize<T>(row.Json), $"\"{row.Version}\""));
        }
    }
    public Task WriteAsync<T>(string path, T value, string? etag = null, CancellationToken ct = default)
    {
        lock (gate)
        {
            var version = records.TryGetValue(path, out var row) ? row.Version : 0;
            if (etag != null && etag != $"\"{version}\"")
                throw new AccessProblem(409, "Concurrent update.");
            records[path] = (JsonSerializer.Serialize(value), version + 1);
        }
        return Task.CompletedTask;
    }
    public Task<AccessPage<T>> ListAsync<T>(string path, string? after = null, int limit = 50, CancellationToken ct = default)
    {
        lock (gate)
        {
            var rows = records.Where(p => p.Key.StartsWith(path + "/", StringComparison.Ordinal))
                .Where(p => after == null || string.CompareOrdinal(p.Key[(path.Length + 1)..], after) > 0)
                .OrderBy(p => p.Key, StringComparer.Ordinal).ToList();

            return Task.FromResult(new AccessPage<T>(rows.Take(limit).Select(p => JsonSerializer.Deserialize<T>(p.Value.Json)!).ToList(),
                rows.Count > limit ? rows[limit - 1].Key[(path.Length + 1)..] : null));
        }
    }
}

public sealed class MemoryBlobStorage : IBlobStorageService
{
    private readonly ConcurrentDictionary<string, byte[]> blobs = new(StringComparer.Ordinal);
    public bool FailUploads { get; set; }
    public bool FailDownloads { get; set; }

    public Task<string> UploadReportBlobAsync(string fileName, byte[] pdfBytes, string contentType = "application/pdf")
    {
        BlobStorageService.ValidateReportFileName(fileName);
        if (FailUploads)
            throw new AccessProblem(503, "Private report storage is unavailable; the report could not be saved.");
        blobs[fileName] = pdfBytes.ToArray();
        return Task.FromResult($"private://pulselink-reports/{fileName}");
    }

    public Task<byte[]?> DownloadReportBlobAsync(string fileName)
    {
        BlobStorageService.ValidateReportFileName(fileName);
        if (FailDownloads)
            return Task.FromResult<byte[]?>(null);
        return Task.FromResult(blobs.TryGetValue(fileName, out var bytes) ? bytes.ToArray() : null);
    }

    public Task<bool> ReportBlobExistsAsync(string fileName)
    {
        BlobStorageService.ValidateReportFileName(fileName);
        if (FailDownloads)
            return Task.FromResult(false);
        return Task.FromResult(blobs.TryGetValue(fileName, out var bytes) && bytes.Length > 0);
    }

    public void Remove(string fileName) => blobs.TryRemove(fileName, out _);
}

public sealed class AccessTestApplication : WebApplicationFactory<Program>
{
    public MemoryAccessStore Store { get; } = new();
    public MemoryBlobStorage Blobs { get; } = new();
    public AccessTestApplication()
    {
        Seed("sites/S1", new ClinicSite { Id = "S1", Name = "Remote clinic" });

        Seed("sites/S2", new ClinicSite { Id = "S2", Name = "Other clinic" });

        foreach (var (uid, role, sites) in new[] {
            ("doc1", "doctor", new[]{"S1"}), ("doc2", "doctor", new[]{"S1"}),
            ("docOther", "doctor", new[]{"S2"}), ("nurse1", "nurse", new[]{"S1"}),
            ("nurseOther", "nurse", new[]{"S2"}), ("patient", "patient", Array.Empty<string>()) })

            Seed($"accounts/{uid}", new AccessAccount { Uid = uid, Role = role, Status = "approved", SiteIds = sites.ToList(), Version = 1 });
        Seed("patients/P1", new ClinicalPatient { Id = "P1", FullName = "Test patient", SiteId = "S1", PrimaryDoctorUid = "doc1", Version = 1 });
        Seed("devices/D1", new SiteDevice { DeviceId = "D1", SiteId = "S1" });
    }
    private void Seed<T>(string path, T value) => Store.WriteAsync($"{AccessDirectory.Root}/{path}", value).GetAwaiter().GetResult();
    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.ConfigureAppConfiguration((_, config) => config.AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["AccessControl:BootstrapAdministratorEmail"] = "administrator@example.test",
            ["Notifications:WorkerEnabled"] = "false"
        }));
        builder.ConfigureServices(services =>
        {
            services.RemoveAll<IAccessStore>(); services.AddSingleton<IAccessStore>(Store);
            services.RemoveAll<IFirebaseTokenVerifier>(); services.AddSingleton<IFirebaseTokenVerifier, FakeVerifier>();
            services.RemoveAll<IBlobStorageService>(); services.AddSingleton<IBlobStorageService>(Blobs);
            services.RemoveAll<IPdfReportGenerator>(); services.AddSingleton<IPdfReportGenerator>(new PdfReportGenerator(NullLogger<PdfReportGenerator>.Instance));
        });
    }
    public HttpClient As(string? token = null)
    {
        var client = CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost"), AllowAutoRedirect = false });
        if (token != null) client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);

        return client;
    }
    private sealed class FakeVerifier : IFirebaseTokenVerifier
    {
        public Task<VerifiedUser> VerifyAsync(string token, CancellationToken ct) => token == "invalid"
            ? throw new AuthenticationException()
            : Task.FromResult(new VerifiedUser(token == "admin-unverified" ? "admin" : token,
                token is "admin" or "unverified" or "admin-unverified" ? "administrator@example.test" : $"{token}@example.test",
                token is not ("unverified" or "admin-unverified"), token));
    }
}

public sealed class AccessTests
{
    [Fact]
    public async Task Scalar_schema_remains_public_and_documents_bearer_authentication()
    {
        using var app = new AccessTestApplication(); using var client = app.As();

        using var document = JsonDocument.Parse(await client.GetStringAsync("/openapi/v1.json"));

        var scheme = document.RootElement.GetProperty("components").GetProperty("securitySchemes").GetProperty("FirebaseBearer");

        Assert.Equal("bearer", scheme.GetProperty("scheme").GetString());
        Assert.True(document.RootElement.GetProperty("paths").GetProperty("/api/access/me").GetProperty("get").GetProperty("security").GetArrayLength() > 0);
    }
    [Theory]
    [InlineData("/api/ecg/sessions/patient/P1")]
    [InlineData("/api/alerts")]
    [InlineData("/api/admin/accounts")]
    [InlineData("/api/reports/session/unknown/pdf")]
    public async Task Anonymous_requests_are_rejected(string route)
    {
        using var app = new AccessTestApplication();
        using var client = app.As();
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync(route)).StatusCode);
    }
    [Fact]
    public async Task Invalid_token_is_rejected()
    {
        using var app = new AccessTestApplication();
        using var client = app.As("invalid");
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/access/me")).StatusCode);
    }
    [Fact]
    public async Task Only_verified_configured_identity_bootstraps_administrator()
    {
        using var app = new AccessTestApplication();
        using var admin = app.As("admin"); using var other = app.As("unverified");
        Assert.Equal(HttpStatusCode.OK, (await admin.GetAsync("/api/admin/accounts")).StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await other.GetAsync("/api/admin/accounts")).StatusCode);
    }
    [Fact]
    public async Task Role_request_does_not_grant_staff_access()
    {
        using var app = new AccessTestApplication();
        using var client = app.As("applicant");

        var response = await client.PostAsJsonAsync("/api/access/role-request",
        new { requestedRole = "doctor" });

        var account = await response.Content.ReadFromJsonAsync<AccessAccount>();
        Assert.Equal("pending", account!.Status); Assert.Equal("patient", account.Role);
        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
    }
    [Fact]
    public async Task An_existing_administrator_cannot_use_an_unverified_email_identity()
    {
        using var app = new AccessTestApplication();
        using var admin = app.As("admin");
        Assert.Equal(HttpStatusCode.OK, (await admin.GetAsync("/api/admin/accounts")).StatusCode);
        using var unverified = app.As("admin-unverified");
        Assert.Equal(HttpStatusCode.Forbidden, (await unverified.GetAsync("/api/admin/accounts")).StatusCode);
    }
    [Fact]
    public async Task Staff_cannot_request_administrator_privileges()
    {
        using var app = new AccessTestApplication();
        using var client = app.As("nurse1");
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync("/api/access/role-request", new { requestedRole = "administrator" })).StatusCode);
    }
    [Fact]
    public async Task Suspension_takes_effect_on_the_next_request()
    {
        using var app = new AccessTestApplication();
        using var admin = app.As("admin"); using var doctor = app.As("doc1");

        Assert.Equal(HttpStatusCode.OK, (await doctor.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
        var current = await doctor.GetFromJsonAsync<AccessAccount>("/api/access/me");
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsJsonAsync("/api/admin/accounts/doc1/approval", new
        {
            role = "doctor",
            status = "suspended",
            siteIds = new[] { "S1" },
            expectedVersion = current!.Version
        })).StatusCode);

        Assert.Equal(HttpStatusCode.Forbidden, (await doctor.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
    }
    [Fact]
    public async Task Care_plan_authorship_is_server_controlled_and_other_doctors_are_rejected()
    {
        using var app = new AccessTestApplication();
        using var doctor = app.As("doc1");
        using var other = app.As("doc2");

        var plan = new { clinicalInstructions = "Test instructions", updatedByDoctor = "forged", updatedAtUtc = "forged" };
        Assert.Equal(HttpStatusCode.Forbidden, (await other.PutAsJsonAsync("/api/access/patients/P1/care-plan", plan)).StatusCode);

        var response = await doctor.PutAsJsonAsync("/api/access/patients/P1/care-plan", plan);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var saved = await response.Content.ReadFromJsonAsync<CarePlan>();
        Assert.Equal("doc1", saved!.UpdatedByDoctor); Assert.NotEqual("forged", saved.UpdatedAtUtc);
    }
    [Theory]
    [InlineData("patient")]
    [InlineData("admin")]
    [InlineData("doc2")]
    [InlineData("docOther")]
    [InlineData("nurseOther")]
    public async Task Clinical_access_requires_the_correct_role_site_and_assignment(string token)
    {
        using var app = new AccessTestApplication();
        using var client = app.As(token);
        Assert.Equal(HttpStatusCode.Forbidden, (await client.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
    }
    [Theory]
    [InlineData("doc1")]
    [InlineData("nurse1")]
    public async Task Assigned_clinicians_can_access_the_patient(string token)
    {
        using var app = new AccessTestApplication();
        using var client = app.As(token);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
    }
    [Fact]
    public async Task Administrator_can_explicitly_add_and_remove_a_doctor()
    {
        using var app = new AccessTestApplication();
        using var admin = app.As("admin"); using var doctor = app.As("doc2");

        var first = await admin.PutAsJsonAsync("/api/admin/patients/P1/assignment",
        new { primaryDoctorUid = "doc1", additionalDoctorUids = new[] { "doc2" }, expectedVersion = 1 });
        Assert.Equal(HttpStatusCode.OK, first.StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await doctor.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);

        var removed = await admin.PutAsJsonAsync("/api/admin/patients/P1/assignment",
        new { primaryDoctorUid = "doc1", additionalDoctorUids = Array.Empty<string>(), expectedVersion = 2 });
        Assert.Equal(HttpStatusCode.OK, removed.StatusCode);
        Assert.Equal(HttpStatusCode.Forbidden, (await doctor.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
    }
    [Fact]
    public async Task Nurse_cannot_be_approved_for_two_sites()
    {
        using var app = new AccessTestApplication();
        using var admin = app.As("admin");
        var response = await admin.PutAsJsonAsync("/api/admin/accounts/nurse1/approval",
        new { role = "nurse", status = "approved", siteIds = new[] { "S1", "S2" }, expectedVersion = 1 });
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
    [Fact]
    public async Task Cross_site_doctor_assignment_is_rejected()
    {
        using var app = new AccessTestApplication();
        using var admin = app.As("admin");
        var response = await admin.PutAsJsonAsync("/api/admin/patients/P1/assignment", new { primaryDoctorUid = "docOther", additionalDoctorUids = Array.Empty<string>(), expectedVersion = 1 });
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }
    [Fact]
    public async Task Stale_approval_version_is_rejected_without_changing_access()
    {
        using var app = new AccessTestApplication();
        using var admin = app.As("admin");

        var response = await admin.PutAsJsonAsync("/api/admin/accounts/doc1/approval", new { role = "doctor", status = "suspended", siteIds = new[] { "S1" }, expectedVersion = 0 });
        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);

        using var doctor = app.As("doc1");
        Assert.Equal(HttpStatusCode.OK, (await doctor.GetAsync("/api/ecg/sessions/patient/P1")).StatusCode);
    }
    [Fact]
    public async Task Patient_record_can_be_created_without_a_login()
    {
        using var app = new AccessTestApplication();
        using var nurse = app.As("nurse1");

        var response = await nurse.PostAsJsonAsync("/api/access/patients", new { fullName = "New patient", siteId = "S1", primaryDoctorUid = "doc1" });
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);

        var patient = await response.Content.ReadFromJsonAsync<ClinicalPatient>();

        Assert.Equal("nurse1", patient!.RegisteredByUid); Assert.StartsWith("PAT-", patient.Id);
    }
    [Fact]
    public async Task Session_start_uses_registered_identity_and_requires_assigned_device()
    {
        using var app = new AccessTestApplication();
        using var nurse = app.As("nurse1");

        var denied = await nurse.PostAsJsonAsync("/api/ecg/sessions/start", new { patientId = "P1", deviceId = "unassigned" });

        Assert.Equal(HttpStatusCode.Forbidden, denied.StatusCode);

        var response = await nurse.PostAsJsonAsync("/api/ecg/sessions/start", new { patientId = "P1", deviceId = "D1", patientName = "Forged" });
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);

        var sessions = await nurse.GetFromJsonAsync<List<EcgSessionSummaryDto>>("/api/ecg/sessions/patient/P1");

        Assert.Equal("Test patient", Assert.Single(sessions!).PatientName);
    }
    [Fact]
    public async Task Dismissing_all_alerts_preserves_other_sites_alerts()
    {
        using var app = new AccessTestApplication();
        using var doctor = app.As("doc1");
        await app.Store.WriteAsync($"{AccessDirectory.Root}/patients/P2", new ClinicalPatient
        {
            Id = "P2",
            FullName = "Other patient",
            SiteId = "S2",
            PrimaryDoctorUid = "docOther",
            Version = 1
        });

        using var scope = app.Services.CreateScope();
        var service = scope.ServiceProvider.GetRequiredService<IEcgDataService>();

        foreach (var id in new[] { "P1", "P2" })
        {
            var session = await service.StartSessionAsync(new StartSessionRequestDto { PatientId = id, DeviceId = $"device-{id}" });
            await service.IngestBatchAsync(new IngestEcgPayloadDto
            {
                DeviceId = $"device-{id}",
                SessionId = session.SessionId,
                PatientId = id,
                LeadConnected = true,
                BootId = "boot1",
                Sequence = 0,
                FirstSampleIndex = 0,
                EstimatedBpm = 150,
                Samples = [2048]
            });
        }

        var visible = await doctor.GetFromJsonAsync<List<AlertDto>>("/api/alerts");

        Assert.Equal("P1", Assert.Single(visible!).PatientId);

        Assert.Equal(HttpStatusCode.OK, (await doctor.PostAsync("/api/alerts/dismiss-all", null)).StatusCode);

        Assert.Equal("P2", Assert.Single(await service.GetActiveAlertsAsync()).PatientId);
    }
    [Fact]
    public async Task Doctor_note_identity_cannot_be_spoofed()
    {
        using var app = new AccessTestApplication();
        using var nurse = app.As("nurse1");
        using var doctor = app.As("doc1");

        var started = await nurse.PostAsJsonAsync("/api/ecg/sessions/start", new { patientId = "P1", deviceId = "D1" });

        var session = await started.Content.ReadFromJsonAsync<StartSessionResponseDto>();

        var response = await doctor.PostAsJsonAsync("/api/doctor/notes", new
        {

            sessionId = session!.SessionId,
            patientId = "P1",
            doctorId = "doc2",
            doctorName = "Forged name",
            clinicalImpression = "Test review"
        });
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);

        var note = await response.Content.ReadFromJsonAsync<DoctorNoteDto>();

        Assert.Equal("doc1", note!.DoctorId); Assert.Equal("doc1", note.DoctorName);
    }
    [Fact]
    public async Task Knowing_a_report_file_name_does_not_grant_access()
    {
        using var app = new AccessTestApplication();
        using var nurse = app.As("nurse1");
        using var other = app.As("doc2");

        var started = await nurse.PostAsJsonAsync("/api/ecg/sessions/start", new { patientId = "P1", deviceId = "D1" });

        var session = await started.Content.ReadFromJsonAsync<StartSessionResponseDto>();

        Assert.Equal(HttpStatusCode.Forbidden, (await other.GetAsync($"/api/reports/PulseLink_Report_{session!.SessionId}.pdf")).StatusCode);
    }
    [Theory]
    [InlineData("users/../roles")]
    [InlineData("users/name.json")]
    [InlineData("users/x?auth=anything")]
    public void Firebase_path_injection_is_rejected(string path) => Assert.Throws<AccessProblem>(() => FirebaseAccessStore.ValidatePath(path));
}
