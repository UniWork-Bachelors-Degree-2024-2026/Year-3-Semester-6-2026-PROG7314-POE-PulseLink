using System.ComponentModel.DataAnnotations;
using System.Security.Cryptography;
using System.Text;
using PulseLinkAPI.Access;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Transport;

public sealed record NurseGrant(string Id, string DeviceId, string SiteId, string NurseUid, string NurseName,
    DateTimeOffset ApprovedAtUtc, DateTimeOffset ExpiresAtUtc, DateTimeOffset SyncUntilUtc);
public sealed record NurseChallenge(string Hash, DateTimeOffset ExpiresAtUtc, int Attempts, NurseGrant? Grant = null);
public sealed record ScreenCode(string Code, DateTimeOffset ExpiresAtUtc);
public sealed class ScreenApprovalRequest { [Required, RegularExpression("^[0-9]{6}$")] public string Code { get; set; } = ""; }
public sealed record ScreenPatient(string Id, string FullName, DateOnly? DateOfBirth, string PrimaryDoctorUid,
    string DoctorName, int Version, bool RequiresDoctorAssignment = false);
public sealed class ScreenDoctorAssignmentRequest
{
    [Required] public string GrantId { get; set; } = "";
    [Required, StringLength(128)] public string PrimaryDoctorUid { get; set; } = "";
    [Range(1, int.MaxValue)] public int ExpectedVersion { get; set; }
}
public sealed record ScreenDoctor(string Uid, string Name, string Email = "");
public sealed record ClinicPage(NurseGrant Grant, string SiteName, AccessPage<ScreenPatient> Patients, AccessPage<ScreenDoctor> Doctors);
public sealed class ScreenPatientRequest
{
    [Required]
    public string GrantId { get; set; } = "";
    public DateTimeOffset RegisteredAtUtc { get; set; }

    [Required]
    public PatientRegistrationRequest Patient { get; set; } = new();
}
public sealed class ScreenRecordingRequest
{
    [Required]
    public string GrantId { get; set; } = "";

    public Guid RecordingId { get; set; }
    [Required, StringLength(64)]
    public string PatientId { get; set; } = "";

    [Required, StringLength(128)]
    public string PrimaryDoctorUid { get; set; } = "";

    [Range(1, int.MaxValue)]
    public int PatientVersion { get; set; }
    public DateTimeOffset StartedAtUtc { get; set; }
}

