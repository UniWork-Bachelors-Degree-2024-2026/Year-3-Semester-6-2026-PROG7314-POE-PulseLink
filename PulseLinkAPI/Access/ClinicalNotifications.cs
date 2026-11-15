using System.ComponentModel.DataAnnotations;
using System.Security.Cryptography;
using System.Text;
using FirebaseAdmin;
using FirebaseAdmin.Messaging;
using Google.Apis.Auth.OAuth2;

namespace PulseLinkAPI.Access;

public sealed record ClinicalNotification(
    string NotificationId,
    string RecipientUid,
    string Type,
    string PatientId,
    string SessionId,
    DateTimeOffset CreatedAtUtc,
    string? DoctorName = null,
    string? PatientName = null,
    string? PrescriptionSummary = null,
    string? ClinicalAdvice = null);
public sealed record PushRegistration(string Token, string OwnerUid, DateTimeOffset UpdatedAtUtc);
public sealed record ClinicalReportJob(string SessionId, DateTimeOffset RetryAtUtc);
public sealed class PushRegistrationRequest
{
    [Required, StringLength(4096, MinimumLength = 20)]
    public string Token { get; set; } = "";
}
public sealed class PendingClinicalNotification
{
    public ClinicalNotification Notification { get; set; } = null!;
    public DateTimeOffset RetryAtUtc { get; set; }
    public List<string> DeliveredTokenHashes { get; set; } = [];
}

// Stable event identifiers prevent retries and report downloads from creating duplicate inbox rows.
public sealed class ClinicalNotifications(IAccessStore store)
{
    public static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();

    public Task EnqueueAsync(string patientId, string sessionId, string type, string revision = "", CancellationToken ct = default) =>
        EnqueueAsync(patientId, sessionId, type, revision, null, null, null, null, ct);

