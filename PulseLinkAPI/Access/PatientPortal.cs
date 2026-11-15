using System.ComponentModel.DataAnnotations;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Access;

public sealed class PatientLoginRequest
{
    [Required, StringLength(128)] 
    public string AccountUid { get; set; } = "";
    
    [Required, EmailAddress] 
    public string ConfirmedEmail { get; set; } = "";
    
    [Range(1, int.MaxValue)] 
    public int ExpectedVersion { get; set; }
}

public sealed record PatientLoginLink(string PatientId, string AccountUid, string ConfirmedByUid, DateTimeOffset ConfirmedAtUtc);

public sealed record PatientProfile(string PatientId, string FullName);

public sealed record PatientSessionStatus(string SessionId, string PatientId, DateTime StartTimeUtc,
    DateTime? EndTimeUtc, string Status, int DurationSeconds, bool ResultsReleased, bool HasDoctorNotes, bool ReportAvailable = false);

public sealed class PatientRelease
{
    public string SessionId { get; set; } = "";
    public string PatientId { get; set; } = "";
    public string ReleasedByUid { get; set; } = "";
    public DateTimeOffset ReleasedAtUtc { get; set; }
    public List<DoctorNoteDto> Notes { get; set; } = [];
    public CarePlan CarePlan { get; set; } = new();
    public string ReportState { get; set; } = "Unavailable";
    public bool ReportAvailable { get; set; }
    public string ReportFileName { get; set; } = "";
}

// The two matching links are required before a login can see any clinical content.
// A failed conditional write leaves a reservation that the same request can safely retry.
public sealed class PatientPortal(AccessDirectory directory, IAccessStore store, IEcgDataService ecg, IBlobStorageService blobs)
{
    private static string Key(string id)
    {
        FirebaseAccessStore.ValidatePath(id);
        if (id.Contains('/')) throw new AccessProblem(400, "Invalid identifier.");
        return id;
    }