// A short physical-presence code authorises an eight-hour nurse shift, not a Firebase login on the device.
public sealed class NurseWorkflow(IAccessStore store, AccessDirectory directory, IEcgDataService data, DeviceRecordingAccess recordings)
{
    private static string ChallengePath(string id) => $"screenAuthorisations/{id}";
    private static string GrantPath(string id)
    {
        if (!DeviceCredentials.ValidId(id)) throw new AccessProblem(400, "Invalid nurse permission.");
        return $"screenNurseGrants/{id}";
    }
    private static string CodeHash(string device, string code) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(device + ":" + code)));
    private static string Name(AccessAccount account)
    {
        var value = string.IsNullOrWhiteSpace(account.DisplayName) ? account.Email : account.DisplayName;
        return value[..Math.Min(80, value.Length)];
    }
    private static void RequireNurse(AccessAccount account, string site)
    {
        if (account.Role != AccessRoles.Nurse || account.Status != "approved" || account.SiteIds.Count != 1 || account.SiteIds[0] != site)
            throw new AccessProblem(403, "An approved nurse at this device's site must authorise the screen.");
    }
    private async Task<SiteDevice> Device(string id, CancellationToken ct)
    {
        if (!DeviceCredentials.ValidId(id)) throw new AccessProblem(400, "Invalid device identifier.");
        var device = (await directory.ReadDeviceAsync(id, ct)).Value;
        if (device?.Active != true) throw new AccessProblem(403, "This device is not active.");
        await directory.RequireSiteAsync(device.SiteId, ct); return device;
    }
    private async Task SaveGrant(NurseGrant grant, CancellationToken ct)
    {
        var row = await store.ReadAsync<NurseGrant>(GrantPath(grant.Id), ct);
        if (row.Value == null)
        {
            try
            {
                await store.WriteAsync(GrantPath(grant.Id), grant, row.ETag, ct);
            }
            catch (AccessProblem problem)
                when (problem.Status == 409)
            { }
        }
    }
    public async Task<ScreenCode> BeginAsync(DeviceIdentity device, CancellationToken ct)
    {
        var code = RandomNumberGenerator.GetInt32(1000000).ToString("D6");
        var row = await store.ReadAsync<NurseChallenge>(ChallengePath(device.DeviceId), ct);
        var challenge = new NurseChallenge(CodeHash(device.DeviceId, code), DateTimeOffset.UtcNow.AddMinutes(5), 0);
        await store.WriteAsync(ChallengePath(device.DeviceId), challenge, row.ETag, ct);
        return new(code, challenge.ExpiresAtUtc);
    }
    public async Task<NurseGrant> ApproveAsync(CurrentAccess caller, string id, string code, CancellationToken ct)
    {
        var device = await Device(id, ct);
        RequireNurse(caller.Account, device.SiteId);
        if (!System.Text.RegularExpressions.Regex.IsMatch(code, "^[0-9]{6}$")) throw new AccessProblem(400, "Enter the six-digit screen code.");
        var row = await store.ReadAsync<NurseChallenge>(ChallengePath(id), ct);
        var challenge = row.Value;

        if (challenge == null || challenge.Attempts >= 5 || challenge.ExpiresAtUtc <= DateTimeOffset.UtcNow)
            throw new AccessProblem(410, "Screen code expired or locked. Request a new code on the screen.");

        if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(challenge.Hash), Encoding.ASCII.GetBytes(CodeHash(id, code))))
        {
            await store.WriteAsync(ChallengePath(id), challenge with { Attempts = challenge.Attempts + 1 }, row.ETag, ct);
            throw new AccessProblem(403, "The screen code does not match.");
        }
        if (challenge.Grant is { } previous)
        {
            if (previous.NurseUid != caller.User.Uid) throw new AccessProblem(409, "Another nurse has already approved this screen code.");
            await SaveGrant(previous, ct); return previous;
        }
        var now = DateTimeOffset.UtcNow;
        var grant = new NurseGrant(Guid.NewGuid().ToString("N"), id, device.SiteId, caller.User.Uid, Name(caller.Account), now, now.AddHours(8), now.AddHours(80));
        await store.WriteAsync(ChallengePath(id), challenge with { Grant = grant }, row.ETag, ct);
        await SaveGrant(grant, ct);
        return grant;
    }
    public async Task<NurseGrant?> CurrentAsync(DeviceIdentity device, CancellationToken ct)
    {
        var challenge = (await store.ReadAsync<NurseChallenge>(ChallengePath(device.DeviceId), ct)).Value;
        var grant = challenge?.Grant;
        if (grant == null || grant.SiteId != device.SiteId || grant.ExpiresAtUtc <= DateTimeOffset.UtcNow) return null;
        var nurse = (await store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/{grant.NurseUid}", ct)).Value;
        if (nurse == null) throw new AccessProblem(403, "The nurse account is unavailable.");
        RequireNurse(nurse, device.SiteId); await SaveGrant(grant, ct); return grant;
    }
    public async Task<NurseGrant?> CurrentForNurseAsync(CurrentAccess caller, string id, CancellationToken ct)
    {
        var device = await Device(id, ct);
        RequireNurse(caller.Account, device.SiteId);
        var grant = await CurrentAsync(new DeviceIdentity(id, device.SiteId), ct);
        return grant?.NurseUid == caller.User.Uid ? grant : null;
    }
    public async Task EndAsync(DeviceIdentity device, CancellationToken ct)
    {
        var row = await store.ReadAsync<NurseChallenge>(ChallengePath(device.DeviceId), ct);
        await store.WriteAsync(ChallengePath(device.DeviceId), new NurseChallenge("", DateTimeOffset.MinValue, 5), row.ETag, ct);
    }
    private async Task<(NurseGrant Grant, CurrentAccess Nurse)> RequireAsync(DeviceIdentity device, string id, DateTimeOffset? actionAt, CancellationToken ct)
    {
        var grant = (await store.ReadAsync<NurseGrant>(GrantPath(id), ct)).Value;
        if (grant == null || grant.DeviceId != device.DeviceId || grant.SiteId != device.SiteId)
            throw new AccessProblem(403, "Nurse permission does not match this device and site.");
        var account = (await store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/{grant.NurseUid}", ct)).Value;
        if (account == null) throw new AccessProblem(403, "Nurse account not found.");
        RequireNurse(account, device.SiteId); await directory.RequireSiteAsync(device.SiteId, ct);
        if (actionAt is { } captured)
        {
            if (captured < grant.ApprovedAtUtc || captured >= grant.ExpiresAtUtc || captured > DateTimeOffset.UtcNow.AddMinutes(1) || DateTimeOffset.UtcNow > grant.SyncUntilUtc)
                throw new AccessProblem(403, "This offline action is outside its authorised capture or synchronisation window.");
        }
        else
        {
            var current = await CurrentAsync(device, ct);
            if (current?.Id != id) throw new AccessProblem(403, "Approve this nurse shift again before accessing patients.");
        }
        return (grant, new CurrentAccess(new VerifiedUser(account.Uid, account.Email, true, account.DisplayName), account));
    }
    public async Task<ClinicPage> ClinicAsync(DeviceIdentity device, string id, string? afterPatient, string? afterDoctor, CancellationToken ct)
    {
        var (grant, _) = await RequireAsync(device, id, null, ct);
        if (afterPatient != null && !DeviceCredentials.ValidId(afterPatient) || afterDoctor != null && !DeviceCredentials.ValidId(afterDoctor))
            throw new AccessProblem(400, "Invalid clinic cursor.");
        var patients = await store.ListAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients", afterPatient, 2, ct);
        var doctors = await store.ListAsync<AccessAccount>($"{AccessDirectory.Root}/accounts", afterDoctor, 2, ct);
        var visible = new List<ScreenPatient>();
        foreach (var patient in patients.Items.Where(p => p.SiteId == device.SiteId))
        {
            try
            {
                var doctor = await directory.RequireDoctorAsync(patient.PrimaryDoctorUid, device.SiteId, ct);
                visible.Add(new(patient.Id, patient.FullName[..Math.Min(80, patient.FullName.Length)], patient.DateOfBirth, patient.PrimaryDoctorUid, Name(doctor), patient.Version));
            }
            catch (AccessProblem problem) when (problem.Status is 400 or 403 or 404)
            {
                visible.Add(new(patient.Id, patient.FullName[..Math.Min(80, patient.FullName.Length)],
                    patient.DateOfBirth, patient.PrimaryDoctorUid, "", patient.Version, true));
            }
        }
        var site = await directory.RequireSiteAsync(device.SiteId, ct);
        return new(grant, site.Name[..Math.Min(80, site.Name.Length)], new(visible, patients.NextCursor),
            new(doctors.Items.Where(d => d.Role == AccessRoles.Doctor && d.Status == "approved" && d.SiteIds.Contains(device.SiteId))
                .Select(d => new ScreenDoctor(d.Uid, Name(d))).ToList(), doctors.NextCursor));
    }
    public async Task<ScreenPatient> AssignDoctorAsync(DeviceIdentity device, string patientId, ScreenDoctorAssignmentRequest request, CancellationToken ct)
    {
        await RequireAsync(device, request.GrantId, null, ct);
        var patient = (await directory.ReadPatientAsync(patientId, ct)).Value;
        if (patient == null || patient.SiteId != device.SiteId)
            throw new AccessProblem(403, "This patient is not registered at this device's site.");
        var doctor = await directory.RequireDoctorAsync(request.PrimaryDoctorUid, device.SiteId, ct);
        // A lost response may be retried, but an unrelated subsequent edit must still conflict.
        if (patient.PrimaryDoctorUid == doctor.Uid &&
            (patient.Version == request.ExpectedVersion || patient.Version == request.ExpectedVersion + 1))
            return new(patient.Id, patient.FullName, patient.DateOfBirth, doctor.Uid, Name(doctor), patient.Version);
        if (patient.Version != request.ExpectedVersion)
            throw new AccessProblem(409, "Refresh the patient before assigning a doctor.");
        var currentEligible = false;
        try { await directory.RequireDoctorAsync(patient.PrimaryDoctorUid, device.SiteId, ct); currentEligible = true; }
        catch (AccessProblem problem) when (problem.Status is 400 or 403 or 404) { }
        if (currentEligible) throw new AccessProblem(409, "This patient already has an eligible primary doctor. Refresh the clinic list.");
        patient = await directory.AssignPatientAsync(patient.Id, new PatientAssignmentRequest
        {
            PrimaryDoctorUid = doctor.Uid,
            ExpectedVersion = request.ExpectedVersion,
            AdditionalDoctorUids = patient.AdditionalDoctorUids
        }, ct);
        return new(patient.Id, patient.FullName, patient.DateOfBirth, doctor.Uid, Name(doctor), patient.Version);
    }
    public async Task<ClinicalPatient> RegisterAsync(DeviceIdentity device, ScreenPatientRequest request, CancellationToken ct)
    {
        var (_, nurse) = await RequireAsync(device, request.GrantId, request.RegisteredAtUtc, ct);
        if (request.Patient.RegistrationId == null || request.Patient.RegistrationId == Guid.Empty || request.Patient.SiteId != device.SiteId)
            throw new AccessProblem(400, "Use a stable patient registration identifier at this site.");
        return await directory.RegisterPatientAsync(nurse, request.Patient, ct);
    }
    public async Task<StartSessionResponseDto> RecordingAsync(DeviceIdentity device, ScreenRecordingRequest request, CancellationToken ct)
    {
        var (_, nurse) = await RequireAsync(device, request.GrantId, request.StartedAtUtc, ct);
        var patient = await RecordingPatientAsync(device, request, ct);
        var result = await data.StartSessionAsync(new StartSessionRequestDto
        {
            RecordingId = request.RecordingId,
            PatientId = patient.Id,
            PatientName = patient.FullName,
            DeviceId = device.DeviceId,
            CaptureStartedAtUtc = request.StartedAtUtc,
            OperatorUid = nurse.User.Uid,
            SiteId = device.SiteId,
            AuthorisationGrantId = request.GrantId
        });
        await recordings.PermitAsync(nurse, result.SessionId, 72, ct); return result;
    }
    public async Task CheckRecordingAsync(DeviceIdentity device, ScreenRecordingRequest request, CancellationToken ct)
    {
        var (grant, _) = await RequireAsync(device, request.GrantId, null, ct);
        if (grant.ExpiresAtUtc <= DateTimeOffset.UtcNow.AddSeconds(90))
            throw new AccessProblem(403, "Approve a nurse shift with enough time for the whole recording.");
        await RecordingPatientAsync(device, request, ct);
    }
    private async Task<ClinicalPatient> RecordingPatientAsync(DeviceIdentity device, ScreenRecordingRequest request, CancellationToken ct)
    {
        var patient = (await directory.ReadPatientAsync(request.PatientId, ct)).Value;
        if (request.RecordingId == Guid.Empty || patient == null || patient.SiteId != device.SiteId || patient.PrimaryDoctorUid != request.PrimaryDoctorUid || patient.Version != request.PatientVersion)
            throw new AccessProblem(409, "Patient or doctor assignment changed. Review this recording before synchronising.");
        await directory.RequireDoctorAsync(patient.PrimaryDoctorUid, device.SiteId, ct);
        return patient;
    }
}
