//Controller managing ESP32-C3 hardware registration and live connection telemetry.

using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;    // (Microsoft, 2024)
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Controllers;

[ApiController]
[Authorize]
[Route("api/devices")]
public class DevicesController : ControllerBase
{
    private readonly IEcgDataService _ecgDataService;
    private readonly ILogger<DevicesController> _logger;

    public DevicesController(IEcgDataService ecgDataService, ILogger<DevicesController> logger)
    {
        _ecgDataService = ecgDataService;
        _logger = logger;
    }

    // Registers and binds an ESP32-C3 hardware monitor to a patient account.
    [HttpPost("register")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> RegisterDevice([FromBody] DeviceRegistrationDto registration)
    {
        if (!ModelState.IsValid)
        {
            return BadRequest(ModelState);
        }

        var success = await _ecgDataService.RegisterDeviceAsync(registration);

        if (!success)
        {
            return BadRequest(new { Message = "Failed to register hardware monitor." });
        }

        return Ok(new
        {
            Success = true,
            DeviceId = registration.DeviceId,
            PatientId = registration.PatientId,
            Message = "Device successfully linked to patient profile."
        });
    }

    // Retrieves live connection, lead detachment, and health telemetry for an ESP32-C3 device.
    [HttpGet("{deviceId}/status")]
    [ProducesResponseType(typeof(DeviceStatusDto), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetDeviceStatus([FromRoute] string deviceId)
    {
        if (string.IsNullOrWhiteSpace(deviceId))
        {
            return BadRequest(new { Message = "Device ID is required." });
        }

        var status = await _ecgDataService.GetDeviceStatusAsync(deviceId);

        if (status == null)
        {
            return NotFound(new { Message = $"Hardware monitor with ID '{deviceId}' is not registered." });
        }

        return Ok(status);
    }
}
