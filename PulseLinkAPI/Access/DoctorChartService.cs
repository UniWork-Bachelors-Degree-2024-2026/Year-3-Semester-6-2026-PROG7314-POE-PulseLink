using System.ComponentModel.DataAnnotations;
using PulseLinkAPI.Models.Domain;
using PulseLinkAPI.Models.DTOs;
using PulseLinkAPI.Services.Implementations;
using PulseLinkAPI.Services.Interfaces;

namespace PulseLinkAPI.Access;

public sealed class DoctorChartService(
    AccessDirectory directory,
    IAccessStore store,
    IEcgDataService ecg,
    IBlobStorageService blobs)
{
    public const string VisitsRoot = "clinicalVisitsV2";

    private static string Key(string id)
    {
        FirebaseAccessStore.ValidatePath(id);
        if (id.Contains('/')) throw new AccessProblem(400, "Invalid identifier.");
        return id;
    }

    public static string ResolveDoctorName(CurrentAccess caller) =>
        string.IsNullOrWhiteSpace(caller.Account.DisplayName)
            ? caller.User.DisplayName
            : caller.Account.DisplayName;

    public static void RequireClinicalStaff(CurrentAccess? caller)
    {
        if (caller == null ||
            caller.Account.Status != "approved" ||
            !AccessRoles.IsStaff(caller.Account.Role) ||
            caller.Account.Role == AccessRoles.Nurse && caller.Account.SiteIds.Count != 1)
        {
            throw new AccessProblem(403, "Clinical review access is restricted to approved doctors and nurses.");
        }
    }

    public static void RequireDoctor(CurrentAccess? caller)
    {
        RequireClinicalStaff(caller);
        if (caller!.Account.Role != AccessRoles.Doctor)
        {
            throw new AccessProblem(403, "This clinical action is restricted to an assigned doctor.");
        }
    }

    public async Task<(ClinicalPatient Patient, ClinicSite Site)> RequireAssignedPatientAsync(
        CurrentAccess caller,
        string patientId,
        CancellationToken ct)
    {
        RequireClinicalStaff(caller);
        var patient = (await directory.ReadPatientAsync(patientId, ct)).Value;
        if (patient == null || !AccessDirectory.CanReadClinicalPatient(caller.Account, patient))
        {
            throw new AccessProblem(403, "This patient is not assigned to your care or site.");
        }

        var site = await directory.RequireSiteAsync(patient.SiteId, ct);
        return (patient, site);
    }

    public async Task<AccessPage<DoctorPatientSummaryDto>> ListAssignedPatientsAsync(
        CurrentAccess caller,
        string? after,
        int limit,
        CancellationToken ct)
    {
        RequireClinicalStaff(caller);
        if (after != null) Key(after);
        var boundedLimit = Math.Clamp(limit, 1, 100);

        var activeSites = await LoadActiveSitesMapAsync(caller.Account, ct);
        var activeAlerts = await ecg.GetActiveAlertsAsync();
        var alertsByPatient = activeAlerts
            .GroupBy(a => a.PatientId, StringComparer.Ordinal)
            .ToDictionary(g => g.Key, g => g.ToList(), StringComparer.Ordinal);

        var matched = new List<DoctorPatientSummaryDto>();
        var cursor = after;
        string? nextCursor = null;

        while (matched.Count < boundedLimit)
        {
            var page = await store.ListAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients", cursor, 100, ct);
            if (page.Items.Count == 0)
            {
                nextCursor = null;
                break;
            }

            for (var i = 0; i < page.Items.Count; i++)
            {
                var patient = page.Items[i];
                if (!AccessDirectory.CanReadClinicalPatient(caller.Account, patient) ||
                    !activeSites.TryGetValue(patient.SiteId, out var site))
                {
                    continue;
                }

                var patientAlerts = alertsByPatient.TryGetValue(patient.Id, out var list) ? list : [];
                var (summary, _) = await BuildPatientSummaryAndQueueItemsAsync(patient, site, patientAlerts, ct);
                matched.Add(summary);

                if (matched.Count == boundedLimit)
                {
                    var hasRemainingInPage = i + 1 < page.Items.Count;
                    nextCursor = hasRemainingInPage ? patient.Id : page.NextCursor;
                    break;
                }
            }

            if (matched.Count == boundedLimit)
            {
                break;
            }

            if (page.NextCursor == null)
            {
                nextCursor = null;
                break;
            }

            cursor = page.NextCursor;
        }

        return new AccessPage<DoctorPatientSummaryDto>(matched, nextCursor);
    }

    public async Task<DoctorDashboardDto> GetDashboardAsync(CurrentAccess caller, CancellationToken ct)
    {
        RequireDoctor(caller);
        var activeSites = await LoadActiveSitesMapAsync(caller.Account, ct);
        var allPatients = await LoadAllAssignedPatientsAsync(caller.Account, activeSites, ct);
        var activeAlerts = await ecg.GetActiveAlertsAsync();
        var alertsByPatient = activeAlerts
            .GroupBy(a => a.PatientId, StringComparer.Ordinal)
            .ToDictionary(g => g.Key, g => g.ToList(), StringComparer.Ordinal);

        var summaries = new List<DoctorPatientSummaryDto>(allPatients.Count);
        var queue = new List<DoctorQueueItemDto>();
        var assignedAlerts = new List<AlertDto>();

        foreach (var (patient, site) in allPatients)
        {
            var patientAlerts = alertsByPatient.TryGetValue(patient.Id, out var list) ? list : [];
            assignedAlerts.AddRange(patientAlerts);

            var (summary, patientQueueItems) = await BuildPatientSummaryAndQueueItemsAsync(patient, site, patientAlerts, ct);
            summaries.Add(summary);
            queue.AddRange(patientQueueItems);
        }

        var orderedPatients = summaries
            .OrderByDescending(p => p.AwaitingReviewCount + p.DraftAssessedCount)
            .ThenByDescending(p => p.FollowUpRequired)
            .ThenByDescending(p => p.LatestRecordingAtUtc ?? DateTime.MinValue)
            .ThenBy(p => p.FullName, StringComparer.OrdinalIgnoreCase)
            .ToList();

        var orderedQueue = queue
            .OrderBy(q => q.ReviewState switch
            {
                "AwaitingReview" => 0,
                "DraftSaved" => 1,
                "ReleasedWithDrafts" => 2,
                _ => 3
            })
            .ThenByDescending(q => q.StartTimeUtc)
            .ToList();

        return new DoctorDashboardDto
        {
            AssignedPatientsCount = orderedPatients.Count,
            CompletedSessionsCount = orderedQueue.Count,
            AwaitingReviewCount = orderedQueue.Count(q => !q.ResultsReleased && !q.HasDoctorNotes),
            DraftAssessedCount = orderedQueue.Count(q => q.HasUnreleasedDrafts),
            PendingReleaseCount = orderedQueue.Count(q => !q.ResultsReleased || q.HasUnreleasedDrafts),
            ReleasedCount = orderedQueue.Count(q => q.ResultsReleased),
            CompletedReportsReadyCount = orderedQueue.Count(q => q.ReportAvailable),
            FollowUpRequiredCount = orderedPatients.Count(p => p.FollowUpRequired),
            CriticalAlertsCount = assignedAlerts.Count(a =>
                !a.IsAcknowledged &&
                (string.Equals(a.Severity, "Critical", StringComparison.OrdinalIgnoreCase) ||
                 string.Equals(a.Severity, "Warning", StringComparison.OrdinalIgnoreCase))),
            UnacknowledgedAlertsCount = assignedAlerts.Count(a => !a.IsAcknowledged),
            AssignedPatients = orderedPatients,
            CompletedSessionQueue = orderedQueue
        };
    }

    public async Task<PatientChartDto> GetPatientChartAsync(
        CurrentAccess caller,
        string patientId,
        CancellationToken ct)
    {
        var (patient, site) = await RequireAssignedPatientAsync(caller, patientId, ct);
        var carePlan = (await store.ReadAsync<CarePlan>($"clinicalCarePlans/{Key(patient.Id)}", ct)).Value ?? new CarePlan();
        var visits = await GetPatientVisitsInternalAsync(patient.Id, ct);
        var activeAlerts = (await ecg.GetActiveAlertsAsync())
            .Where(a => a.PatientId == patient.Id)
            .OrderByDescending(a => a.TimestampUtc)
            .ToList();

        var (summary, _, completedReviews, history) = await BuildPatientClinicalDetailsAsync(
            patient, site, carePlan, visits, activeAlerts, ct);

        return new PatientChartDto
        {
            Patient = summary,
            CarePlan = carePlan,
            PriorVisits = visits,
            CompletedRecordings = completedReviews,
            RecordingHistory = history,
            ActiveAlerts = activeAlerts
        };
    }

    public async Task<List<ClinicalVisitDto>> GetPatientVisitsAsync(
        CurrentAccess caller,
        string patientId,
        CancellationToken ct)
    {
        var (patient, _) = await RequireAssignedPatientAsync(caller, patientId, ct);
        return await GetPatientVisitsInternalAsync(patient.Id, ct);
    }

    public async Task<ClinicalVisitDto> CreatePatientVisitAsync(
        CurrentAccess caller,
        string patientId,
        CreateClinicalVisitDto request,
        CancellationToken ct)
    {
        RequireDoctor(caller);
        var (patient, _) = await RequireAssignedPatientAsync(caller, patientId, ct);

        var errors = new List<ValidationResult>();
        if (!Validator.TryValidateObject(request, new ValidationContext(request), errors, true))
        {
            throw new AccessProblem(400, errors.FirstOrDefault()?.ErrorMessage ?? "Invalid clinical visit payload.");
        }

        string? validatedSessionId = null;
        if (!string.IsNullOrWhiteSpace(request.SessionId))
        {
            var trimmedSessionId = request.SessionId.Trim();
            var sessionCtx = await ecg.GetSessionAccessContextAsync(trimmedSessionId)
                             ?? throw new AccessProblem(404, "Referenced ECG session not found.");
            if (sessionCtx.PatientId != patient.Id)
            {
                throw new AccessProblem(400, "Referenced ECG session does not belong to this patient.");
            }
            if (sessionCtx.Status == "Discarded")
            {
                throw new AccessProblem(410, "Referenced ECG session was discarded.");
            }
            validatedSessionId = trimmedSessionId;
        }

        var sanitisedPrescriptions = (request.Prescriptions ?? [])
            .Where(m => !string.IsNullOrWhiteSpace(m.Name))
            .Select(m => new MedicationEntry
            {
                Name = m.Name.Trim(),
                Dosage = m.Dosage?.Trim() ?? string.Empty,
                Frequency = m.Frequency?.Trim() ?? string.Empty
            })
            .Take(30)
            .ToList();

        var visit = new ClinicalVisitDto
        {
            VisitId = $"VISIT-{Guid.NewGuid():N}",
            PatientId = patient.Id,
            SiteId = patient.SiteId,
            SessionId = validatedSessionId,
            VisitType = request.VisitType.Trim(),
            ChiefComplaint = request.ChiefComplaint?.Trim() ?? string.Empty,
            Assessment = request.Assessment.Trim(),
            Recommendations = string.IsNullOrWhiteSpace(request.Recommendations) ? null : request.Recommendations.Trim(),
            Prescriptions = sanitisedPrescriptions,
            FollowUpRequired = request.FollowUpRequired,
            FollowUpInstructions = string.IsNullOrWhiteSpace(request.FollowUpInstructions) ? null : request.FollowUpInstructions.Trim(),
            FollowUpInDays = request.FollowUpInDays,
            DoctorId = caller.User.Uid,
            DoctorName = ResolveDoctorName(caller),
            RecordedAtUtc = DateTimeOffset.UtcNow
        };

        await store.WriteAsync($"{VisitsRoot}/{Key(patient.Id)}/{visit.VisitId}", visit, ct: ct);
        return visit;
    }

    public async Task<DoctorSessionReviewDto> GetSessionReviewAsync(
        CurrentAccess caller,
        string sessionId,
        CancellationToken ct)
    {
        RequireClinicalStaff(caller);
        var manifest = (await store.ReadAsync<RecordingManifest>($"{EcgDataService.RecordingRoot}/{Key(sessionId)}", ct)).Value
                       ?? throw new AccessProblem(404, "Session not found.");
        var (patient, site) = await RequireAssignedPatientAsync(caller, manifest.PatientId, ct);

        if (manifest.Status == "Discarded")
        {
            throw new AccessProblem(410, "This recording was discarded.");
        }
        if (manifest.Status != "Completed")
        {
            throw new AccessProblem(409, "Clinical session review requires a completed, finalised recording.");
        }

        var carePlan = (await store.ReadAsync<CarePlan>($"clinicalCarePlans/{Key(patient.Id)}", ct)).Value ?? new CarePlan();
        var notes = (await ecg.GetDoctorNotesAsync(sessionId))
            .OrderByDescending(n => n.CreatedAtUtc)
            .ToList();
        var release = (await store.ReadAsync<PatientRelease>($"patientReleases/{Key(sessionId)}", ct)).Value;
        var reportStatus = await ecg.GetStaffReportStatusAsync(sessionId, generateIfMissing: true)
                           ?? throw new AccessProblem(404, "Session report status not found.");

        var (releaseState, reviewState) = EvaluateReleaseAndReviewState(notes, carePlan, release, patient.Id);
        var sessions = await ecg.GetPatientSessionsAsync(patient.Id);
        var summary = sessions.FirstOrDefault(s => s.SessionId == sessionId) ?? new EcgSessionSummaryDto
        {
            SessionId = manifest.SessionId,
            PatientId = patient.Id,
            PatientName = patient.FullName,
            DeviceId = manifest.DeviceId,
            StartTimeUtc = manifest.StartTimeUtc,
            EndTimeUtc = manifest.EndTimeUtc,
            DurationSeconds = manifest.RecordedDurationSeconds ?? 0,
            Status = manifest.Status
        };

        summary.PatientName = patient.FullName;
        summary.HasDoctorNotes = notes.Count > 0;
        summary.ResultsReleased = releaseState.ResultsReleased;
        summary.HasUnreleasedDrafts = releaseState.HasUnreleasedDrafts;
        summary.ReportAvailable = reportStatus.ReportAvailable;

        return new DoctorSessionReviewDto
        {
            Session = summary,
            PatientId = patient.Id,
            PatientName = patient.FullName,
            DateOfBirth = patient.DateOfBirth,
            SiteId = site.Id,
            SiteName = site.Name,
            OperatorUid = manifest.OperatorUid,
            StaffReportStatus = reportStatus,
            DraftNotes = notes,
            DraftCarePlan = carePlan,
            ReleaseState = releaseState,
            ReviewState = reviewState
        };
    }

    public static (SessionReleaseStateDto ReleaseState, string ReviewState) EvaluateReleaseAndReviewState(
        IReadOnlyList<DoctorNoteDto> draftNotes,
        CarePlan draftCarePlan,
        PatientRelease? release,
        string patientId)
    {
        var isReleased = release != null && release.PatientId == patientId;
        var releasedNoteCount = isReleased ? release!.Notes.Count : 0;
        var carePlanModifiedSinceRelease = isReleased &&
                                           DateTimeOffset.TryParse(draftCarePlan.UpdatedAtUtc, out var carePlanUpdatedAt) &&
                                           carePlanUpdatedAt > release!.ReleasedAtUtc;
        var notesAddedSinceRelease = isReleased &&
                                     (draftNotes.Count > releasedNoteCount ||
                                      draftNotes.Any(n => new DateTimeOffset(DateTime.SpecifyKind(n.CreatedAtUtc, DateTimeKind.Utc)) > release!.ReleasedAtUtc));

        var hasUnreleasedDrafts = isReleased
            ? notesAddedSinceRelease || carePlanModifiedSinceRelease
            : draftNotes.Count > 0;

        var reviewState = !isReleased
            ? draftNotes.Count > 0 ? "DraftSaved" : "AwaitingReview"
            : hasUnreleasedDrafts ? "ReleasedWithDrafts" : "Released";

        var state = new SessionReleaseStateDto
        {
            ResultsReleased = isReleased,
            ReleasedAtUtc = isReleased ? release!.ReleasedAtUtc : null,
            ReleasedByUid = isReleased ? release!.ReleasedByUid : null,
            ReleasedNoteCount = releasedNoteCount,
            DraftNoteCount = draftNotes.Count,
            HasUnreleasedDrafts = hasUnreleasedDrafts,
            ReportState = isReleased ? release!.ReportState : "Unavailable",
            ReportAvailable = isReleased && release!.ReportAvailable
        };

        return (state, reviewState);
    }

    private async Task<List<ClinicalVisitDto>> GetPatientVisitsInternalAsync(string patientId, CancellationToken ct)
    {
        var page = await store.ListAsync<ClinicalVisitDto>($"{VisitsRoot}/{Key(patientId)}", limit: 100, ct: ct);
        return page.Items
            .OrderByDescending(v => v.RecordedAtUtc)
            .ToList();
    }

    private async Task<Dictionary<string, ClinicSite>> LoadActiveSitesMapAsync(
        AccessAccount account,
        CancellationToken ct)
    {
        var map = new Dictionary<string, ClinicSite>(StringComparer.Ordinal);
        foreach (var siteId in account.SiteIds.Distinct(StringComparer.Ordinal).Take(50))
        {
            var site = (await store.ReadAsync<ClinicSite>($"{AccessDirectory.Root}/sites/{Key(siteId)}", ct)).Value;
            if (site?.Active == true)
            {
                map[site.Id] = site;
            }
        }
        return map;
    }

    private async Task<List<(ClinicalPatient Patient, ClinicSite Site)>> LoadAllAssignedPatientsAsync(
        AccessAccount account,
        Dictionary<string, ClinicSite> activeSites,
        CancellationToken ct)
    {
        var result = new List<(ClinicalPatient, ClinicSite)>();
        string? cursor = null;

        while (true)
        {
            var page = await store.ListAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients", cursor, 100, ct);
            if (page.Items.Count == 0) break;

            foreach (var patient in page.Items)
            {
                if (AccessDirectory.CanReadClinicalPatient(account, patient) &&
                    activeSites.TryGetValue(patient.SiteId, out var site))
                {
                    result.Add((patient, site));
                }
            }

            if (page.NextCursor == null) break;
            cursor = page.NextCursor;
        }

        return result;
    }

    private async Task<(DoctorPatientSummaryDto Summary, List<DoctorQueueItemDto> QueueItems)> BuildPatientSummaryAndQueueItemsAsync(
        ClinicalPatient patient,
        ClinicSite site,
        List<AlertDto> patientAlerts,
        CancellationToken ct)
    {
        var carePlan = (await store.ReadAsync<CarePlan>($"clinicalCarePlans/{Key(patient.Id)}", ct)).Value ?? new CarePlan();
        var visits = await GetPatientVisitsInternalAsync(patient.Id, ct);
        var (summary, queueItems, _, _) = await BuildPatientClinicalDetailsAsync(patient, site, carePlan, visits, patientAlerts, ct);
        return (summary, queueItems);
    }

    private async Task<(
        DoctorPatientSummaryDto Summary,
        List<DoctorQueueItemDto> QueueItems,
        List<DoctorSessionReviewSummaryDto> CompletedReviews,
        List<EcgSessionSummaryDto> RecordingHistory)> BuildPatientClinicalDetailsAsync(
        ClinicalPatient patient,
        ClinicSite site,
        CarePlan carePlan,
        List<ClinicalVisitDto> visits,
        List<AlertDto> patientAlerts,
        CancellationToken ct)
    {
        var sessions = await ecg.GetPatientSessionsAsync(patient.Id);
        var queueItems = new List<DoctorQueueItemDto>();
        var completedReviews = new List<DoctorSessionReviewSummaryDto>();

        var followUpFromNotes = false;
        string? followUpInstructions = null;
        int? followUpInDays = null;

        foreach (var session in sessions)
        {
            session.PatientName = patient.FullName;
            if (session.Status != "Completed")
            {
                continue;
            }

            var manifest = (await store.ReadAsync<RecordingManifest>($"{EcgDataService.RecordingRoot}/{Key(session.SessionId)}", ct)).Value;
            var notes = (await ecg.GetDoctorNotesAsync(session.SessionId))
                .OrderByDescending(n => n.CreatedAtUtc)
                .ToList();
            var release = (await store.ReadAsync<PatientRelease>($"patientReleases/{Key(session.SessionId)}", ct)).Value;
            var reportStatus = await ecg.GetStaffReportStatusAsync(session.SessionId, generateIfMissing: false);

            var (releaseState, reviewState) = EvaluateReleaseAndReviewState(notes, carePlan, release, patient.Id);
            if (releaseState.ResultsReleased && release != null)
            {
                var releasedReportExists = release.ReportAvailable &&
                                           release.ReportState == "Ready" &&
                                           await blobs.ReportBlobExistsAsync(EcgDataService.ReleasedPatientReportFileName(session.SessionId));
                releaseState.ReportAvailable = releasedReportExists;
                if (!releasedReportExists && releaseState.ReportState == "Ready")
                {
                    releaseState.ReportState = "Unavailable";
                }
            }

            session.HasDoctorNotes = notes.Count > 0;
            session.ResultsReleased = releaseState.ResultsReleased;
            session.HasUnreleasedDrafts = releaseState.HasUnreleasedDrafts;
            session.ReportAvailable = reportStatus?.ReportAvailable ?? session.ReportAvailable;

            var latestFollowUpNote = notes.FirstOrDefault(n => n.FollowUpRequired);
            if (latestFollowUpNote != null && !followUpFromNotes)
            {
                followUpFromNotes = true;
                followUpInstructions = latestFollowUpNote.FollowUpInstructions ?? latestFollowUpNote.Recommendations;
                followUpInDays = latestFollowUpNote.FollowUpInDays;
            }

            queueItems.Add(new DoctorQueueItemDto
            {
                SessionId = session.SessionId,
                PatientId = patient.Id,
                PatientName = patient.FullName,
                SiteId = site.Id,
                SiteName = site.Name,
                OperatorUid = manifest?.OperatorUid,
                StartTimeUtc = session.StartTimeUtc,
                EndTimeUtc = session.EndTimeUtc,
                DurationSeconds = session.DurationSeconds,
                AverageBpm = session.AverageBpm,
                SignalQuality = session.SignalQuality,
                HasDoctorNotes = session.HasDoctorNotes,
                ResultsReleased = session.ResultsReleased,
                HasUnreleasedDrafts = session.HasUnreleasedDrafts,
                ReportAvailable = session.ReportAvailable,
                FollowUpRequired = latestFollowUpNote != null,
                ReviewState = reviewState
            });

            completedReviews.Add(new DoctorSessionReviewSummaryDto
            {
                Session = session,
                SiteId = manifest?.SiteId ?? site.Id,
                OperatorUid = manifest?.OperatorUid,
                StaffReportStatus = reportStatus,
                Notes = notes,
                ReleaseState = releaseState,
                ReviewState = reviewState
            });
        }

        var latestVisitWithFollowUp = visits.FirstOrDefault(v => v.FollowUpRequired);
        var followUpRequired = followUpFromNotes || latestVisitWithFollowUp != null;
        if (!followUpFromNotes && latestVisitWithFollowUp != null)
        {
            followUpInstructions = latestVisitWithFollowUp.FollowUpInstructions ?? latestVisitWithFollowUp.Recommendations;
            followUpInDays = latestVisitWithFollowUp.FollowUpInDays;
        }

        var latestCompleted = queueItems.OrderByDescending(q => q.StartTimeUtc).FirstOrDefault();
        var awaitingCount = queueItems.Count(q => !q.ResultsReleased && !q.HasDoctorNotes);
        var draftCount = queueItems.Count(q => q.HasUnreleasedDrafts);
        var releasedCount = queueItems.Count(q => q.ResultsReleased);
        var reportReadyCount = queueItems.Count(q => q.ReportAvailable);

        var overallReviewStatus = queueItems.Count == 0
            ? "NoCompletedRecordings"
            : awaitingCount > 0
                ? "AwaitingReview"
                : draftCount > 0
                    ? "DraftSaved"
                    : "Released";

        var summary = new DoctorPatientSummaryDto
        {
            PatientId = patient.Id,
            FullName = patient.FullName,
            DateOfBirth = patient.DateOfBirth,
            SiteId = site.Id,
            SiteName = site.Name,
            PrimaryDoctorUid = patient.PrimaryDoctorUid,
            AdditionalDoctorUids = patient.AdditionalDoctorUids.ToList(),
            HasLinkedLogin = !string.IsNullOrWhiteSpace(patient.LinkedAccountUid),
            Version = patient.Version,
            CompletedSessionsCount = queueItems.Count,
            AwaitingReviewCount = awaitingCount,
            DraftAssessedCount = draftCount,
            ReleasedSessionsCount = releasedCount,
            ReportReadyCount = reportReadyCount,
            PriorVisitsCount = visits.Count,
            ActiveAlertsCount = patientAlerts.Count(a => !a.IsAcknowledged),
            FollowUpRequired = followUpRequired,
            FollowUpInstructions = followUpInstructions,
            FollowUpInDays = followUpInDays,
            LatestCompletedSessionId = latestCompleted?.SessionId,
            LatestRecordingAtUtc = latestCompleted?.StartTimeUtc,
            LatestAverageBpm = latestCompleted?.AverageBpm,
            LatestDurationSeconds = latestCompleted?.DurationSeconds,
            LatestSignalQuality = latestCompleted?.SignalQuality,
            ReviewStatus = overallReviewStatus
        };

        return (summary, queueItems, completedReviews, sessions);
    }

    public async Task<PulseLinkAPI.Transport.DeviceEvent> SendDoctorMessageAsync(
        CurrentAccess caller,
        string sessionId,
        string rawMessage,
        CancellationToken ct)
    {
        RequireDoctor(caller);
        var manifest = (await store.ReadAsync<RecordingManifest>($"{EcgDataService.RecordingRoot}/{Key(sessionId)}", ct)).Value
                       ?? throw new AccessProblem(404, "Session not found.");
        var (patient, site) = await RequireAssignedPatientAsync(caller, manifest.PatientId, ct);

        var message = rawMessage?.Trim() ?? string.Empty;
        if (message.Length is < 1 or > 300)
            throw new AccessProblem(400, "Coordination message must be between 1 and 300 characters.");

        message = new string(message.Where(c => !char.IsControl(c)).ToArray());
        if (string.IsNullOrWhiteSpace(message))
            throw new AccessProblem(400, "Coordination message cannot contain only control characters.");

        var device = (await directory.ReadDeviceAsync(manifest.DeviceId, ct)).Value
                     ?? throw new AccessProblem(404, "Device not found for session.");
        if (!device.Active || device.SiteId != site.Id)
            throw new AccessProblem(403, "Device is inactive or does not belong to the patient's site.");

        var eventId = Guid.NewGuid().ToString("N");
        var doctorName = ResolveDoctorName(caller);
        var deviceEvent = new PulseLinkAPI.Transport.DeviceEvent(
            Id: eventId,
            DeviceId: device.DeviceId,
            SiteId: site.Id,
            SessionId: sessionId,
            Kind: "doctor-coordination",
            ExpiresAtUtc: DateTimeOffset.UtcNow.AddHours(24),
            ReceivedAtUtc: null,
            Message: message,
            Author: doctorName
        );

        await store.WriteAsync($"deviceEvents/{device.DeviceId}/{eventId}", deviceEvent, ct: ct);
        return deviceEvent;
    }

    public async Task<List<PulseLinkAPI.Transport.DeviceEvent>> GetSessionMessagesAsync(
        CurrentAccess caller,
        string sessionId,
        CancellationToken ct)
    {
        RequireClinicalStaff(caller);
        var manifest = (await store.ReadAsync<RecordingManifest>($"{EcgDataService.RecordingRoot}/{Key(sessionId)}", ct)).Value
                       ?? throw new AccessProblem(404, "Session not found.");
        await RequireAssignedPatientAsync(caller, manifest.PatientId, ct);

        var page = await store.ListAsync<PulseLinkAPI.Transport.DeviceEvent>($"deviceEvents/{manifest.DeviceId}", null, 50, ct);
        return page.Items.Where(e => e.SessionId == sessionId && e.Kind == "doctor-coordination").ToList();
    }
}