    public async Task EnqueueAsync(
        string patientId,
        string sessionId,
        string type,
        string revision,
        string? doctorName,
        string? patientName,
        string? prescriptionSummary,
        string? clinicalAdvice,
        CancellationToken ct = default)
    {
        if (type is not ("REPORT_READY" or "RESULTS_RELEASED" or "PRESCRIPTION_ISSUED" or "CLINICAL_ADVICE"))
            throw new ArgumentException("Unsupported clinical notification event.", nameof(type));
        var patient = (await store.ReadAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients/{patientId}", ct)).Value;
        if (patient == null) return;
        var resolvedPatientName = string.IsNullOrWhiteSpace(patientName)
            ? (string.IsNullOrWhiteSpace(patient.FullName) ? null : patient.FullName.Trim())
            : patientName.Trim();
        var resolvedDoctorName = string.IsNullOrWhiteSpace(doctorName) ? null : doctorName.Trim();
        var resolvedPrescription = string.IsNullOrWhiteSpace(prescriptionSummary) ? null : prescriptionSummary.Trim();
        var resolvedAdvice = string.IsNullOrWhiteSpace(clinicalAdvice) ? null : clinicalAdvice.Trim();
        IEnumerable<string> recipients = type == "REPORT_READY"
            ? patient.AdditionalDoctorUids.Append(patient.PrimaryDoctorUid).Distinct()
            : patient.LinkedAccountUid is { Length: > 0 } patientUid ? [patientUid] : [];
        foreach (var uid in recipients.Where(u => !string.IsNullOrWhiteSpace(u)))
        {
            var id = Hash($"{type}|{sessionId}|{uid}|{revision}");
            var inbox = await store.ReadAsync<ClinicalNotification>($"clinicalInbox/{uid}/{id}", ct);
            if (inbox.Value != null) continue;
            var path = $"clinicalNotificationOutbox/{id}";
            var pending = await store.ReadAsync<PendingClinicalNotification>(path, ct);
            if (pending.Value != null) continue;
            try
            {
                await store.WriteAsync(path, new PendingClinicalNotification
                {
                    Notification = new(
                        id,
                        uid,
                        type,
                        patientId,
                        sessionId,
                        DateTimeOffset.UtcNow,
                        resolvedDoctorName,
                        resolvedPatientName,
                        resolvedPrescription,
                        resolvedAdvice)
                }, pending.ETag, ct);
            }
            catch (AccessProblem p) when (p.Status == 409) { }
        }
    }

    public async Task<bool> CanReadAsync(AccessAccount account, ClinicalNotification item, CancellationToken ct)
    {
        if (account.Status != "approved" || account.Uid != item.RecipientUid) return false;
        var patient = (await store.ReadAsync<ClinicalPatient>($"{AccessDirectory.Root}/patients/{item.PatientId}", ct)).Value;
        if (patient == null) return false;
        var site = (await store.ReadAsync<ClinicSite>($"{AccessDirectory.Root}/sites/{patient.SiteId}", ct)).Value;
        if (site?.Active != true) return false;
        if (item.Type == "REPORT_READY")
            return account.Role == AccessRoles.Doctor && AccessDirectory.CanReadClinicalPatient(account, patient);
        if (item.Type is not ("RESULTS_RELEASED" or "PRESCRIPTION_ISSUED" or "CLINICAL_ADVICE") ||
            account.Role != AccessRoles.Patient ||
            patient.LinkedAccountUid != account.Uid)
            return false;
        var link = (await store.ReadAsync<PatientLoginLink>($"{AccessDirectory.Root}/patientLogins/{account.Uid}", ct)).Value;
        return link?.PatientId == patient.Id && link.AccountUid == account.Uid;
    }

    public async Task RegisterAsync(string uid, string token, CancellationToken ct)
    {
        var hash = Hash(token);
        var row = new PushRegistration(token, uid, DateTimeOffset.UtcNow);
        // The global owner is checked on every send, including when a phone changes accounts.
        await store.WriteAsync($"pushTokenOwners/{hash}", row, ct: ct);
        await store.WriteAsync($"pushTokens/{uid}/{hash}", row, ct: ct);
    }
}

public interface IClinicalPushSender
{
    Task SendAsync(string token, ClinicalNotification notification, CancellationToken ct);
}

public sealed class FirebaseClinicalPushSender(IConfiguration configuration) : IClinicalPushSender
{
    private readonly Lazy<FirebaseApp> app = new(() => FirebaseApp.Create(new AppOptions
    {
        ProjectId = configuration["Firebase:ProjectId"], Credential = GoogleCredential.GetApplicationDefault()
    }, $"pulselink-push-{Guid.NewGuid():N}"));

