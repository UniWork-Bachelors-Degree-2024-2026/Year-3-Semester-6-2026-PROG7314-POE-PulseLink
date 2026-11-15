// Controller managing clinical PDF report generation and private Blob Storage download streams for authorised staff.

using Microsoft.AspNetCore.Mvc;    // (Microsoft, 2024)
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Controllers;

[ApiController]
[Route("api/reports")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public class ReportsController : ControllerBase
{
    private readonly IEcgDataService _ecgDataService;
    private readonly ILogger<ReportsController> _logger;

    public ReportsController(
        IEcgDataService ecgDataService,
        ILogger<ReportsController> logger)
    {
        _ecgDataService = ecgDataService;
        _logger = logger;
    }

    // Generates or downloads the authoritative single-lead ECG PDF report for a session.
    // Verifies note-count freshness so newly saved doctor notes never serve a stale cached PDF.
    [HttpGet("session/{sessionId}/pdf")]
    [ProducesResponseType(typeof(FileContentResult), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetSessionReportPdf(
        [FromRoute] string sessionId,
        [FromQuery] bool forceRegenerate = false)
    {
        if (string.IsNullOrWhiteSpace(sessionId))
        {
            return BadRequest(new { Message = "Session ID is required." });
        }

        var fileName = EcgDataService.StaffReportFileName(sessionId);
        var pdfBytes = await _ecgDataService.GenerateSessionReportPdfAsync(sessionId, forceRegenerate);

        if (pdfBytes == null || pdfBytes.Length == 0)
        {
            return NotFound(new { Message = $"ECG session with ID '{sessionId}' was not found." });
        }

        Response.Headers.CacheControl = "no-store";
        _logger.LogInformation("[REPORTS] Served authoritative staff report: {FileName} ({Bytes} bytes)", fileName, pdfBytes.Length);
        return File(pdfBytes, "application/pdf", fileName);
    }

    // Direct private Blob download endpoint serving binary staff reports by file name.
    [HttpGet("{fileName}")]
    [ProducesResponseType(typeof(FileContentResult), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetReportByFileName([FromRoute] string fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName))
        {
            return BadRequest(new { Message = "File name is required." });
        }

        const string prefix = "PulseLink_Report_", suffix = ".pdf";
        var safeFileName = Path.GetFileName(fileName);
        if (!safeFileName.StartsWith(prefix, StringComparison.Ordinal) ||
            !safeFileName.EndsWith(suffix, StringComparison.Ordinal))
        {
            return NotFound(new { Message = $"Report file '{safeFileName}' was not found." });
        }

        var sessionId = safeFileName[prefix.Length..^suffix.Length];
        var fileBytes = await _ecgDataService.GenerateSessionReportPdfAsync(sessionId, forceRegenerate: false);

        if (fileBytes == null || fileBytes.Length == 0)
        {
            return NotFound(new { Message = $"Report file '{safeFileName}' was not found in private storage." });
        }

        Response.Headers.CacheControl = "no-store";
        return File(fileBytes, "application/pdf", safeFileName);
    }

    // Retrieves authenticated report readiness and link metadata for a session report.
    [HttpGet("session/{sessionId}/status")]
    [ProducesResponseType(typeof(ReportStatusDto), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetSessionReportStatus(
        [FromRoute] string sessionId,
        [FromQuery] bool generateIfMissing = true)
    {
        if (string.IsNullOrWhiteSpace(sessionId))
        {
            return BadRequest(new { Message = "Session ID is required." });
        }

        var status = await _ecgDataService.GetStaffReportStatusAsync(sessionId, generateIfMissing);
        if (status == null)
        {
            return NotFound(new { Message = $"ECG session with ID '{sessionId}' was not found." });
        }

        Response.Headers.CacheControl = "no-store";
        return Ok(status);
    }

    // Retrieves authenticated report access paths for an ECG session report (never exposes public SAS URLs).
    [HttpGet("session/{sessionId}/url")]
    [ProducesResponseType(typeof(ReportStatusDto), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public Task<IActionResult> GetSessionReportUrl([FromRoute] string sessionId) =>
        GetSessionReportStatus(sessionId, generateIfMissing: true);

    // External report uploads remain disabled until explicit report versioning is authorised.
    [HttpPost("upload")]
    [Consumes("multipart/form-data")]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public IActionResult UploadReport([FromForm] IFormFile file, [FromForm] string? sessionId) =>
        StatusCode(StatusCodes.Status403Forbidden, new { Message = "External report uploads are unavailable." });
}
