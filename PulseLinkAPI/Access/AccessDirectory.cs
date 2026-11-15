namespace PulseLinkAPI.Access;

public sealed class AccessDirectory(IAccessStore store, IConfiguration configuration)
{
    public const string Root = "accessDirectory";
    private static string Key(string id) { FirebaseAccessStore.ValidatePath(id); if (id.Contains('/')) throw new AccessProblem(400, "Invalid identifier."); return id; }

    public async Task<AccessAccount> ResolveAsync(VerifiedUser user, CancellationToken ct = default)
    {
        var path = $"{Root}/accounts/{Key(user.Uid)}";
        var stored = await store.ReadAsync<AccessAccount>(path, ct);
        var account = stored.Value;
        var bootstrap = configuration["AccessControl:BootstrapAdministratorEmail"];
        var isBootstrap = user.EmailVerified && !string.IsNullOrWhiteSpace(bootstrap) &&
                          string.Equals(user.Email, bootstrap, StringComparison.OrdinalIgnoreCase);
        if (account?.Role == AccessRoles.Administrator && !isBootstrap)
        {
            account.Status = "suspended";
            account.Version++;
            await store.WriteAsync(path, account, stored.ETag, ct);
            return account;
        }
        if (account != null && (!isBootstrap || account.Role == AccessRoles.Administrator && account.Status == "approved"))
        {
            if (account.EmailVerified == user.EmailVerified && account.Email == user.Email) return account;
            account.Email = user.Email;
            account.EmailVerified = user.EmailVerified;
            account.Version++;
            await store.WriteAsync(path, account, stored.ETag, ct);
            return account;
        }
        account ??= new AccessAccount { Uid = user.Uid, Email = user.Email, DisplayName = user.DisplayName };
        account.EmailVerified = user.EmailVerified;
        if (isBootstrap) { account.Role = AccessRoles.Administrator; account.Status = "approved"; account.SiteIds = []; }
        account.Version++;
        try { await store.WriteAsync(path, account, stored.ETag, ct); }
        catch (AccessProblem problem) when (problem.Status == 409)
        {
            var concurrent = (await store.ReadAsync<AccessAccount>(path, ct)).Value;
            if (concurrent != null) return concurrent;
            throw;
        }
        return account;
    }

    public async Task<AccessAccount> RequestRoleAsync(CurrentAccess caller, string role, CancellationToken ct)
    {
        if (role is not (AccessRoles.Patient or AccessRoles.Doctor or AccessRoles.Nurse)) throw new AccessProblem(400, "Invalid requested role.");
        var path = $"{Root}/accounts/{Key(caller.User.Uid)}";
        var stored = await store.ReadAsync<AccessAccount>(path, ct);
        var account = stored.Value ?? throw new AccessProblem(404, "Account not found.");
        if (account.Role != AccessRoles.Patient || role == AccessRoles.Patient) return account;
        account.RequestedRole = role;
        account.Status = "pending";
        account.Version++;
        await store.WriteAsync(path, account, stored.ETag, ct);
        return account;
    }

    public async Task<ClinicSite> CreateSiteAsync(SiteRequest request, CancellationToken ct)
    {
        if (request.Latitude.HasValue != request.Longitude.HasValue) throw new AccessProblem(400, "Provide both latitude and longitude, or neither.");
        var site = new ClinicSite
        {
            Id = $"SITE-{Guid.NewGuid():N}",
            Name = request.Name.Trim(),
            LocationDescription = request.LocationDescription?.Trim(),
            Latitude = request.Latitude,
            Longitude = request.Longitude
        };
        if (site.Name.Length < 2) throw new AccessProblem(400, "A site name is required.");
        await store.WriteAsync($"{Root}/sites/{site.Id}", site, ct: ct);
        return site;
    }

    public async Task<AccessAccount> ApproveAsync(CurrentAccess caller, string uid, ApprovalRequest request, CancellationToken ct)
    {
        if (caller.Account.Role != AccessRoles.Administrator || caller.Account.Status != "approved") throw new AccessProblem(403, "Administrator access is required.");
        if (request.Role is not (AccessRoles.Patient or AccessRoles.Nurse or AccessRoles.Doctor) || request.Status is not ("approved" or "suspended"))
            throw new AccessProblem(400, "Invalid approval settings.");
        var path = $"{Root}/accounts/{Key(uid)}";
        var stored = await store.ReadAsync<AccessAccount>(path, ct);
        var account = stored.Value ?? throw new AccessProblem(404, "The user must sign in before approval.");
        if (account.Role == AccessRoles.Administrator) throw new AccessProblem(400, "Administrator access is managed through server configuration.");
        if (account.Version != request.ExpectedVersion) throw new AccessProblem(409, "Refresh the account before changing its approval.");
        var sites = request.SiteIds.Distinct(StringComparer.Ordinal).ToList();
        if (request.Status == "approved" && request.Role == AccessRoles.Nurse && sites.Count != 1)
            throw new AccessProblem(400, "An approved nurse must belong to exactly one site.");
        if (request.Status == "approved" && request.Role == AccessRoles.Doctor && sites.Count == 0)
            throw new AccessProblem(400, "An approved doctor requires at least one site.");
        if (request.Role == AccessRoles.Patient && sites.Count != 0) throw new AccessProblem(400, "Patient sites are assigned through clinical registration.");
        foreach (var siteId in sites) await RequireSiteAsync(siteId, ct);
        account.Role = request.Role; account.Status = request.Status; account.SiteIds = sites;
        if (request.Status == "approved") account.RequestedRole = null;
        account.ApprovedByUid = caller.User.Uid; account.Version++;
        await store.WriteAsync(path, account, stored.ETag, ct);
        return account;
    }

