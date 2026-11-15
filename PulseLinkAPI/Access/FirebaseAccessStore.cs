using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using FirebaseAdmin;
using FirebaseAdmin.Auth;
using Google.Apis.Auth.OAuth2;

namespace PulseLinkAPI.Access;

public interface IFirebaseTokenVerifier
{
    Task<VerifiedUser> VerifyAsync(string token, CancellationToken cancellationToken);
}

public interface IFirebaseAccessTokenSource
{
    Task<string> GetAccessTokenAsync(CancellationToken ct);
}

// Uses server credentials from the hosting environment, never an APK or device secret.
// https://firebase.google.com/docs/auth/admin/verify-id-tokens
public sealed class FirebaseRuntime(IConfiguration configuration) : IFirebaseTokenVerifier, IFirebaseAccessTokenSource
{
    private readonly Lazy<GoogleCredential> credential = new(GoogleCredential.GetApplicationDefault);
    private FirebaseApp? app;
    private readonly object appLock = new();
    private GoogleCredential? databaseCredential;
    public GoogleCredential Credential
    {
        get
        {
            lock (appLock) return databaseCredential ??= credential.Value.CreateScoped(
                "https://www.googleapis.com/auth/firebase.database", "https://www.googleapis.com/auth/userinfo.email");
        }
    }
    public Task<string> GetAccessTokenAsync(CancellationToken ct) => Credential.UnderlyingCredential.GetAccessTokenForRequestAsync(null, ct);

    public async Task<VerifiedUser> VerifyAsync(string token, CancellationToken cancellationToken)
    {
        lock (appLock)
        {
            app ??= FirebaseApp.Create(new AppOptions
            {
                ProjectId = configuration["Firebase:ProjectId"] ?? throw new InvalidOperationException("Firebase ProjectId is required."),
                Credential = credential.Value
            }, $"pulselink-{Guid.NewGuid():N}");
        }
        var verified = await FirebaseAuth.GetAuth(app).VerifyIdTokenAsync(token, true, cancellationToken);
        string Claim(string key) => verified.Claims.TryGetValue(key, out var value) ? value?.ToString() ?? "" : "";
        return new VerifiedUser(verified.Uid, Claim("email"),
            verified.Claims.TryGetValue("email_verified", out var flag) && flag is true, Claim("name"));
    }
}

public interface IAccessStore
{
    Task<StoredValue<T>> ReadAsync<T>(string path, CancellationToken ct = default);
    Task WriteAsync<T>(string path, T value, string? etag = null, CancellationToken ct = default);
    Task<AccessPage<T>> ListAsync<T>(string path, string? after = null, int limit = 50, CancellationToken ct = default);
}

// Conditional writes preserve concurrent approvals and assignment changes.
// https://firebase.google.com/docs/database/rest/auth
public sealed class FirebaseAccessStore(HttpClient client, IFirebaseAccessTokenSource tokens, IConfiguration configuration) : IAccessStore
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public static string ValidatePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.Split('/').Any(p =>
            p.Length is 0 or > 128 || p.Any(c => char.IsControl(c) || ".#$[]?\\".Contains(c))))
            throw new AccessProblem(400, "An invalid record identifier was supplied.");
        return string.Join('/', path.Split('/').Select(Uri.EscapeDataString));
    }

    private async Task<HttpRequestMessage> Request(HttpMethod method, string path, string query, CancellationToken ct)
    {
        var baseUrl = configuration["Firebase:DatabaseUrl"]?.TrimEnd('/')
            ?? throw new InvalidOperationException("Firebase DatabaseUrl is required.");
        var uri = new Uri($"{baseUrl}/{ValidatePath(path)}.json{query}");
        if (uri.Scheme != Uri.UriSchemeHttps) throw new InvalidOperationException("Firebase requires HTTPS.");
        var request = new HttpRequestMessage(method, uri);
        var token = await tokens.GetAccessTokenAsync(ct);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return request;
    }

    private static async Task Check(HttpResponseMessage response, CancellationToken ct)
    {
        if (response.StatusCode == HttpStatusCode.PreconditionFailed)
            throw new AccessProblem(409, "This record changed. Refresh it before saving again.");
        if (!response.IsSuccessStatusCode)
            throw new AccessProblem(503, "The access directory is temporarily unavailable.");
        await Task.CompletedTask;
    }

    public async Task<StoredValue<T>> ReadAsync<T>(string path, CancellationToken ct = default)
    {
        using var request = await Request(HttpMethod.Get, path, "", ct);
        request.Headers.Add("X-Firebase-ETag", "true");
        using var response = await client.SendAsync(request, ct);
        await Check(response, ct);
        var body = await response.Content.ReadAsStringAsync(ct);
        var value = body.Trim() == "null" ? default : JsonSerializer.Deserialize<T>(body, Json);
        // Firebase/emulator revisions can be opaque tokens that the typed HTTP ETag parser rejects.
        // Preserve the exact header for the subsequent If-Match request.
        var etag = response.Headers.TryGetValues("ETag", out var revisions) ? revisions.SingleOrDefault() : null;
        if (string.IsNullOrEmpty(etag)) throw new AccessProblem(503, "The database did not return a revision.");
        return new StoredValue<T>(value, etag);
    }

    public async Task WriteAsync<T>(string path, T value, string? etag = null, CancellationToken ct = default)
    {
        using var request = await Request(HttpMethod.Put, path, "", ct);
        request.Content = JsonContent.Create(value, options: Json);
        if (etag != null) request.Headers.TryAddWithoutValidation("If-Match", etag);
        using var response = await client.SendAsync(request, ct);
        await Check(response, ct);
    }

    public async Task<AccessPage<T>> ListAsync<T>(string path, string? after = null, int limit = 50, CancellationToken ct = default)
    {
        limit = Math.Clamp(limit, 1, 100);
        if (after != null) ValidatePath(after);
        var query = $"?orderBy=%22%24key%22&limitToFirst={limit + 1}";
        if (after != null) query += $"&startAt={Uri.EscapeDataString(JsonSerializer.Serialize(after))}";
        using var request = await Request(HttpMethod.Get, path, query, ct);
        using var response = await client.SendAsync(request, ct);
        await Check(response, ct);
        var records = await response.Content.ReadFromJsonAsync<Dictionary<string, T>>(Json, ct) ?? [];
        var ordered = records.Where(p => after == null || string.CompareOrdinal(p.Key, after) > 0)
            .OrderBy(p => p.Key, StringComparer.Ordinal).ToList();
        // With an inclusive cursor, a full result can include the previous page's last key.
        var more = ordered.Count > limit || records.Count == limit + 1;
        var page = ordered.Take(limit).ToList();
        return new AccessPage<T>(page.Select(p => p.Value).ToList(), more && page.Count > 0 ? page[^1].Key : null);
    }
}
