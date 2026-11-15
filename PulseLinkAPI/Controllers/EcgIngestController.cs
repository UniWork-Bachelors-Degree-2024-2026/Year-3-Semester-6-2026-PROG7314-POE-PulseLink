// DESCRIPTION:
// Controller responsible for receiving real-time 250 Hz analogue ECG telemetry batches
// and estimated BPM calculations transmitted over Wi-Fi from the ESP32-C3 hardware.

using Microsoft.AspNetCore.Mvc;    // (Microsoft, 2024)
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Controllers;

[ApiController]                    // (Microsoft, 2024)
[Route("api/ecg")]
public class EcgIngestController : ControllerBase
{
    private readonly IEcgDataService _ecgDataService;
    private readonly ILogger<EcgIngestController> _logger;

    public EcgIngestController(IEcgDataService ecgDataService, ILogger<EcgIngestController> logger)
    {
        _ecgDataService = ecgDataService;
        _logger = logger;
    }

    // Ingests a telemetry batch from the ESP32-C3 microcontroller containing 250 Hz analogue ECG samples.
    // param payload Structured telemetry payload matching the firmware contract.
    // returns HTTP 200 OK with ingestion confirmation and count of processed samples.
    [HttpPost("ingest")]
    [RequestSizeLimit(32768)]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> IngestTelemetry([FromBody] IngestEcgPayloadDto payload)
    {
        if (!ModelState.IsValid)
        {
            return BadRequest(ModelState);
        }

        return Ok(await _ecgDataService.IngestRecordingBatchAsync(payload));
    }
}
