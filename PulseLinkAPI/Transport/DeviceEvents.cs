using PulseLinkAPI.Access;

namespace PulseLinkAPI.Transport;

public sealed record DeviceEvent(string Id, string DeviceId, string SiteId, string SessionId, string Kind,
    DateTimeOffset ExpiresAtUtc, DateTimeOffset? ReceivedAtUtc = null, string? Message = null, string? Author = null);

public sealed class DeviceEvents(IAccessStore store, DeviceRecordingAccess access)
{
    private static string Root(string id) => $"deviceEvents/{id}";
    public async Task<AccessPage<DeviceEvent>> ReadAsync(DeviceIdentity device, string? after, CancellationToken ct)
    {
        if (after != null && !DeviceCredentials.ValidId(after)) throw new AccessProblem(400, "Invalid event cursor.");
        var page = await store.ListAsync<DeviceEvent>(Root(device.DeviceId), after, 8, ct);
        var visible = new List<DeviceEvent>();
        foreach (var item in page.Items)
        {
            if (item.DeviceId != device.DeviceId || item.SiteId != device.SiteId || item.ExpiresAtUtc <= DateTimeOffset.UtcNow || item.ReceivedAtUtc != null) continue;
            if (item.Kind != "doctor-coordination")
            {
                try { await access.RequireAsync(device, item.SessionId, ct); }
                catch (AccessProblem problem) when (problem.Status is 403 or 404) { continue; }
            }
            visible.Add(item);
        }
        return new(visible, page.NextCursor);
    }
    public async Task ReceiveAsync(DeviceIdentity device, string id, CancellationToken ct)
    {
        if (!DeviceCredentials.ValidId(id)) throw new AccessProblem(400, "Invalid event identifier.");
        var path = $"{Root(device.DeviceId)}/{id}";
        var row = await store.ReadAsync<DeviceEvent>(path, ct);
        var item = row.Value;
        if (item == null || item.DeviceId != device.DeviceId || item.SiteId != device.SiteId || item.ExpiresAtUtc <= DateTimeOffset.UtcNow)
            throw new AccessProblem(404, "Current event not found.");
        if (item.Kind != "doctor-coordination")
        {
            await access.RequireAsync(device, item.SessionId, ct);
        }
        if (item.ReceivedAtUtc == null) await store.WriteAsync(path, item with { ReceivedAtUtc = DateTimeOffset.UtcNow }, row.ETag, ct);
    }
}
