using System.ComponentModel.DataAnnotations;
using PulseLinkAPI.Access;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Transport;

public sealed record RecordingUploadPermit(string SessionId, string DeviceId, string SiteId, string NurseUid,
    DateTimeOffset ExpiresAtUtc);
public sealed class UploadPermitRequest
{
    [Range(1, 72)] public int ValidHours { get; set; } = 24;
}

public sealed class DeviceRecordingAccess(IAccessStore store, AccessDirectory directory, IEcgDataService data)
{
    private static string Path(string id)
    {
        if (!System.Text.RegularExpressions.Regex.IsMatch(id, "^SESSION-[a-fA-F0-9]{32}$"))
            throw new AccessProblem(400, "Invalid session identifier.");
        return $"deviceUploadPermits/{id}";
    }
    public async Task<RecordingUploadPermit> PermitAsync(CurrentAccess caller, string id, int hours, CancellationToken ct)
    {
        if (caller.Account.Role != AccessRoles.Nurse || caller.Account.Status != "approved" || caller.Account.SiteIds.Count != 1)
            throw new AccessProblem(403, "An approved nurse must authorise this upload.");
        if (hours is < 1 or > 72) throw new AccessProblem(400, "Use an upload permission between one and 72 hours.");
        var session = await data.GetSessionAccessContextAsync(id) ?? throw new AccessProblem(404, "Session not found.");
        var device = (await directory.ReadDeviceAsync(session.DeviceId, ct)).Value;
        var patient = (await directory.ReadPatientAsync(session.PatientId, ct)).Value;
        if (device?.Active != true || patient == null || device.SiteId != patient.SiteId || !AccessDirectory.CanReadClinicalPatient(caller.Account, patient))
            throw new AccessProblem(403, "The patient and device must belong to the nurse's site.");
        await directory.RequireSiteAsync(patient.SiteId, ct);
        await directory.RequireDoctorAsync(patient.PrimaryDoctorUid, patient.SiteId, ct);
        var permit = new RecordingUploadPermit(id, device.DeviceId, patient.SiteId, caller.User.Uid, DateTimeOffset.UtcNow.AddHours(hours));
        await store.WriteAsync(Path(id), permit, ct: ct);
        var eventId = id[8..];
        await store.WriteAsync($"deviceEvents/{device.DeviceId}/{eventId}",
            new DeviceEvent(eventId, device.DeviceId, patient.SiteId, id, "upload-permitted", permit.ExpiresAtUtc), ct: ct);
        return permit;
    }
    public async Task<RecordingUploadPermit> RequireAsync(DeviceIdentity device, string id, CancellationToken ct)
    {
        var permit = (await store.ReadAsync<RecordingUploadPermit>(Path(id), ct)).Value;
        if (permit == null || permit.DeviceId != device.DeviceId || permit.SiteId != device.SiteId || permit.ExpiresAtUtc <= DateTimeOffset.UtcNow)
            throw new AccessProblem(403, "No current upload permission exists for this device and recording.");
        var nurse = (await store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/{permit.NurseUid}", ct)).Value;
        var session = await data.GetSessionAccessContextAsync(id) ?? throw new AccessProblem(404, "Session not found.");
        var patient = (await directory.ReadPatientAsync(session.PatientId, ct)).Value;
        if (nurse?.Role != AccessRoles.Nurse || nurse.Status != "approved" || nurse.SiteIds.Count != 1 ||
            patient == null || session.DeviceId != device.DeviceId || patient.SiteId != device.SiteId || !AccessDirectory.CanReadClinicalPatient(nurse, patient))
            throw new AccessProblem(403, "The recording assignment is no longer authorised.");
        await directory.RequireDoctorAsync(patient.PrimaryDoctorUid, patient.SiteId, ct);
        return permit;
    }
}
