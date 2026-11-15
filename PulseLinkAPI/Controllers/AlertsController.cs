//Controller managing physiological threshold and hardware detachment alerts for clinical review.

using Microsoft.AspNetCore.Mvc;    // (Microsoft, 2024)
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;
using PulseLinkAPI.Access;

namespace PulseLinkAPI.Controllers;

[ApiController]
[Route("api/alerts")]
public class AlertsController : ControllerBase
{
    private readonly IEcgDataService _ecgDataService;
    private readonly ILogger<AlertsController> _logger;
    private readonly AccessDirectory _directory;

    public AlertsController(IEcgDataService ecgDataService, ILogger<AlertsController> logger, AccessDirectory directory)
    {
        _ecgDataService = ecgDataService;
        _logger = logger;
        _directory = directory;
    }

    // Retrieves all active clinical alerts (e.g. Lead detachment or elevated estimated BPM).
    [HttpGet]
    [ProducesResponseType(typeof(List<AlertDto>), StatusCodes.Status200OK)]
    public async Task<IActionResult> GetAlerts()
    {
        var alerts = await AllowedAlertsAsync();

        return Ok(alerts);
    }

    // Dismisses an individual alert by its ID.
    [HttpPost("{alertId}/dismiss")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> DismissAlert(string alertId)
    {
        if (!(await AllowedAlertsAsync()).Any(a => a.AlertId == alertId)) return NotFound();
        var result = await _ecgDataService.DismissAlertAsync(alertId);
        _logger.LogInformation("[ALERTS] Dismissed alert: {AlertId} (Success: {Result})", alertId, result);
        return Ok(new { success = result, alertId });
    }

    // Dismisses all active alerts.
    [HttpPost("dismiss-all")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> DismissAllAlerts()
    {
        var allowed = await AllowedAlertsAsync();
        foreach (var alert in allowed) await _ecgDataService.DismissAlertAsync(alert.AlertId);
        return Ok(new { success = true, dismissed = allowed.Count });
    }

    private async Task<List<AlertDto>> AllowedAlertsAsync()
    {
        var account = HttpContext.Features.Get<CurrentAccess>()!.Account;
        var allowed = new List<AlertDto>();
        var decisions = new Dictionary<string, bool>(StringComparer.Ordinal);
        foreach (var alert in await _ecgDataService.GetActiveAlertsAsync())
        {
            if (string.IsNullOrWhiteSpace(alert.PatientId)) continue;
            if (!decisions.TryGetValue(alert.PatientId, out var canRead))
            {
                var patient = (await _directory.ReadPatientAsync(alert.PatientId, HttpContext.RequestAborted)).Value;
                canRead = patient != null && AccessDirectory.CanReadClinicalPatient(account, patient);
                decisions[alert.PatientId] = canRead;
            }
            if (canRead) allowed.Add(alert);
        }
        return allowed;
    }
}