    public async Task SendAsync(string token, ClinicalNotification notification, CancellationToken ct) =>
        await FirebaseMessaging.GetMessaging(app.Value).SendAsync(new Message
        {
            Token = token,
            // Push is a wake-up hint. Clinical content is retrieved through the authenticated inbox API.
            Data = new Dictionary<string, string>
            {
                ["notificationId"] = notification.NotificationId,
                ["recipientUid"] = notification.RecipientUid
            },
            Android = new AndroidConfig { Priority = Priority.Normal, TimeToLive = TimeSpan.FromHours(24) }
        }, ct);
}

public sealed class ClinicalNotificationDispatcher(IAccessStore store, ClinicalNotifications notifications, IClinicalPushSender sender)
{
    public async Task DispatchAsync(CancellationToken ct)
    {
        string? after = null;
        do
        {
            var page = await store.ListAsync<PendingClinicalNotification>("clinicalNotificationOutbox", after, 100, ct);
            after = page.NextCursor;
            foreach (var item in page.Items.Where(i => i != null && i.RetryAtUtc <= DateTimeOffset.UtcNow))
            {
                var n = item.Notification;
                var path = $"clinicalNotificationOutbox/{n.NotificationId}";
                var stored = await store.ReadAsync<PendingClinicalNotification>(path, ct);
                if (stored.Value == null || stored.Value.RetryAtUtc > DateTimeOffset.UtcNow) continue;
                var pending = stored.Value;
                pending.RetryAtUtc = DateTimeOffset.UtcNow.AddMinutes(2);
                try { await store.WriteAsync(path, pending, stored.ETag, ct); }
                catch (AccessProblem p) when (p.Status == 409) { continue; }
                var account = (await store.ReadAsync<AccessAccount>($"{AccessDirectory.Root}/accounts/{n.RecipientUid}", ct)).Value;
                if (account != null && await notifications.CanReadAsync(account, n, ct))
                {
                    await store.WriteAsync($"clinicalInbox/{n.RecipientUid}/{n.NotificationId}", n, ct: ct);
                    string? tokenCursor = null;
                    do
                    {
                        var tokens = await store.ListAsync<PushRegistration>($"pushTokens/{n.RecipientUid}", tokenCursor, 100, ct);
                        tokenCursor = tokens.NextCursor;
                        foreach (var token in tokens.Items.Where(t => t != null))
                        {
                            var hash = ClinicalNotifications.Hash(token.Token);
                            if (pending.DeliveredTokenHashes.Contains(hash)) continue;
                            var owner = await store.ReadAsync<PushRegistration>($"pushTokenOwners/{hash}", ct);
                            if (owner.Value?.OwnerUid != n.RecipientUid || token.UpdatedAtUtc < DateTimeOffset.UtcNow.AddDays(-30)) continue;
                            try { await sender.SendAsync(token.Token, n, ct); }
                            catch (FirebaseMessagingException ex) when (ex.MessagingErrorCode is MessagingErrorCode.Unregistered or MessagingErrorCode.InvalidArgument)
                            {
                                await store.WriteAsync<PushRegistration?>($"pushTokens/{n.RecipientUid}/{hash}", null, ct: ct);
                            }
                            pending.DeliveredTokenHashes.Add(hash);
                            await store.WriteAsync(path, pending, ct: ct);
                        }
                    } while (tokenCursor != null);
                }
                await store.WriteAsync<PendingClinicalNotification?>(path, null, ct: ct);
            }
        } while (after != null);
    }
}

public sealed class ClinicalNotificationWorker(IServiceScopeFactory scopes, ILogger<ClinicalNotificationWorker> logger, IConfiguration configuration) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (configuration.GetValue<bool?>("Notifications:WorkerEnabled") == false) return;
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                using var scope = scopes.CreateScope();
                var store = scope.ServiceProvider.GetRequiredService<IAccessStore>();
                var ecg = scope.ServiceProvider.GetRequiredService<PulseLinkAPI.Services.Interfaces.IEcgDataService>();
                string? cursor = null;
                do
                {
                    var reports = await store.ListAsync<ClinicalReportJob>("clinicalReportJobs", cursor, 100, stoppingToken);
                    cursor = reports.NextCursor;
                    foreach (var job in reports.Items.Where(j => j != null && j.RetryAtUtc <= DateTimeOffset.UtcNow))
                    {
                        try
                        {
                            await ecg.GenerateSessionReportPdfAsync(job.SessionId);
                            await store.WriteAsync<ClinicalReportJob?>($"clinicalReportJobs/{job.SessionId}", null, ct: stoppingToken);
                        }
                        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { throw; }
                        catch (Exception)
                        {
                            await store.WriteAsync($"clinicalReportJobs/{job.SessionId}", job with { RetryAtUtc = DateTimeOffset.UtcNow.AddMinutes(2) }, ct: stoppingToken);
                        }
                    }
                } while (cursor != null);
                await scope.ServiceProvider.GetRequiredService<ClinicalNotificationDispatcher>().DispatchAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception) { logger.LogWarning("Clinical notification delivery is pending and will retry."); }
            await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken);
        }
    }
}
