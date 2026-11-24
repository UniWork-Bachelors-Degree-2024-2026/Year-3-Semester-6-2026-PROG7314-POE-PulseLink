using System.ComponentModel.DataAnnotations;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Access;

namespace PulseLinkAPI.Controllers;

public sealed record SupportChatThreadDto(
    string ThreadId,
    string PatientName,
    string LastMessage,
    long LastTimestampMillis,
    string LastSenderRole);

public sealed record SupportChatMessageDto(
    string Id,
    string ThreadId,
    string PatientName,
    string SenderUid,
    string SenderName,
    string SenderRole,
    string Text,
    long TimestampMillis);

public sealed class SendSupportMessageRequest
{
    [Required, StringLength(1000, MinimumLength = 1)]
    public string Text { get; set; } = "";

    [StringLength(160)]
    public string? PatientName { get; set; }
}

[ApiController, Authorize, Route("api/support-chat")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class SupportChatController(IAccessStore store) : ControllerBase
{
    private const string IndexRoot = "supportChatsV1Index";
    private const string MessagesRoot = "supportChatsV1Messages";
    private CurrentAccess Caller => HttpContext.Features.Get<CurrentAccess>()!;

    private static string Key(string id)
    {
        FirebaseAccessStore.ValidatePath(id);
        if (id.Contains('/')) throw new AccessProblem(400, "Invalid thread identifier.");
        return id;
    }

    private void RequireThreadAccess(string threadId)
    {
        if (Caller.Account.Status != "approved")
            throw new AccessProblem(403, "Approved account required.");
        if (Caller.Account.Role == AccessRoles.Patient && Caller.User.Uid != threadId)
            throw new AccessProblem(403, "Patients can only access their own support thread.");
        if (Caller.Account.Role is not (AccessRoles.Patient or AccessRoles.Nurse or AccessRoles.Doctor or AccessRoles.Administrator))
            throw new AccessProblem(403, "Unsupported role.");
    }

    [HttpGet("threads")]
    public async Task<IReadOnlyList<SupportChatThreadDto>> Threads(CancellationToken ct)
    {
        if (Caller.Account.Role == AccessRoles.Patient)
        {
            var own = (await store.ReadAsync<SupportChatThreadDto>($"{IndexRoot}/{Key(Caller.User.Uid)}", ct)).Value;
            return own == null ? [] : [own];
        }
        var page = await store.ListAsync<SupportChatThreadDto>(IndexRoot, limit: 100, ct: ct);
        return page.Items.OrderByDescending(t => t.LastTimestampMillis).ToList();
    }

    [HttpGet("threads/{threadId}/messages")]
    public async Task<IReadOnlyList<SupportChatMessageDto>> Messages(string threadId, CancellationToken ct)
    {
        var key = Key(threadId);
        RequireThreadAccess(key);
        var page = await store.ListAsync<SupportChatMessageDto>($"{MessagesRoot}/{key}", limit: 100, ct: ct);
        return page.Items.OrderBy(m => m.TimestampMillis).ToList();
    }

    [HttpPost("threads/{threadId}/messages")]
    public async Task<ActionResult<SupportChatMessageDto>> Send(string threadId, [FromBody] SendSupportMessageRequest request, CancellationToken ct)
    {
        var key = Key(threadId);
        RequireThreadAccess(key);
        var senderName = string.IsNullOrWhiteSpace(Caller.Account.DisplayName)
            ? (string.IsNullOrWhiteSpace(Caller.User.DisplayName) ? Caller.Account.Email : Caller.User.DisplayName)
            : Caller.Account.DisplayName;
        var existing = (await store.ReadAsync<SupportChatThreadDto>($"{IndexRoot}/{key}", ct)).Value;
        var patientName = !string.IsNullOrWhiteSpace(request.PatientName)
            ? request.PatientName.Trim()
            : Caller.Account.Role == AccessRoles.Patient
                ? senderName
                : (existing?.PatientName ?? "Clinic Patient");

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var msgId = $"M{now:D15}-{Guid.NewGuid():N}"[..28];
        var message = new SupportChatMessageDto(
            msgId, key, patientName, Caller.User.Uid, senderName, Caller.Account.Role, request.Text.Trim(), now);

        await store.WriteAsync($"{MessagesRoot}/{key}/{msgId}", message, ct: ct);
        var summary = new SupportChatThreadDto(key, patientName, message.Text, now, Caller.Account.Role);
        await store.WriteAsync($"{IndexRoot}/{key}", summary, ct: ct);
        return Ok(message);
    }
}
