using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;
using PulseLinkAPI.Transport;

namespace PulseLinkAPI.Controllers;

[ApiController, Route("api/device-transport"), Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
public sealed class DeviceTransportController(DeviceRecordingAccess access, IEcgDataService data, DeviceEvents events) : ControllerBase
{
    private DeviceIdentity Device => HttpContext.Features.Get<DeviceIdentity>()!;
    private static object Compact(RecordingProgress p) => new
    {
        p.SessionId,
        p.Status,
        p.Revision,
        p.ReceivedBatchCount,
        p.ReceivedSampleCount,
        p.ExpectedBatchCount,
        p.ExpectedSampleCount,
        p.MissingSequences,
        p.HasSampleGaps
    };

    [HttpGet("health")]
    public object Health() => new { status = "ready", Device.DeviceId, Device.SiteId, serverTimeUtc = DateTimeOffset.UtcNow };

    [HttpGet("events")]
    public Task<PulseLinkAPI.Access.AccessPage<DeviceEvent>> Events([FromQuery] string? after, CancellationToken ct) =>
        events.ReadAsync(Device, after, ct);

    [HttpPost("events/{id}/received")]
    public async Task<IActionResult> ReceiveEvent(string id, CancellationToken ct) {
        await events.ReceiveAsync(Device, id, ct); return NoContent();
    }

    [HttpGet("recordings/{id}/progress")]
    public async Task<IActionResult> Progress(string id, CancellationToken ct)
    {
        await access.RequireAsync(Device, id, ct);
        var result = await data.GetRecordingProgressAsync(id);
        return result == null ? NotFound() : Ok(Compact(result));
    }

    [HttpPost("recordings/{id}/batches"), RequestSizeLimit(32768)]
    public async Task<IActionResult> Batch(string id, IngestEcgPayloadDto payload, CancellationToken ct)
    {
        await access.RequireAsync(Device, id, ct);
        if (payload.DeviceId != Device.DeviceId || payload.SessionId != id)
            return Conflict(new { title = "Recording and device identities do not match." });
        if (payload.SampleFlags is { } flags) {
            payload.LeadConnected = flags.All(f => (f & 3) == 0);
            payload.SignalQuality = !payload.LeadConnected ? "LeadsDisconnected" : flags.Any(f => f != 0) ? "Degraded" :
                payload.EstimatedBpm > 0 ? "Good" : "Unknown";
            if (flags.Any(f => f != 0)) payload.EstimatedBpm = 0;
            payload.AdaptiveThreshold = 0;
        }
        return Ok(await data.IngestRecordingBatchAsync(payload));
    }

    [HttpPost("recordings/{id}/complete")]
    public async Task<IActionResult> Complete(string id, CompleteRecordingRequest request, CancellationToken ct)
    {
        await access.RequireAsync(Device, id, ct);
        var result = await data.CompleteRecordingAsync(id, request);
        if (result == null) return NotFound();
        return result.Status == "Completed" ? Ok(Compact(result)) : StatusCode(202, Compact(result));
    }
}
