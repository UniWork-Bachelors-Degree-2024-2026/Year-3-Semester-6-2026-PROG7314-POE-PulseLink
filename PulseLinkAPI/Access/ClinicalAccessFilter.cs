using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Mvc.Filters;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Access;

// Resource authorisation is enforced before existing clinical actions execute.
public sealed class ClinicalAccessFilter(AccessDirectory directory) : IAsyncActionFilter
{
    public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
    {
        var descriptor = (ControllerActionDescriptor)context.ActionDescriptor;
        if (descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.DeviceTransportController) ||
            descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.DeviceWorkflowController))
        {
            if (context.HttpContext.Features.Get<PulseLinkAPI.Transport.DeviceIdentity>() == null)
            {
                context.Result = new UnauthorizedResult(); return;
            }
            await next(); return;
        }
        if (descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.PatientPortalController) ||
            descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.AccessController) ||
            descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.AdministrationController) ||
            descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.FacilitiesController) ||
            descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.NotificationsController) ||
            descriptor.ControllerTypeInfo.AsType() == typeof(Controllers.SupportChatController)) { await next(); return; }
        var caller = context.HttpContext.Features.Get<CurrentAccess>();
        if (caller?.Account is { Role: AccessRoles.Patient, Status: "approved" })
        {
            var portal = context.HttpContext.RequestServices.GetRequiredService<PatientPortal>();
            var ctPatient = context.HttpContext.RequestAborted;
            if (descriptor.ControllerName == "EcgSessions" && descriptor.ActionName is "GetPatientSessions" or "GetPatientSessionPage")
            {
                var patient = await portal.OwnPatientAsync(caller, ctPatient);
                var requested = context.ActionArguments["patientId"]?.ToString();
                if (requested != caller.User.Uid && requested != patient?.Id)
                    throw new AccessProblem(403, "Only your own session status is available.");
                var after = context.ActionArguments.TryGetValue("after", out var cursor) ? cursor?.ToString() : null;
                var limit = context.ActionArguments.TryGetValue("limit", out var size) && size is int count ? count : 100;
                var page = await portal.SessionsAsync(caller, after, limit, ctPatient);
                context.Result = new OkObjectResult(descriptor.ActionName == "GetPatientSessions" ? (object)page.Items : page);
                context.HttpContext.Response.Headers.CacheControl = "no-store";
                return;
            }
            if (descriptor.ControllerName == "DoctorNotes" && descriptor.ActionName == "GetSessionNotes")
            {
                var release = await portal.ReleasedAsync(caller, context.ActionArguments["sessionId"]!.ToString()!, ctPatient);
                context.Result = new OkObjectResult(release.Notes);
                context.HttpContext.Response.Headers.CacheControl = "no-store";
                return;
            }
            context.Result = new ForbidResult(); return;
        }
        if (caller == null || caller.Account.Status != "approved" || !AccessRoles.IsStaff(caller.Account.Role) ||
            caller.Account.Role == AccessRoles.Nurse && caller.Account.SiteIds.Count != 1)
        {
            context.Result = new ForbidResult(); return;
        }
        var ct = context.HttpContext.RequestAborted;
        var service = context.HttpContext.RequestServices.GetRequiredService<IEcgDataService>();
        var args = context.ActionArguments;
        string Arg(string key) => args.TryGetValue(key, out var value) ? value?.ToString() ?? "" : "";
        async Task<ClinicalPatient> RequirePatient(string id)
        {
            var patient = (await directory.ReadPatientAsync(id, ct)).Value;
            if (patient == null || !AccessDirectory.CanReadClinicalPatient(caller.Account, patient))
                throw new AccessProblem(403, "This patient is not assigned to your care or site.");
            await directory.RequireSiteAsync(patient.SiteId, ct);
            return patient;
        }
        async Task<SessionAccessContext> RequireSession(string id)
        {
            var session = await service.GetSessionAccessContextAsync(id) ?? throw new AccessProblem(404, "Session not found.");
            await RequirePatient(session.PatientId);
            return session;
        }
        async Task RequireDevice(string id, string? patientId = null)
        {
            var device = (await directory.ReadDeviceAsync(id, ct)).Value;
            if (device?.Active != true || !caller.Account.SiteIds.Contains(device.SiteId)) throw new AccessProblem(403, "Device access is not authorised.");
            await directory.RequireSiteAsync(device.SiteId, ct);
            if (patientId != null && (await RequirePatient(patientId)).SiteId != device.SiteId) throw new AccessProblem(403, "The device and patient must belong to the same site.");
            if (caller.Account.Role != AccessRoles.Nurse) throw new AccessProblem(403, "Device operation is restricted to nurses.");
        }

        switch (descriptor.ControllerName)
        {
            case "EcgSessions":
                if (args.Values.OfType<StartSessionRequestDto>().FirstOrDefault() is { } start)
                {
                    await RequireDevice(start.DeviceId, start.PatientId);
                    var patient = await RequirePatient(start.PatientId);
                    await directory.RequireDoctorAsync(patient.PrimaryDoctorUid, patient.SiteId, ct);
                    start.PatientName = patient.FullName;
                }
                else if (descriptor.ActionName is "GetPatientSessions" or "GetPatientSessionPage") await RequirePatient(Arg("patientId"));
                else
                {
                    await RequireSession(Arg("id"));
                    if (descriptor.ActionName is not ("GetSessionSamples" or "GetRecordingProgress" or "GetRecordingBatches") && caller.Account.Role != AccessRoles.Nurse)
                        throw new AccessProblem(403, "Recording controls are restricted to nurses.");
                }
                break;
            case "EcgIngest":
                var payload = args.Values.OfType<IngestEcgPayloadDto>().Single();
                var session = await RequireSession(payload.SessionId);
                if (session.DeviceId != payload.DeviceId ||
                    payload.PatientId != null && payload.PatientId != session.PatientId)
                    throw new AccessProblem(409, "Telemetry does not match the authorised session.");
                await RequireDevice(payload.DeviceId, session.PatientId);
                payload.PatientId = session.PatientId;
                payload.PatientName = (await RequirePatient(session.PatientId)).FullName;
                break;
            case "DoctorNotes":
                if (args.Values.OfType<CreateDoctorNoteDto>().FirstOrDefault() is { } note)
                {
                    if (caller.Account.Role != AccessRoles.Doctor) throw new AccessProblem(403, "Only an assigned doctor may author an assessment.");
                    var noteSession = await RequireSession(note.SessionId);
                    if (noteSession.Status == "Discarded") throw new AccessProblem(410, "This recording was discarded.");
                    if (noteSession.PatientId != note.PatientId) throw new AccessProblem(400, "The patient and session do not match.");
                    note.DoctorId = caller.User.Uid;
                    note.DoctorName = DoctorChartService.ResolveDoctorName(caller);
                }
                else
                {
                    var notesSession = await RequireSession(Arg("sessionId"));
                    if (notesSession.Status == "Discarded") throw new AccessProblem(410, "This recording was discarded.");
                }
                break;
            case "DoctorChart":
                // Resource-level assignment, site, and completed-session rules are enforced in DoctorChartService.
                break;
            case "Devices":
                if (args.Values.OfType<DeviceRegistrationDto>().FirstOrDefault() is { } registration)
                    await RequireDevice(registration.DeviceId, registration.PatientId);
                else if (caller.Account.Role == AccessRoles.Doctor)
                {
                    var deviceId = Arg("deviceId");
                    var assignedDevice = (await directory.ReadDeviceAsync(deviceId, ct)).Value;
                    var status = await service.GetDeviceStatusAsync(deviceId);
                    if (assignedDevice?.Active != true || status == null || !caller.Account.SiteIds.Contains(assignedDevice.SiteId))
                        throw new AccessProblem(403, "Device access is not authorised.");
                    var assignedPatient = await RequirePatient(status.PatientId);
                    if (assignedPatient.SiteId != assignedDevice.SiteId) throw new AccessProblem(403, "Device access is not authorised.");
                }
                else await RequireDevice(Arg("deviceId"));
                break;
            case "Reports":
                if (descriptor.ActionName == "UploadReport")
                    throw new AccessProblem(403, "External report uploads are unavailable until report versioning is implemented.");
                var sessionId = Arg("sessionId");
                if (descriptor.ActionName == "GetReportByFileName")
                {
                    var file = Arg("fileName");
                    const string prefix = "PulseLink_Report_", suffix = ".pdf";
                    if (!file.StartsWith(prefix, StringComparison.Ordinal) || !file.EndsWith(suffix, StringComparison.Ordinal))
                        throw new AccessProblem(404, "Report not found.");
                    sessionId = file[prefix.Length..^suffix.Length];
                }
                var reportSession = await RequireSession(sessionId);
                if (reportSession.Status != "Completed") throw new AccessProblem(409, "Reports require a complete, finalised recording.");
                break;
            case "Alerts":
                // This controller filters and dismisses alerts using patient-specific authorisation.
                break;
            default:
                throw new AccessProblem(403, "An explicit resource access policy is required.");
        }
        await next();
    }
}

public sealed class AccessExceptionFilter(ILogger<AccessExceptionFilter> logger) : IExceptionFilter
{
    public void OnException(ExceptionContext context)
    {
        if (context.Exception is not AccessProblem problem) return;
        logger.LogWarning("Access operation returned status {Status}.", problem.Status);
        context.Result = new ObjectResult(new ProblemDetails { Status = problem.Status, Title = problem.Message }) { StatusCode = problem.Status };
        context.ExceptionHandled = true;
    }
}

public sealed record SessionAccessContext(string PatientId, string DeviceId, string Status);
