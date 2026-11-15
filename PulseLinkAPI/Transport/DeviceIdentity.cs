using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;
using PulseLinkAPI.Access;

namespace PulseLinkAPI.Transport;

public sealed record DeviceIdentity(string DeviceId, string SiteId);
public sealed record DeviceCredential(string Sha256, string SiteId, DateTimeOffset IssuedAtUtc);
public sealed record IssuedDeviceCredential(string DeviceId, string SiteId, string Token);

public sealed class DeviceCredentials(IAccessStore store, AccessDirectory directory)
{
    public static bool ValidId(string id) => Regex.IsMatch(id, "^[A-Za-z0-9_-]{1,64}$", RegexOptions.CultureInvariant);
    private static string Path(string id) => $"{AccessDirectory.Root}/deviceCredentials/{id}";
    public async Task<IssuedDeviceCredential> RotateAsync(string id, CancellationToken ct)
    {
        if (!ValidId(id)) throw new AccessProblem(400, "Invalid device identifier.");
        var device = (await directory.ReadDeviceAsync(id, ct)).Value;
        if (device?.Active != true) throw new AccessProblem(403, "Assign an active device to a site first.");
        await directory.RequireSiteAsync(device.SiteId, ct);
        var token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var row = await store.ReadAsync<DeviceCredential>(Path(id), ct);
        await store.WriteAsync(Path(id), new DeviceCredential(Hash(token), device.SiteId, DateTimeOffset.UtcNow), row.ETag, ct);
        return new(id, device.SiteId, token);
    }
    public async Task RevokeAsync(string id, CancellationToken ct)
    {
        if (!ValidId(id)) throw new AccessProblem(400, "Invalid device identifier.");
        var row = await store.ReadAsync<DeviceCredential>(Path(id), ct);
        await store.WriteAsync(Path(id), new DeviceCredential("", "", DateTimeOffset.UtcNow), row.ETag, ct);
    }
    public async Task<DeviceIdentity?> VerifyAsync(string id, string token, CancellationToken ct)
    {
        if (!ValidId(id) || !Regex.IsMatch(token, "^[A-Za-z0-9_-]{43}$")) return null;
        var credential = (await store.ReadAsync<DeviceCredential>(Path(id), ct)).Value;
        var device = (await directory.ReadDeviceAsync(id, ct)).Value;
        if (credential == null || credential.Sha256.Length != 64 || device?.Active != true || device.SiteId != credential.SiteId)
            return null;
        if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(credential.Sha256), Encoding.ASCII.GetBytes(Hash(token))))
            return null;
        var site = (await store.ReadAsync<ClinicSite>($"{AccessDirectory.Root}/sites/{device.SiteId}", ct)).Value;
        return site?.Active == true ? new(id, device.SiteId) : null;
    }
    private static string Hash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token)));
}

public sealed class DeviceAuthenticationHandler(IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger, UrlEncoder encoder, DeviceCredentials credentials)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "RecordingDevice";
    private bool unavailable;
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var header = Request.Headers.Authorization.ToString();
        if (!header.StartsWith("Device ", StringComparison.Ordinal) || header.Length > 160)
            return AuthenticateResult.NoResult();
        var parts = header[7..].Split(':');
        if (parts.Length != 2) return AuthenticateResult.Fail("Invalid device credential.");
        try
        {
            var device = await credentials.VerifyAsync(parts[0], parts[1], Context.RequestAborted);
            if (device == null) return AuthenticateResult.Fail("Invalid device credential.");
            Context.Features.Set(device);
            var identity = new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, device.DeviceId)], SchemeName);
            return AuthenticateResult.Success(new(new ClaimsPrincipal(identity), SchemeName));
        }
        catch (OperationCanceledException) when (Context.RequestAborted.IsCancellationRequested) { throw; }
        catch (Exception error)
        {
            unavailable = true;
            Logger.LogError("Device verification unavailable ({Type}).", error.GetType().Name);
            return AuthenticateResult.Fail("Device verification unavailable.");
        }
    }
    protected override async Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        Response.StatusCode = unavailable ? 503 : 401;
        Response.Headers.WWWAuthenticate = "Device";
        await Response.WriteAsJsonAsync(new { status = Response.StatusCode, title = unavailable ? "Device verification unavailable." : "Device authentication required." });
    }
}
