using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using PulseLinkAPI.Access;

namespace PulseLinkAPI.Controllers;

[ApiController, Authorize(Roles = "patient,doctor,nurse,administrator"), Route("api/notifications")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class NotificationsController(IAccessStore store, ClinicalNotifications notifications) : ControllerBase
{
    private CurrentAccess Caller => HttpContext.Features.Get<CurrentAccess>()!;

    [HttpGet]
    public async Task<AccessPage<ClinicalNotification>> Inbox([FromQuery] string? after = null, CancellationToken ct = default)
    {
        var page = await store.ListAsync<ClinicalNotification>($"clinicalInbox/{Caller.User.Uid}", after, 50, ct);
        var visible = new List<ClinicalNotification>();
        foreach (var item in page.Items)
            if (await notifications.CanReadAsync(Caller.Account, item, ct)) visible.Add(item);
        return new(visible, page.NextCursor);
    }

    [HttpPut("registration")]
    public async Task<IActionResult> Register(PushRegistrationRequest request, CancellationToken ct)
    {
        await notifications.RegisterAsync(Caller.User.Uid, request.Token, ct);
        return NoContent();
    }
}
