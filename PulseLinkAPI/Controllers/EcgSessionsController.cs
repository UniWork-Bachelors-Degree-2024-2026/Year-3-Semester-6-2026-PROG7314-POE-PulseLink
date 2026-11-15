//Controller managing remote ECG monitoring session lifecycle and patient historical summaries.

using Microsoft.AspNetCore.Mvc;    // (Microsoft, 2024)
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Controllers;

[ApiController]                    // (Microsoft, 2024)
[Route("api/ecg/sessions")]
public class EcgSessionsController : ControllerBase
{
    private readonly IEcgDataService _ecgDataService;
    private readonly ILogger<EcgSessionsController> _logger;

    public EcgSessionsController(IEcgDataService ecgDataService, ILogger<EcgSessionsController> logger)
    {
        _ecgDataService = ecgDataService;
        _logger = logger;
    }

    // Initialises a new remote ECG monitoring session for an authorised patient.
    [HttpPost("start")]
    [ProducesResponseType(typeof(StartSessionResponseDto), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> StartSession([FromBody] StartSessionRequestDto request)
    {
        if (!ModelState.IsValid)
        {
            return BadRequest(ModelState);
        }

        var result = await _ecgDataService.StartSessionAsync(request);

        return StatusCode(StatusCodes.Status201Created, result);
    }

    // Concludes an active recording session and calculates final session statistics.
    [HttpPost("{id}/stop")]
    [ProducesResponseType(typeof(EcgSessionSummaryDto), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> StopSession([FromRoute] string id, [FromQuery, System.ComponentModel.DataAnnotations.Range(0, int.MaxValue)] int? recordedDurationSeconds = null)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            return BadRequest(new { Message = "Session ID is required." });
        }

        var summary = await _ecgDataService.StopSessionAsync(id, recordedDurationSeconds);

        if (summary == null)
        {
            return NotFound(new { Message = $"ECG recording session with ID '{id}' was not found." });
        }

        return Ok(summary);
    }

    [HttpPost("{id}/recording-state")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> SetRecordingState([FromRoute] string id, [FromQuery] bool paused)
    {
        return await _ecgDataService.SetSessionPausedAsync(id, paused) ? NoContent() : NotFound();
    }

    [HttpPost("{id}/discard")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DiscardSession([FromRoute] string id)
    {
        if (string.IsNullOrWhiteSpace(id)) return BadRequest(new { Message = "Session ID is required." });
        return await _ecgDataService.DiscardSessionAsync(id) ? NoContent() : NotFound();
    }

    // Retrieves all historical ECG monitoring sessions for a specific patient.
    [HttpGet("patient/{patientId}")]
    [ProducesResponseType(typeof(List<EcgSessionSummaryDto>), StatusCodes.Status200OK)]
    public async Task<IActionResult> GetPatientSessions([FromRoute] string patientId)
    {
        if (string.IsNullOrWhiteSpace(patientId))
        {
            return BadRequest(new { Message = "Patient ID is required." });
        }

        var sessions = await _ecgDataService.GetPatientSessionsAsync(patientId);

        return Ok(sessions);
    }

    // Retrieves high-frequency 250 Hz sample points for oscilloscope waveform rendering.
    [HttpPost("{id}/complete")]
    public async Task<IActionResult> CompleteRecording(string id, CompleteRecordingRequest request)
    {
        var progress = await _ecgDataService.CompleteRecordingAsync(id, request);
        if (progress == null) return NotFound();
        return progress.Status == "Completed" ? Ok(progress) : StatusCode(202, progress);
    }

    [HttpGet("patient/{patientId}/page")]
    public async Task<IActionResult> GetPatientSessionPage(string patientId, [FromQuery] string? after = null,
        [FromQuery, System.ComponentModel.DataAnnotations.Range(1, 100)] int limit = 20) =>
        Ok(await _ecgDataService.GetPatientSessionPageAsync(patientId, after, limit));

    [HttpGet("{id}/progress")]
    public async Task<IActionResult> GetRecordingProgress(string id)
    {
        var progress = await _ecgDataService.GetRecordingProgressAsync(id);
        return progress == null ? NotFound() : Ok(progress);
    }

    [HttpGet("{id}/batches")]
    public async Task<IActionResult> GetRecordingBatches(string id,
        [FromQuery, System.ComponentModel.DataAnnotations.Range(-1, 599)] int afterSequence = -1,
        [FromQuery, System.ComponentModel.DataAnnotations.Range(1, 100)] int limit = 20)
    {
        var page = await _ecgDataService.GetRecordingBatchesAsync(id, afterSequence, limit);
        return page == null ? NotFound() : Ok(page);
    }

    [HttpGet("{id}/samples")]
    [ProducesResponseType(typeof(EcgSessionSamplesResponseDto), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetSessionSamples([FromRoute] string id)
    {
        if (string.IsNullOrWhiteSpace(id))
        {
            return BadRequest(new { Message = "Session ID is required." });
        }

        var samplesDto = await _ecgDataService.GetSessionSamplesAsync(id);

        if (samplesDto == null)
        {
            return NotFound(new { Message = $"No sample points found for session ID '{id}'." });
        }

        return Ok(samplesDto);
    }
}
