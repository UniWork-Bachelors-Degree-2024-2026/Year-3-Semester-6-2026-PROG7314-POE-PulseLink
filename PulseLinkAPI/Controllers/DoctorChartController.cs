using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;

namespace PulseLinkAPI.Controllers;

[ApiController]
[Authorize]
[Route("api/doctor")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DoctorChartController(DoctorChartService chart, PatientPortal portal) : ControllerBase
{
    private CurrentAccess Caller => HttpContext.Features.Get<CurrentAccess>()!;

    [HttpGet("dashboard"), Authorize(Roles = AccessRoles.Doctor)]
    public Task<DoctorDashboardDto> GetDashboard(CancellationToken ct) =>
        chart.GetDashboardAsync(Caller, ct);

    [HttpGet("patients"), Authorize(Roles = "doctor,nurse")]
    public Task<AccessPage<DoctorPatientSummaryDto>> GetAssignedPatients(
        [FromQuery] string? after = null,
        [FromQuery] int limit = 50,
        CancellationToken ct = default) =>
        chart.ListAssignedPatientsAsync(Caller, after, limit, ct);

    [HttpGet("patients/{patientId}/chart"), Authorize(Roles = "doctor,nurse")]
    public Task<PatientChartDto> GetPatientChart(string patientId, CancellationToken ct) =>
        chart.GetPatientChartAsync(Caller, patientId, ct);

    [HttpGet("patients/{patientId}/visits"), Authorize(Roles = "doctor,nurse")]
    public Task<List<ClinicalVisitDto>> GetPatientVisits(string patientId, CancellationToken ct) =>
        chart.GetPatientVisitsAsync(Caller, patientId, ct);

    [HttpPost("patients/{patientId}/visits"), Authorize(Roles = AccessRoles.Doctor)]
    public async Task<ActionResult<ClinicalVisitDto>> CreatePatientVisit(
        string patientId,
        [FromBody] CreateClinicalVisitDto request,
        CancellationToken ct)
    {
        var visit = await chart.CreatePatientVisitAsync(Caller, patientId, request, ct);
        return Created($"/api/doctor/patients/{patientId}/visits", visit);
    }

    [HttpGet("sessions/{sessionId}/review"), Authorize(Roles = "doctor,nurse")]
    public Task<DoctorSessionReviewDto> GetSessionReview(string sessionId, CancellationToken ct) =>
        chart.GetSessionReviewAsync(Caller, sessionId, ct);

    [HttpPost("sessions/{sessionId}/release"), Authorize(Roles = AccessRoles.Doctor)]
    public Task<PatientRelease> ReleaseSessionResults(string sessionId, CancellationToken ct) =>
        portal.ReleaseAsync(Caller, sessionId, ct);

    [HttpPost("sessions/{sessionId}/messages"), Authorize(Roles = AccessRoles.Doctor)]
    public async Task<ActionResult<PulseLinkAPI.Transport.DeviceEvent>> SendDoctorMessage(
        string sessionId,
        [FromBody] SendDoctorMessageDto request,
        CancellationToken ct)
    {
        var result = await chart.SendDoctorMessageAsync(Caller, sessionId, request.Message, ct);
        return Created($"/api/doctor/sessions/{sessionId}/messages/{result.Id}", result);
    }

    [HttpGet("sessions/{sessionId}/messages"), Authorize(Roles = "doctor,nurse")]
    public Task<List<PulseLinkAPI.Transport.DeviceEvent>> GetSessionMessages(string sessionId, CancellationToken ct) =>
        chart.GetSessionMessagesAsync(Caller, sessionId, ct);
}
