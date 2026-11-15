using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Transport;
using Xunit;

namespace PulseLinkAPI.Tests;

public sealed class DeviceTransportTests
{
    private static HttpClient Device(AccessTestApplication app, IssuedDeviceCredential credential)
    {
        var client = app.As();
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Device", $"{credential.DeviceId}:{credential.Token}");
        return client;
    }
    private static async Task<IssuedDeviceCredential> Enrol(AccessTestApplication app)
    {
        using var admin = app.As("admin");
        var response = await admin.PostAsync("/api/admin/devices/D1/credential", null);

        response.EnsureSuccessStatusCode();
        Assert.Equal("no-store", response.Headers.CacheControl!.ToString());
        return (await response.Content.ReadFromJsonAsync<IssuedDeviceCredential>())!;
    }
    private static async Task<string> Start(AccessTestApplication app, bool permit = true)
    {
        using var nurse = app.As("nurse1");

        var response = await nurse.PostAsJsonAsync("/api/ecg/sessions/start", new StartSessionRequestDto
        {
            PatientId = "P1",
            DeviceId = "D1",
            RecordingId = Guid.NewGuid()
        });
        response.EnsureSuccessStatusCode();

        var id = (await response.Content.ReadFromJsonAsync<StartSessionResponseDto>())!.SessionId;
        if (permit)
            (await nurse.PostAsJsonAsync($"/api/access/recordings/{id}/upload-permit", new UploadPermitRequest())).EnsureSuccessStatusCode();
        return id;
    }
    [Fact]
    public async Task Credential_is_admin_only_not_stored_in_plaintext_and_cannot_access_user_routes()
    {
        using var app = new AccessTestApplication();
        using var nurse = app.As("nurse1");
        Assert.Equal(HttpStatusCode.Forbidden, (await nurse.PostAsync("/api/admin/devices/D1/credential", null)).StatusCode);

        var credential = await Enrol(app);
        var stored = (await app.Store.ReadAsync<DeviceCredential>($"{AccessDirectory.Root}/deviceCredentials/D1")).Value!;
        Assert.NotEqual(credential.Token, stored.Sha256); Assert.Equal(64, stored.Sha256.Length);

        using var device = Device(app, credential);
        Assert.Equal(HttpStatusCode.OK, (await device.GetAsync("/api/device-transport/health")).StatusCode);
        Assert.NotEqual(HttpStatusCode.OK, (await device.GetAsync("/api/admin/accounts")).StatusCode);
        Assert.NotEqual(HttpStatusCode.OK, (await device.GetAsync("/api/access/me")).StatusCode);

        using var staff = app.As("nurse1");
        Assert.Equal(HttpStatusCode.Unauthorized, (await staff.GetAsync("/api/device-transport/health")).StatusCode);
    }
    [Fact]
    public async Task Rotation_revocation_and_site_changes_invalidate_credentials()
    {
        using var app = new AccessTestApplication();
        var old = await Enrol(app);

        using var previous = Device(app, old);


        var current = await Enrol(app);
        using var device = Device(app, current);
        Assert.Equal(HttpStatusCode.Unauthorized, (await previous.GetAsync("/api/device-transport/health")).StatusCode);

        await app.Store.WriteAsync($"{AccessDirectory.Root}/devices/D1", new SiteDevice { DeviceId = "D1", SiteId = "S2" });
        Assert.Equal(HttpStatusCode.Unauthorized, (await device.GetAsync("/api/device-transport/health")).StatusCode);

        await app.Store.WriteAsync($"{AccessDirectory.Root}/devices/D1", new SiteDevice { DeviceId = "D1", SiteId = "S1" });
        using var admin = app.As("admin");
        (await admin.DeleteAsync("/api/admin/devices/D1/credential")).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.Unauthorized, (await device.GetAsync("/api/device-transport/health")).StatusCode);
    }
    [Fact]
    public async Task Device_upload_requires_nurse_permission_and_retries_are_immutable()
    {
        using var app = new AccessTestApplication();
        using var device = Device(app, await Enrol(app));
        var id = await Start(app, false);
        Assert.Equal(HttpStatusCode.Forbidden, (await device.GetAsync($"/api/device-transport/recordings/{id}/progress")).StatusCode);

        using var other = app.As("nurseOther");
        Assert.Equal(HttpStatusCode.Forbidden, (await other.PostAsJsonAsync($"/api/access/recordings/{id}/upload-permit", new UploadPermitRequest())).StatusCode);

        using var nurse = app.As("nurse1");
        (await nurse.PostAsJsonAsync($"/api/access/recordings/{id}/upload-permit", new UploadPermitRequest())).EnsureSuccessStatusCode();

        var batch = new IngestEcgPayloadDto
        {
            SessionId = id,
            DeviceId = "D1",
            BootId = "boot1",
            Sequence = 0,
            FirstSampleIndex = 0,
            Timestamp = 1000,
            Samples = [1000, 1200, 1400],
            SignalQuality = "Unknown"
        };

        var url = $"/api/device-transport/recordings/{id}/batches";
        (await device.PostAsJsonAsync(url, batch)).EnsureSuccessStatusCode();

        var retry = await device.PostAsJsonAsync(url, batch); retry.EnsureSuccessStatusCode();
        Assert.True((await retry.Content.ReadFromJsonAsync<RecordingReceipt>())!.Duplicate);
        batch.Samples[0] = 1001;

        Assert.Equal(HttpStatusCode.Conflict, (await device.PostAsJsonAsync(url, batch)).StatusCode);
        (await device.PostAsJsonAsync($"/api/device-transport/recordings/{id}/complete", new CompleteRecordingRequest
        {
            ExpectedBatchCount = 1,
            ExpectedSampleCount = 3
        })).EnsureSuccessStatusCode();
        var suspended = (await app.Store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/nurse1")).Value!;
        suspended.Status = "suspended";
        await app.Store.WriteAsync($"{AccessDirectory.Root}/accounts/nurse1", suspended);
        Assert.Equal(HttpStatusCode.Forbidden, (await device.GetAsync($"/api/device-transport/recordings/{id}/progress")).StatusCode);
    }
    [Fact]
    public async Task Expired_permissions_and_inactive_sites_are_rejected()
    {
        using var app = new AccessTestApplication();
        using var device = Device(app, await Enrol(app));

        var id = await Start(app);
        await app.Store.WriteAsync($"deviceUploadPermits/{id}", new RecordingUploadPermit(id, "D1", "S1", "nurse1", DateTimeOffset.UtcNow.AddMinutes(-1)));

        Assert.Equal(HttpStatusCode.Forbidden, (await device.GetAsync($"/api/device-transport/recordings/{id}/progress")).StatusCode);

        await app.Store.WriteAsync($"{AccessDirectory.Root}/sites/S1", new ClinicSite { Id = "S1", Name = "Closed", Active = false });
        Assert.Equal(HttpStatusCode.Unauthorized, (await device.GetAsync("/api/device-transport/health")).StatusCode);
    }

    [Fact]
    public async Task Exact_capture_times_and_quality_flags_survive_upload()
    {
        using var app = new AccessTestApplication(); using var device = Device(app, await Enrol(app));
        var id = await Start(app);
        var batch = new IngestEcgPayloadDto
        {
            SessionId = id,
            DeviceId = "D1",
            BootId = "boot1",
            Sequence = 0,
            FirstSampleIndex = 0,
            Timestamp = 1000,
            FirstSampleTimestampUs = 1000100,
            SampleOffsetsUs = [0, 3990, 8010],
            Samples = [1000, 2000, 3000],
            SampleFlags = [0, 1, 0],
            EstimatedBpm = 70
        };
        var route = $"/api/device-transport/recordings/{id}/batches";
        (await device.PostAsJsonAsync(route, batch)).EnsureSuccessStatusCode();
        using var nurse = app.As("nurse1");
        var page = await nurse.GetFromJsonAsync<RecordingBatchPage>($"/api/ecg/sessions/{id}/batches");
        var saved = Assert.Single(page!.Batches).Payload;
        Assert.Equal(batch.SampleOffsetsUs, saved.SampleOffsetsUs); Assert.Equal(batch.SampleFlags, saved.SampleFlags);
        Assert.Equal(1000100, saved.FirstSampleTimestampUs); Assert.False(saved.LeadConnected); Assert.Equal(0, saved.EstimatedBpm);
        batch.Sequence = 1; batch.FirstSampleIndex = 3; batch.Timestamp = 1012; batch.FirstSampleTimestampUs = 1012100;
        batch.SampleOffsetsUs = [0, 4000, 8000]; batch.SampleFlags = [0, 0, 0];
        (await device.PostAsJsonAsync(route, batch)).EnsureSuccessStatusCode();
        var progress = (await nurse.GetFromJsonAsync<RecordingProgress>($"/api/ecg/sessions/{id}/progress"))!;
        Assert.Empty(progress.TimeGaps);
        batch.Sequence = 2; batch.FirstSampleIndex = 6; batch.Timestamp = 1024; batch.FirstSampleTimestampUs = 1024100;
        batch.SampleFlags = [0];
        Assert.Equal(HttpStatusCode.BadRequest, (await device.PostAsJsonAsync(route, batch)).StatusCode);
        batch.Timestamp = 1029; batch.FirstSampleTimestampUs = 1029100; batch.SampleFlags = [0, 0, 0];
        (await device.PostAsJsonAsync(route, batch)).EnsureSuccessStatusCode();
        progress = (await nurse.GetFromJsonAsync<RecordingProgress>($"/api/ecg/sessions/{id}/progress"))!;
        Assert.Equal(5, Assert.Single(progress.TimeGaps).DurationMs);
    }

    [Fact]
    public async Task Events_are_scoped_and_receipt_does_not_imply_user_acknowledgement()
    {
        using var app = new AccessTestApplication(); using var device = Device(app, await Enrol(app));
        var id = await Start(app);
        using var nurse = app.As("nurse1");
        (await nurse.PostAsJsonAsync($"/api/access/recordings/{id}/upload-permit", new UploadPermitRequest())).EnsureSuccessStatusCode();
        var page = (await device.GetFromJsonAsync<AccessPage<DeviceEvent>>("/api/device-transport/events"))!;
        var item = Assert.Single(page.Items); Assert.Equal(id, item.SessionId); Assert.Null(item.ReceivedAtUtc);
        (await device.PostAsync($"/api/device-transport/events/{item.Id}/received", null)).EnsureSuccessStatusCode();
        var stored = (await app.Store.ReadAsync<DeviceEvent>($"deviceEvents/D1/{item.Id}")).Value!;
        Assert.NotNull(stored.ReceivedAtUtc);
        Assert.Empty((await device.GetFromJsonAsync<AccessPage<DeviceEvent>>("/api/device-transport/events"))!.Items);
        Assert.Equal(HttpStatusCode.NotFound, (await device.PostAsync("/api/device-transport/events/unknown/received", null)).StatusCode);
    }
}
