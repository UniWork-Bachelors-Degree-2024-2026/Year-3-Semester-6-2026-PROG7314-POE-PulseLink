//Controller managing clinical review notes and patient recommendations authored by authorised doctors.

using Microsoft.AspNetCore.Mvc;    // (Microsoft, 2024)
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Controllers;

[ApiController]
[Route("api/doctor/notes")]
public class DoctorNotesController : ControllerBase
{
    private readonly IEcgDataService _ecgDataService;
    private readonly ILogger<DoctorNotesController> _logger;

    public DoctorNotesController(IEcgDataService ecgDataService, ILogger<DoctorNotesController> logger)
    {
        _ecgDataService = ecgDataService;
        _logger = logger;
    }

    // Attaches clinical impression and recommendations to a recorded ECG session.
    [HttpPost]
    [ProducesResponseType(typeof(DoctorNoteDto), StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> AddClinicalNote([FromBody] CreateDoctorNoteDto request)
    {
        if (!ModelState.IsValid)
        {
            return BadRequest(ModelState);
        }

        var noteDto = await _ecgDataService.AddDoctorNoteAsync(request);

        return StatusCode(StatusCodes.Status201Created, noteDto);
    }

    // Retrieves all clinical assessment notes associated with an ECG recording session.
    [HttpGet("session/{sessionId}")]
    [ProducesResponseType(typeof(List<DoctorNoteDto>), StatusCodes.Status200OK)]
    public async Task<IActionResult> GetSessionNotes([FromRoute] string sessionId)
    {
        if (string.IsNullOrWhiteSpace(sessionId))
        {
            return BadRequest(new { Message = "Session ID is required." });
        }

        var notes = await _ecgDataService.GetDoctorNotesAsync(sessionId);

        return Ok(notes);
    }
}