    public async Task<ClinicSite> RequireSiteAsync(string id, CancellationToken ct)
    {
        var site = (await store.ReadAsync<ClinicSite>($"{Root}/sites/{Key(id)}", ct)).Value;
        return site?.Active == true ? site : throw new AccessProblem(400, "An active site is required.");
    }

    public async Task<AccessAccount> RequireDoctorAsync(string uid, string siteId, CancellationToken ct)
    {
        var doctor = (await store.ReadAsync<AccessAccount>($"{Root}/accounts/{Key(uid)}", ct)).Value;
        if (doctor?.Role != AccessRoles.Doctor || doctor.Status != "approved" || !doctor.SiteIds.Contains(siteId))
            throw new AccessProblem(400, "The selected doctor must be approved at the patient's site.");
        return doctor;
    }

    public async Task<ClinicalPatient> RegisterPatientAsync(CurrentAccess caller, PatientRegistrationRequest request, CancellationToken ct)
    {
        RequireRegistrationSite(caller.Account, request.SiteId);
        await RequireSiteAsync(request.SiteId, ct);
        await RequireDoctorAsync(request.PrimaryDoctorUid, request.SiteId, ct);
        if (request.DateOfBirth > DateOnly.FromDateTime(DateTime.UtcNow)) throw new AccessProblem(400, "Date of birth cannot be in the future.");
        var patient = new ClinicalPatient
        {
            Id = $"PAT-{(request.RegistrationId ?? Guid.NewGuid()):N}",
            FullName = request.FullName.Trim(),
            DateOfBirth = request.DateOfBirth,
            SiteId = request.SiteId,
            PrimaryDoctorUid = request.PrimaryDoctorUid,
            RegisteredByUid = caller.User.Uid,
            RegisteredAtUtc = DateTimeOffset.UtcNow,
            Version = 1
        };
        if (patient.FullName.Length < 2) throw new AccessProblem(400, "A patient name is required.");
        var path = $"{Root}/patients/{patient.Id}";
        var existing = await store.ReadAsync<ClinicalPatient>(path, ct);
        if (existing.Value is { } previous) {
            if (previous.SiteId != patient.SiteId || previous.RegisteredByUid != patient.RegisteredByUid || previous.FullName != patient.FullName ||
                previous.DateOfBirth != patient.DateOfBirth || previous.PrimaryDoctorUid != patient.PrimaryDoctorUid)
                throw new AccessProblem(409, "This registration identifier is already bound to different patient details.");
            return previous;
        }
        await store.WriteAsync(path, patient, existing.ETag, ct);
        return patient;
    }

    public async Task<ClinicalPatient> AssignPatientAsync(string id, PatientAssignmentRequest request, CancellationToken ct)
    {
        var path = $"{Root}/patients/{Key(id)}";
        var stored = await store.ReadAsync<ClinicalPatient>(path, ct);
        var patient = stored.Value ?? throw new AccessProblem(404, "Patient not found.");
        if (patient.Version != request.ExpectedVersion) throw new AccessProblem(409, "Refresh the patient before changing the assignment.");
        await RequireDoctorAsync(request.PrimaryDoctorUid, patient.SiteId, ct);
        var additional = request.AdditionalDoctorUids.Distinct(StringComparer.Ordinal).Where(x => x != request.PrimaryDoctorUid).ToList();
        foreach (var uid in additional) await RequireDoctorAsync(uid, patient.SiteId, ct);
        patient.PrimaryDoctorUid = request.PrimaryDoctorUid; patient.AdditionalDoctorUids = additional; patient.Version++;
        await store.WriteAsync(path, patient, stored.ETag, ct);
        return patient;
    }

    public static void RequireRegistrationSite(AccessAccount account, string siteId)
    {
        if (account.Status != "approved" || !(account.Role == AccessRoles.Administrator ||
            account.Role == AccessRoles.Nurse && account.SiteIds.Count == 1 && account.SiteIds[0] == siteId))
            throw new AccessProblem(403, "Patient registration is restricted to authorised staff at this site.");
    }

    public static bool CanReadClinicalPatient(AccessAccount account, ClinicalPatient patient) =>
        account.Status == "approved" && account.SiteIds.Contains(patient.SiteId) &&
        (account.Role == AccessRoles.Nurse && account.SiteIds.Count == 1 ||
         account.Role == AccessRoles.Doctor && (patient.PrimaryDoctorUid == account.Uid || patient.AdditionalDoctorUids.Contains(account.Uid)));

    public Task<StoredValue<ClinicalPatient>> ReadPatientAsync(string id, CancellationToken ct = default) => store.ReadAsync<ClinicalPatient>($"{Root}/patients/{Key(id)}", ct);
    public Task<StoredValue<SiteDevice>> ReadDeviceAsync(string id, CancellationToken ct = default) => store.ReadAsync<SiteDevice>($"{Root}/devices/{Key(id)}", ct);

    public async Task<SiteDevice> AssignDeviceAsync(string id, DeviceSiteRequest request, CancellationToken ct)
    {
        await RequireSiteAsync(request.SiteId, ct);
        var device = new SiteDevice { DeviceId = Key(id), SiteId = request.SiteId, Active = request.Active };
        await store.WriteAsync($"{Root}/devices/{device.DeviceId}", device, ct: ct);
        return device;
    }
}
