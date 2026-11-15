using Microsoft.Extensions.Configuration;
using PulseLinkAPI.Access;
using Xunit;

namespace PulseLinkAPI.Tests;

// Run through the Firebase emulator script. Never use a live Firebase project.
public sealed class EmulatorFactAttribute : FactAttribute
{
    public EmulatorFactAttribute()
    {
        if (Environment.GetEnvironmentVariable("FIREBASE_DATABASE_EMULATOR_HOST") != "127.0.0.1:9000")
            Skip = "Requires the local demo Firebase database emulator on 127.0.0.1:9000.";
    }
}

public sealed class FirebaseRecordingTests
{
    private sealed class EmulatorToken : IFirebaseAccessTokenSource
    {
        public Task<string> GetAccessTokenAsync(CancellationToken ct) => Task.FromResult("owner");
    }
    private sealed class EmulatorTransport(string prefix) : DelegatingHandler(new HttpClientHandler())
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var source = request.RequestUri!;
            request.RequestUri = new Uri($"http://127.0.0.1:9000/{prefix}{source.AbsolutePath}{source.Query}" +
                (source.Query.Length == 0 ? "?" : "&") + "ns=demo-pulselink-access");
            return base.SendAsync(request, ct);
        }
    }
    private static FirebaseAccessStore Store(string prefix)
    {
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["Firebase:DatabaseUrl"] = "https://emulator-only.invalid"
        }).Build();
        return new(new HttpClient(new EmulatorTransport(prefix)), new EmulatorToken(), config);
    }

    [EmulatorFact]
    public async Task Real_Firebase_ETags_preserve_recordings_across_instances_and_concurrent_writers()
    {
        var prefix = $"test-{Guid.NewGuid():N}";
        var one = RecordingPersistenceTests.Service(Store(prefix));
        var two = RecordingPersistenceTests.Service(Store(prefix));
        var identity = Guid.NewGuid(); var id = (await RecordingPersistenceTests.Start(one, identity)).SessionId;
        Assert.Equal(id, (await RecordingPersistenceTests.Start(two, identity)).SessionId);
        await Task.WhenAll(one.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id)),
            two.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id, 1)));
        Assert.True((await two.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id))).Duplicate);
        var final = await two.CompleteRecordingAsync(id, new() { ExpectedBatchCount = 2, ExpectedSampleCount = 8 });
        Assert.Equal("Completed", final!.Status);
        var restarted = RecordingPersistenceTests.Service(Store(prefix));
        Assert.Equal(8, (await restarted.GetSessionSamplesAsync(id))!.TotalSamples);
        Assert.Single((await restarted.GetPatientSessionPageAsync("P1")).Items);
        await restarted.DiscardSessionAsync(id);
        Assert.Equal(410, (await Assert.ThrowsAsync<AccessProblem>(() => one.IngestRecordingBatchAsync(RecordingPersistenceTests.Batch(id)))).Status);
    }

    [EmulatorFact]
    public async Task Real_Firebase_key_pagination_and_conditional_writes_match_the_store_contract()
    {
        var store = Store($"test-{Guid.NewGuid():N}");
        foreach (var key in new[] { "A", "B", "C" }) await store.WriteAsync($"page/{key}", key);
        var first = await store.ListAsync<string>("page", limit: 1);
        Assert.Equal("A", Assert.Single(first.Items));
        var second = await store.ListAsync<string>("page", first.NextCursor, 1);
        Assert.Equal("B", Assert.Single(second.Items));
        var third = await store.ListAsync<string>("page", second.NextCursor, 1);
        Assert.Equal("C", Assert.Single(third.Items));
        // Inclusive Firebase cursors can require a final empty page.
        if (third.NextCursor != null) Assert.Empty((await store.ListAsync<string>("page", third.NextCursor, 1)).Items);
        var row = await store.ReadAsync<string>("page/A");
        await store.WriteAsync("page/A", "new", row.ETag);
        Assert.Equal(409, (await Assert.ThrowsAsync<AccessProblem>(() => store.WriteAsync("page/A", "stale", row.ETag))).Status);
    }
}
