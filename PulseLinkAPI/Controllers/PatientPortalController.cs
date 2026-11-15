using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;

namespace PulseLinkAPI.Controllers;

[ApiController, Authorize(Roles = AccessRoles.Patient), Route("api/patient")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class PatientPortalController(PatientPortal portal) : ControllerBase
{
    private CurrentAccess Caller => HttpContext.Features.Get<CurrentAccess>()!;

    [HttpGet("record")]
    public async Task<ActionResult<PatientProfile>> Record(CancellationToken ct)
    {
        var patient = await portal.OwnPatientAsync(Caller, ct);
        return patient == null ? NoContent() : Ok(new PatientProfile(patient.Id, patient.FullName));
    }

    [HttpGet("sessions")]
    public Task<AccessPage<PatientSessionStatus>> Sessions([FromQuery] string? after,
        [FromQuery, Range(1, 100)] int limit = 20, CancellationToken ct = default) => portal.SessionsAsync(Caller, after, limit, ct);

    [HttpGet("sessions/{id}/results")]
    public Task<PatientRelease> Results(string id, CancellationToken ct) => portal.ReleasedAsync(Caller, id, ct);

    [HttpGet("sessions/{id}/report")]
    public Task<ReportStatusDto> ReportStatus(string id, CancellationToken ct) => portal.ReleasedReportStatusAsync(Caller, id, ct);

    [HttpGet("sessions/{id}/report/pdf")]
    [ProducesResponseType(typeof(FileContentResult), StatusCodes.Status200OK)]
    public async Task<IActionResult> DownloadReleasedReportPdf(string id, CancellationToken ct)
    {
        var (pdfBytes, fileName) = await portal.DownloadReleasedReportPdfAsync(Caller, id, ct);
        Response.Headers.CacheControl = "no-store";
        return File(pdfBytes, "application/pdf", fileName);
    }
}
