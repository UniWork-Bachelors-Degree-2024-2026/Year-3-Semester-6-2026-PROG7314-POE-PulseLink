using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Access;

namespace PulseLinkAPI.Controllers;

[ApiController]
[Route("api/facilities")]
[Authorize(AuthenticationSchemes = FirebaseBearerHandler.SchemeName)]
public sealed class FacilitiesController : ControllerBase
{
    private readonly PublicFacilityDirectory _directory;

    public FacilitiesController(PublicFacilityDirectory directory)
    {
        _directory = directory;
    }

    [HttpGet]
    public ActionResult<PublicFacilityDirectoryResponse> GetFacilities(
        [FromQuery] string? query = null,
        [FromQuery] string? type = null)
    {
        return Ok(_directory.Search(query, type));
    }

    [HttpGet("{facilityId}")]
    public ActionResult<PublicFacilityRecord> GetFacilityById(string facilityId)
    {
        var facility = _directory.GetById(facilityId);
        if (facility is null)
        {
            return NotFound(new
            {
                code = "facility_not_found",
                message = "The requested public facility is not in the reviewed Gqeberha directory."
            });
        }

        return Ok(facility);
    }
}
