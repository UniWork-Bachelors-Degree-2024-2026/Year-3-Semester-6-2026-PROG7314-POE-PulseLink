using System.Security.Claims;
using System.Text.Encodings.Web;
using FirebaseAdmin.Auth;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace PulseLinkAPI.Access;

public sealed class FirebaseBearerHandler(IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger, UrlEncoder encoder, IFirebaseTokenVerifier verifier, AccessDirectory directory)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "FirebaseBearer";
    private bool serviceUnavailable;

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var header = Request.Headers.Authorization.ToString();
        if (string.IsNullOrEmpty(header)) return AuthenticateResult.NoResult();
        if (!header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) || header.Length > 8192)
            return AuthenticateResult.Fail("A Firebase bearer token is required.");
        try
        {
            var user = await verifier.VerifyAsync(header[7..].Trim(), Context.RequestAborted);
            var account = await directory.ResolveAsync(user, Context.RequestAborted);
            var claims = new List<Claim> { new(ClaimTypes.NameIdentifier, user.Uid),
                new(ClaimTypes.Email, user.Email), new(ClaimTypes.Name, user.DisplayName) };
            if (account.Status == "approved" && account.Role is AccessRoles.Patient or AccessRoles.Nurse or AccessRoles.Doctor or AccessRoles.Administrator)
                claims.Add(new Claim(ClaimTypes.Role, account.Role));
            Context.Features.Set(new CurrentAccess(user, account));
            return AuthenticateResult.Success(new AuthenticationTicket(new ClaimsPrincipal(new ClaimsIdentity(claims, SchemeName)), SchemeName));
        }
        catch (FirebaseAuthException) { return AuthenticateResult.Fail("The sign-in token is invalid or revoked."); }
        catch (System.Security.Authentication.AuthenticationException) { return AuthenticateResult.Fail("The sign-in token is invalid."); }
        catch (OperationCanceledException) when (Context.RequestAborted.IsCancellationRequested) { throw; }
        catch (Exception error)
        {
            serviceUnavailable = true;
            Logger.LogError("Identity or access verification is unavailable ({ErrorType}).", error.GetType().Name);
            return AuthenticateResult.Fail("Access verification is unavailable.");
        }
    }

    protected override async Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        Response.StatusCode = serviceUnavailable ? 503 : 401;
        if (!serviceUnavailable) Response.Headers.WWWAuthenticate = "Bearer";
        await Response.WriteAsJsonAsync(new { title = serviceUnavailable ? "Access verification is unavailable." : "Sign in is required.", status = Response.StatusCode });
    }
}
