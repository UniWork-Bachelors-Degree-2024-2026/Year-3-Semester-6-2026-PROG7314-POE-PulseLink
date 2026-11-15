using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Transport;

namespace PulseLinkAPI.Controllers;

[ApiController, Route("api/device-workflow"), Authorize(AuthenticationSchemes = DeviceAuthenticationHandler.SchemeName)]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DeviceWorkflowController(NurseWorkflow workflow) : ControllerBase
{
    private DeviceIdentity Device => HttpContext.Features.Get<DeviceIdentity>()!;
    [HttpPost("authorisation")]
    public Task<ScreenCode> Begin(CancellationToken ct) => workflow.BeginAsync(Device, ct);
    [HttpGet("authorisation")]
    public async Task<object> Current(CancellationToken ct) => new { grant = await workflow.CurrentAsync(Device, ct), serverTimeUtc = DateTimeOffset.UtcNow };
    [HttpDelete("authorisation")]
    public async Task<IActionResult> End(CancellationToken ct) { await workflow.EndAsync(Device, ct); return NoContent(); }
    [HttpGet("clinic")]
    public Task<ClinicPage> Clinic(string grantId, string? afterPatient, string? afterDoctor, CancellationToken ct) => workflow.ClinicAsync(Device, grantId, afterPatient, afterDoctor, ct);
    [HttpPost("patients"), RequestSizeLimit(4096)]
    public Task<PulseLinkAPI.Access.ClinicalPatient> Register(ScreenPatientRequest request, CancellationToken ct) => workflow.RegisterAsync(Device, request, ct);
    [HttpPost("patients/{patientId}/doctor"), RequestSizeLimit(4096)]
    public Task<ScreenPatient> AssignDoctor(string patientId, ScreenDoctorAssignmentRequest request, CancellationToken ct) =>
        workflow.AssignDoctorAsync(Device, patientId, request, ct);
    [HttpPost("recordings"), RequestSizeLimit(4096)]
    public Task<PulseLinkAPI.Models.DTOs.StartSessionResponseDto> Recording(ScreenRecordingRequest request, CancellationToken ct) => workflow.RecordingAsync(Device, request, ct);
    [HttpPost("recordings/check"), RequestSizeLimit(4096)]
    public async Task<IActionResult> CheckRecording(ScreenRecordingRequest request, CancellationToken ct)
    {
        await workflow.CheckRecordingAsync(Device, request, ct);
        return NoContent();
    }
}