    public async Task<PatientLoginLink> LinkAsync(CurrentAccess caller, string patientId, PatientLoginRequest request, CancellationToken ct)
    {
        var path = $"{AccessDirectory.Root}/patients/{Key(patientId)}";
        var row = await directory.ReadPatientAsync(patientId, ct);
        var patient = row.Value ?? throw new AccessProblem(404, "Patient record not found.");
        AccessDirectory.RequireRegistrationSite(caller.Account, patient.SiteId);
        await directory.RequireSiteAsync(patient.SiteId, ct);
        var account = (await store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/{Key(request.AccountUid)}", ct)).Value;
        if (account is not { Role: AccessRoles.Patient, Status: "approved", EmailVerified: true } ||
            !string.Equals(account.Email, request.ConfirmedEmail.Trim(), StringComparison.OrdinalIgnoreCase))
            throw new AccessProblem(400, "Confirm the approved patient's verified sign-in account and email address.");
        if (patient.LinkedAccountUid != null && patient.LinkedAccountUid != account.Uid)
            throw new AccessProblem(409, "This patient record already has a login. Existing links cannot be replaced here.");
        var linkPath = $"{AccessDirectory.Root}/patientLogins/{Key(account.Uid)}";
        var existing = await store.ReadAsync<PatientLoginLink>(linkPath, ct);
        if (existing.Value is { } other && other.PatientId != patient.Id)
            throw new AccessProblem(409, "This login is already linked to another patient record.");
        if (patient.LinkedAccountUid == account.Uid && existing.Value != null) return existing.Value;
        if (patient.Version != request.ExpectedVersion) throw new AccessProblem(409, "Refresh the patient record before linking its login.");
        var link = existing.Value ?? new PatientLoginLink(patient.Id, account.Uid, caller.User.Uid, DateTimeOffset.UtcNow);
        if (existing.Value == null) await store.WriteAsync(linkPath, link, existing.ETag, ct);
        patient.LinkedAccountUid = account.Uid;
        patient.Version++;
        await store.WriteAsync(path, patient, row.ETag, ct);
        return link;
    }

    public async Task<ClinicalPatient?> OwnPatientAsync(CurrentAccess caller, CancellationToken ct)
    {
        if (caller.Account is not { Role: AccessRoles.Patient, Status: "approved" } || !caller.User.EmailVerified)
            throw new AccessProblem(403, "An approved, verified patient sign-in is required.");
        var link = (await store.ReadAsync<PatientLoginLink>($"{AccessDirectory.Root}/patientLogins/{Key(caller.User.Uid)}", ct)).Value;
        if (link == null) return null;
        var patient = (await directory.ReadPatientAsync(link.PatientId, ct)).Value;
        if (link.AccountUid != caller.User.Uid || patient?.LinkedAccountUid != caller.User.Uid)
            throw new AccessProblem(409, "The patient login link needs staff review.");
        await directory.RequireSiteAsync(patient.SiteId, ct);
        return patient;
    }

    public async Task<AccessPage<PatientSessionStatus>> SessionsAsync(CurrentAccess caller, string? after, int limit, CancellationToken ct)
    {
        var patient = await OwnPatientAsync(caller, ct);
        if (patient == null) return new([], null);
        var page = await ecg.GetPatientSessionPageAsync(patient.Id, after, limit);
        var result = new List<PatientSessionStatus>();
        foreach (var item in page.Items)
        {
            var release = item.Status == "Completed" ? await ReadRelease(item.SessionId, ct) : null;
            var released = release?.PatientId == patient.Id;
            var reportFileName = EcgDataService.ReleasedPatientReportFileName(item.SessionId);
            var reportReady = released &&
                              release!.ReportAvailable &&
                              release.ReportState == "Ready" &&
                              await blobs.ReportBlobExistsAsync(reportFileName);
            result.Add(new(item.SessionId, patient.Id, item.StartTimeUtc, item.EndTimeUtc, item.Status, item.DurationSeconds,
                released, released && release!.Notes.Count > 0, reportReady));
        }
        return new(result, page.NextCursor);
    }

    public async Task<PatientRelease> ReleaseAsync(CurrentAccess caller, string id, CancellationToken ct)
    {
        var session = await ecg.GetSessionAccessContextAsync(id) ?? throw new AccessProblem(404, "Session not found.");
        var patient = (await directory.ReadPatientAsync(session.PatientId, ct)).Value;
        if (caller.Account.Role != AccessRoles.Doctor || patient == null || !AccessDirectory.CanReadClinicalPatient(caller.Account, patient))
            throw new AccessProblem(403, "Only an assigned doctor may release these results.");
        await directory.RequireSiteAsync(patient.SiteId, ct);
        if (session.Status != "Completed") throw new AccessProblem(409, "Only a completed recording can be released.");
        var notes = await ecg.GetDoctorNotesAsync(id);
        if (notes.Count == 0) throw new AccessProblem(409, "Add a clinical assessment before releasing the results.");

        var liveCarePlan = (await store.ReadAsync<CarePlan>($"clinicalCarePlans/{Key(patient.Id)}", ct)).Value ?? new();
        var snapshotMedications = liveCarePlan.Medications
            .Select(m => new MedicationEntry { Name = m.Name, Dosage = m.Dosage, Frequency = m.Frequency })
            .ToList();
        foreach (var rx in notes.SelectMany(n => n.Prescriptions ?? []))
        {
            if (!string.IsNullOrWhiteSpace(rx.Name) &&
                !snapshotMedications.Any(m => string.Equals(m.Name, rx.Name, StringComparison.OrdinalIgnoreCase) &&
                                              string.Equals(m.Dosage, rx.Dosage, StringComparison.OrdinalIgnoreCase)))
            {
                snapshotMedications.Add(new MedicationEntry
                {
                    Name = rx.Name,
                    Dosage = rx.Dosage,
                    Frequency = rx.Frequency
                });
            }
        }

        var carePlan = new CarePlan
        {
            PrimaryCondition = liveCarePlan.PrimaryCondition,
            SecondaryStatus = liveCarePlan.SecondaryStatus,
            Medications = snapshotMedications,
            ClinicalInstructions = liveCarePlan.ClinicalInstructions,
            UpdatedAtUtc = string.IsNullOrWhiteSpace(liveCarePlan.UpdatedAtUtc)
                ? DateTimeOffset.UtcNow.ToString("O")
                : liveCarePlan.UpdatedAtUtc,
            UpdatedByDoctor = string.IsNullOrWhiteSpace(liveCarePlan.UpdatedByDoctor)
                ? caller.User.Uid
                : liveCarePlan.UpdatedByDoctor
        };

        var releasedAt = DateTimeOffset.UtcNow;
        var reportFileName = EcgDataService.ReleasedPatientReportFileName(id);
        var reportReady = false;
        var reportState = "Failed";

        try
        {
            var pdfBytes = await ecg.GenerateReleasedPatientPdfAsync(id, notes, carePlan.ClinicalInstructions, releasedAt);
            if (pdfBytes.Length > 0 && await blobs.ReportBlobExistsAsync(reportFileName))
            {
                reportReady = true;
                reportState = "Ready";
            }
        }
        catch (AccessProblem)
        {
            reportReady = false;
            reportState = "Failed";
        }

        var path = $"patientReleases/{Key(id)}";
        var previous = await store.ReadAsync<PatientRelease>(path, ct);
        var release = new PatientRelease
        {
            SessionId = id,
            PatientId = patient.Id,
            ReleasedByUid = caller.User.Uid,
            ReleasedAtUtc = releasedAt,
            Notes = notes,
            CarePlan = carePlan,
            ReportState = reportState,
            ReportAvailable = reportReady,
            ReportFileName = reportFileName
        };
        await store.WriteAsync(path, release, previous.ETag, ct);

        var doctorName = notes
            .OrderByDescending(n => n.CreatedAtUtc)
            .Select(n => n.DoctorName)
            .FirstOrDefault(n => !string.IsNullOrWhiteSpace(n))
            ?? (string.IsNullOrWhiteSpace(caller.Account.DisplayName) ? null : caller.Account.DisplayName);
        var prescriptionSummary = snapshotMedications.Count == 0
            ? null
            : string.Join(", ", snapshotMedications.Select(m =>
                string.Join(" ", new[] { m.Name, m.Dosage, m.Frequency }
                    .Where(s => !string.IsNullOrWhiteSpace(s))
                    .Select(s => s!.Trim()))));
        var adviceParts = notes
            .SelectMany(n => new[] { n.Recommendations, n.FollowUpInstructions })
            .Append(liveCarePlan.ClinicalInstructions)
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Select(s => s!.Trim())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var clinicalAdvice = adviceParts.Count == 0 ? null : string.Join(" ", adviceParts);

        var revision = ClinicalNotifications.Hash(System.Text.Json.JsonSerializer.Serialize(new { release.Notes, CarePlan = liveCarePlan }));
        var notifications = new ClinicalNotifications(store);
        await notifications.EnqueueAsync(
            patient.Id,
            id,
            "RESULTS_RELEASED",
            revision,
            doctorName,
            patient.FullName,
            prescriptionSummary,
            clinicalAdvice,
            ct);
        if (!string.IsNullOrWhiteSpace(prescriptionSummary))
        {
            var prescriptionRevision = ClinicalNotifications.Hash(prescriptionSummary);
            await notifications.EnqueueAsync(
                patient.Id,
                id,
                "PRESCRIPTION_ISSUED",
                prescriptionRevision,
                doctorName,
                patient.FullName,
                prescriptionSummary,
                clinicalAdvice,
                ct);
        }
        if (!string.IsNullOrWhiteSpace(clinicalAdvice))
        {
            var adviceRevision = ClinicalNotifications.Hash(clinicalAdvice);
            await notifications.EnqueueAsync(
                patient.Id,
                id,
                "CLINICAL_ADVICE",
                adviceRevision,
                doctorName,
                patient.FullName,
                prescriptionSummary,
                clinicalAdvice,
                ct);
        }
        return release;
    }

    public async Task<PatientRelease> ReleasedAsync(CurrentAccess caller, string id, CancellationToken ct)
    {
        var patient = await OwnPatientAsync(caller, ct) ?? throw new AccessProblem(403, "Your clinical record has not been linked yet.");
        var session = await ecg.GetSessionAccessContextAsync(id);
        if (session?.PatientId != patient.Id) throw new AccessProblem(404, "Released result not found.");
        var release = await ReadRelease(id, ct);
        if (session.Status != "Completed" || release?.PatientId != patient.Id)
            throw new AccessProblem(404, "Released result not found.");

        var fileName = EcgDataService.ReleasedPatientReportFileName(id);
        release.ReportFileName = fileName;
        release.ReportAvailable = release.ReportAvailable &&
                                  release.ReportState == "Ready" &&
                                  await blobs.ReportBlobExistsAsync(fileName);
        if (!release.ReportAvailable && release.ReportState == "Ready")
        {
            release.ReportState = "Unavailable";
        }
        return release;
    }

    public async Task<ReportStatusDto> ReleasedReportStatusAsync(CurrentAccess caller, string id, CancellationToken ct)
    {
        var patient = await OwnPatientAsync(caller, ct) ?? throw new AccessProblem(403, "Your clinical record has not been linked yet.");
        var session = await ecg.GetSessionAccessContextAsync(id);
        if (session?.PatientId != patient.Id) throw new AccessProblem(404, "Released result not found.");

        var fileName = EcgDataService.ReleasedPatientReportFileName(id);
        var statusPath = $"/api/patient/sessions/{Key(id)}/report";
        var streamPath = $"/api/patient/sessions/{Key(id)}/report/pdf";
        var release = session.Status == "Completed" ? await ReadRelease(id, ct) : null;
        if (release == null || release.PatientId != patient.Id)
        {
            return new ReportStatusDto(
                SessionId: id,
                PatientId: patient.Id,
                Audience: "patient",
                State: "Unavailable",
                ReportAvailable: false,
                FileName: "",
                DownloadPath: "",
                StreamPath: "",
                AuthenticatedEmailLinkPath: statusPath,
                GeneratedAtUtc: null,
                Revision: null,
                Message: "Clinical results and the ECG PDF report have not been released yet.");
        }

        var blobExists = await blobs.ReportBlobExistsAsync(fileName);
        var isReady = release.ReportAvailable && release.ReportState == "Ready" && blobExists;
        var state = isReady
            ? "Ready"
            : release.ReportState == "Failed"
                ? "Failed"
                : "Unavailable";

        return new ReportStatusDto(
            SessionId: release.SessionId,
            PatientId: release.PatientId,
            Audience: "patient",
            State: state,
            ReportAvailable: isReady,
            FileName: fileName,
            DownloadPath: streamPath,
            StreamPath: streamPath,
            AuthenticatedEmailLinkPath: statusPath,
            GeneratedAtUtc: release.ReleasedAtUtc,
            Revision: null,
            Message: isReady
                ? null
                : "The released PDF report is not currently available in private storage.");
    }

    public async Task<(byte[] PdfBytes, string FileName)> DownloadReleasedReportPdfAsync(CurrentAccess caller, string id, CancellationToken ct)
    {
        var release = await ReleasedAsync(caller, id, ct);
        var fileName = EcgDataService.ReleasedPatientReportFileName(id);

        if (release.ReportAvailable)
        {
            var existing = await blobs.DownloadReportBlobAsync(fileName);
            if (existing is { Length: > 0 })
            {
                return (existing, fileName);
            }
        }

        // Recover strictly from the immutable release snapshot (never from live draft notes or live care plans).
        var regenerated = await ecg.GenerateReleasedPatientPdfAsync(
            id,
            release.Notes,
            release.CarePlan.ClinicalInstructions,
            release.ReleasedAtUtc);

        if (regenerated.Length == 0 || !await blobs.ReportBlobExistsAsync(fileName))
        {
            throw new AccessProblem(503, "Released PDF report is temporarily unavailable.");
        }

        var path = $"patientReleases/{Key(id)}";
        var current = await store.ReadAsync<PatientRelease>(path, ct);
        if (current.Value != null && current.Value.PatientId == release.PatientId)
        {
            current.Value.ReportAvailable = true;
            current.Value.ReportState = "Ready";
            current.Value.ReportFileName = fileName;
            await store.WriteAsync(path, current.Value, current.ETag, ct);
        }

        return (regenerated, fileName);
    }

    private async Task<PatientRelease?> ReadRelease(string id, CancellationToken ct) =>
        (await store.ReadAsync<PatientRelease>($"patientReleases/{Key(id)}", ct)).Value;
}
