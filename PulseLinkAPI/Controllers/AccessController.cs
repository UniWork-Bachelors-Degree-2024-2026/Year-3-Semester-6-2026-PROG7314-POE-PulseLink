using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Transport;

namespace PulseLinkAPI.Controllers;

[ApiController, Authorize, Route("api/access")]
public sealed class AccessController(
    AccessDirectory directory,
    IAccessStore store,
    DeviceRecordingAccess recordings,
    NurseWorkflow workflow,
    PatientPortal portal,
    DoctorChartService chart) : ControllerBase
{
    [HttpPut("patients/{id}/login"), Authorize(Roles = "administrator,nurse")]
    public Task<PatientLoginLink> LinkPatientLogin(string id, PatientLoginRequest request, CancellationToken ct) =>
        portal.LinkAsync(Caller, id, request, ct);

    [HttpPost("sessions/{id}/release"), Authorize(Roles = AccessRoles.Doctor)]
    public Task<PatientRelease> ReleasePatientResults(string id, CancellationToken ct) => portal.ReleaseAsync(Caller, id, ct);

    private CurrentAccess Caller => HttpContext.Features.Get<CurrentAccess>()!;
    [HttpGet("me")]
    public ActionResult<AccessAccount> Me() => Caller.Account;
    [HttpPost("devices/{id}/authorisation"), Authorize(Roles = AccessRoles.Nurse)]
    public Task<NurseGrant> ApproveScreen(string id, ScreenApprovalRequest request, CancellationToken ct) => workflow.ApproveAsync(Caller, id, request.Code, ct);
    [HttpGet("devices/{id}/authorisation"), Authorize(Roles = AccessRoles.Nurse)]
    [ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
    public async Task<ActionResult<NurseGrant>> ScreenAuthorisation(string id, CancellationToken ct)
    {
        var grant = await workflow.CurrentForNurseAsync(Caller, id, ct);
        return grant == null ? NoContent() : Ok(grant);
    }

    [HttpPost("recordings/{id}/upload-permit"), Authorize(Roles = AccessRoles.Nurse)]
    public Task<RecordingUploadPermit> Permit(string id, UploadPermitRequest request, CancellationToken ct) =>
        recordings.PermitAsync(Caller, id, request.ValidHours, ct);

    [HttpPost("role-request")]
    public Task<AccessAccount> RequestRole(RoleRequest request, CancellationToken ct) => directory.RequestRoleAsync(Caller, request.RequestedRole, ct);

    [HttpGet("sites")]
    public async Task<IReadOnlyList<ClinicSite>> Sites(CancellationToken ct)
    {
        var result = new List<ClinicSite>();
        if (Caller.Account.Status != "approved") return result;
        foreach (var id in Caller.Account.SiteIds.Take(50))
        {
            var site = (await store.ReadAsync<ClinicSite>($"{AccessDirectory.Root}/sites/{FirebaseAccessStore.ValidatePath(id)}", ct)).Value;
            if (site?.Active == true) result.Add(site);
        }
        return result;
    }

    [HttpGet("patients"), Authorize(Roles = "doctor,nurse")]
    [ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
    public Task<AccessPage<DoctorPatientSummaryDto>> AssignedPatients(
        [FromQuery] string? after = null,
        [FromQuery] int limit = 50,
        CancellationToken ct = default) =>
        chart.ListAssignedPatientsAsync(Caller, after, limit, ct);

    [HttpGet("doctors"), Authorize(Roles = "administrator,nurse")]
    [ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
    public async Task<IReadOnlyList<ScreenDoctor>> ApprovedDoctors(CancellationToken ct = default)
    {
        var accounts = await store.ListAsync<AccessAccount>($"{AccessDirectory.Root}/accounts", limit: 100, ct: ct);
        var siteDoctors = accounts.Items
            .Where(a => a.Role == AccessRoles.Doctor && a.Status == "approved" &&
                        (Caller.Account.Role == AccessRoles.Administrator ||
                         a.SiteIds.Any(site => Caller.Account.SiteIds.Contains(site))))
            .ToList();
        return siteDoctors
            .Select(d => new ScreenDoctor(
                d.Uid,
                string.IsNullOrWhiteSpace(d.DisplayName) ? d.Email : d.DisplayName,
                d.Email))
            .ToList();
    }

    [HttpPost("patients"), Authorize(Roles = "administrator,nurse")]
    public async Task<ActionResult<ClinicalPatient>> Register(PatientRegistrationRequest request, CancellationToken ct)
    {
        var patient = await directory.RegisterPatientAsync(Caller, request, ct);
        return Created($"/api/access/patients/{patient.Id}", patient);
    }

    [HttpGet("patients/{id}"), Authorize(Roles = "administrator,nurse,doctor")]
    public async Task<ActionResult<ClinicalPatient>> Patient(string id, CancellationToken ct)
    {
        var patient = (await directory.ReadPatientAsync(id, ct)).Value;
        if (patient == null) return NotFound();
        if (Caller.Account.Role != AccessRoles.Administrator && !AccessDirectory.CanReadClinicalPatient(Caller.Account, patient)) return Forbid();
        return patient;
    }

    [HttpGet("patients/{id}/care-plan"), Authorize(Roles = "doctor,nurse")]
    public async Task<ActionResult<CarePlan>> CarePlan(string id, CancellationToken ct)
    {
        await RequireClinicalPatient(id, ct);
        return (await store.ReadAsync<CarePlan>($"clinicalCarePlans/{FirebaseAccessStore.ValidatePath(id)}", ct)).Value ?? new CarePlan();
    }

    [HttpPut("patients/{id}/care-plan"), Authorize(Roles = "doctor")]
    public async Task<ActionResult<CarePlan>> SaveCarePlan(string id, CarePlan plan, CancellationToken ct)
    {
        await RequireClinicalPatient(id, ct);
        plan.UpdatedAtUtc = DateTimeOffset.UtcNow.ToString("O");
        plan.UpdatedByDoctor = Caller.User.Uid;
        await store.WriteAsync($"clinicalCarePlans/{FirebaseAccessStore.ValidatePath(id)}", plan, ct: ct);
        return plan;
    }

    private async Task RequireClinicalPatient(string id, CancellationToken ct)
    {
        var patient = (await directory.ReadPatientAsync(id, ct)).Value;
        if (patient == null || !AccessDirectory.CanReadClinicalPatient(Caller.Account, patient))
            throw new AccessProblem(403, "This patient is not assigned to your care or site.");
        await directory.RequireSiteAsync(patient.SiteId, ct);
    }
}

[ApiController, Authorize(Roles = AccessRoles.Administrator), Route("api/admin")]
public sealed class AdministrationController(AccessDirectory directory, IAccessStore store, DeviceCredentials credentials) : ControllerBase
{
    private CurrentAccess Caller => HttpContext.Features.Get<CurrentAccess>()!;

    [HttpPost("devices/{id}/credential")]
    public async Task<IssuedDeviceCredential> RotateCredential(string id, CancellationToken ct)
    {
        Response.Headers.CacheControl = "no-store";
        return await credentials.RotateAsync(id, ct);
    }

    [HttpDelete("devices/{id}/credential")]
    public async Task<IActionResult> RevokeCredential(string id, CancellationToken ct)
    {
        await credentials.RevokeAsync(id, ct); return NoContent();
    }

    [HttpGet("accounts")]
    public Task<AccessPage<AccessAccount>> Accounts([FromQuery] string? after, CancellationToken ct) =>
        store.ListAsync<AccessAccount>($"{AccessDirectory.Root}/accounts", after, ct: ct);

    [HttpPut("accounts/{uid}/approval")]
    public Task<AccessAccount> Approve(string uid, ApprovalRequest request, CancellationToken ct) => directory.ApproveAsync(Caller, uid, request, ct);

    [HttpGet("sites")]
    public Task<AccessPage<ClinicSite>> Sites([FromQuery] string? after, CancellationToken ct) =>
        store.ListAsync<ClinicSite>($"{AccessDirectory.Root}/sites", after, ct: ct);

    [HttpPost("sites")]
    public async Task<ActionResult<ClinicSite>> CreateSite(SiteRequest request, CancellationToken ct)
    {
        var site = await directory.CreateSiteAsync(request, ct);
        return Created($"/api/admin/sites/{site.Id}", site);
    }

    [HttpGet("patients")]
    public Task<AccessPage<ClinicalPatient>> Patients([FromQuery] string? after, CancellationToken ct) =>
        store.ListAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients", after, ct: ct);

    [HttpPut("patients/{id}/assignment")]
    public Task<ClinicalPatient> AssignPatient(string id, PatientAssignmentRequest request, CancellationToken ct) => directory.AssignPatientAsync(id, request, ct);

    [HttpPut("devices/{id}/site")]
    public Task<SiteDevice> AssignDevice(string id, DeviceSiteRequest request, CancellationToken ct) => directory.AssignDeviceAsync(id, request, ct);
}
