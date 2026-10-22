// Test del wrapper ZzEngine con l'API nativa finta (Spec §4.2, §4.3, §12). Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Engine;

namespace Zzitto.Tests;

public class ZzEngineTests
{
    private static readonly DictationOptions Options = new("it", CorrectionMode.Formal, TranslateTarget.English, true, 5000);

    [Fact]
    public void Create_RejectsWrongApiVersion()
    {
        // Dal 22/10/2026 16:15 il contratto è alla versione 2 (streaming, Spec §11): una DLL v1 non basta.
        var native = new FakeNative { Api = 1 };
        var ex = Assert.Throws<ZzEngineException>(() => ZzEngine.Create(native, Config()));
        Assert.Equal(ZzStatus.Internal, ex.Status);
    }

    [Fact]
    public void Create_PropagatesNativeError()
    {
        var native = new FakeNative { CreateStatus = ZzStatus.ModelLoad };
        var ex = Assert.Throws<ZzEngineException>(() => ZzEngine.Create(native, Config()));
        Assert.Equal(ZzStatus.ModelLoad, ex.Status);
    }

    [Fact]
    public async Task Dictate_ReturnsResultAndPassesOptions()
    {
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        var stages = new List<EngineStage>();

        await engine.LoadAsync();
        EngineResult result = await engine.DictateAsync(new float[32000], 16000, Options, s => stages.Add(s));

        Assert.True(engine.IsLoaded);
        Assert.Equal("Ciao.", result.FinalText);
        Assert.Equal(16000, native.LastSampleCount);
        Assert.Equal(Options, native.LastOptions);
        Assert.Equal(new[] { EngineStage.Transcribing, EngineStage.Correcting, EngineStage.Done }, stages);
    }

    [Fact]
    public async Task Rewrite_PassesSelectedText()
    {
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        await engine.RewriteAsync("testo selezionato", new float[16000], 16000, Options, null);
        Assert.Equal("testo selezionato", native.LastSelectedText);
    }

    [Fact]
    public async Task Dictate_NoSpeech_ThrowsEngineException()
    {
        var native = new FakeNative { NextStatus = ZzStatus.NoSpeech };
        using ZzEngine engine = ZzEngine.Create(native, Config());
        var ex = await Assert.ThrowsAsync<ZzEngineException>(() => engine.DictateAsync(new float[100], 100, Options, null));
        Assert.Equal(ZzStatus.NoSpeech, ex.Status);
        Assert.Equal("stato NoSpeech", ex.Message);
    }

    [Fact]
    public async Task Dictate_CancellationCallsZzCancel()
    {
        var native = new FakeNative { DictateDelay = TimeSpan.FromSeconds(10) };
        using ZzEngine engine = ZzEngine.Create(native, Config());
        using var cts = new CancellationTokenSource();

        Task<EngineResult> task = engine.DictateAsync(new float[16000], 16000, Options, null, cts.Token);
        await Task.Delay(100);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => task);
        Assert.True(native.CancelCalls >= 1);
    }

    [Fact]
    public async Task ConcurrentCalls_AreSerialized()
    {
        // Contratto: un'operazione alla volta, altrimenti ZZ_ERR_BUSY.
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        Task[] tasks = Enumerable.Range(0, 8)
            .Select(_ => (Task)engine.DictateAsync(new float[1600], 1600, Options, null))
            .Append(engine.LoadAsync())
            .Append(engine.SetVocabularyAsync(new[] { "Zaramella" }, new[] { new KeyValuePair<string, string>("firma", "Natale") }))
            .ToArray();
        await Task.WhenAll(tasks);
        Assert.False(native.ConcurrentCallDetected);
        Assert.Equal(new[] { "Zaramella" }, native.LastDictionary);
        Assert.Single(native.LastShortcuts!);
    }

    [Fact]
    public async Task Dispose_DestroysEngineAndRejectsNewCalls()
    {
        var native = new FakeNative();
        ZzEngine engine = ZzEngine.Create(native, Config());
        engine.Dispose();
        Assert.True(native.Destroyed);
        await Assert.ThrowsAsync<ObjectDisposedException>(() => engine.LoadAsync());
    }

    [Fact]
    public void Dictate_RejectsInvalidSampleCount()
    {
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        Assert.Throws<ArgumentOutOfRangeException>(() =>
        {
            _ = engine.DictateAsync(new float[10], 11, Options, null);
        });
    }

    // ---- Streaming (API 2, Spec §11). Aggiunto il 22/10/2026 alle 16:15. ----

    [Fact]
    public async Task Stream_FeedThenFinish_ReturnsResultLikeDictate()
    {
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        var stages = new List<EngineStage>();

        IDictationStream stream = await engine.BeginStreamAsync(Options);
        float[] block = new float[1600];
        Assert.True(stream.Feed(block));
        Assert.True(stream.Feed(block.AsSpan(0, 800)));
        Assert.True(stream.Feed(ReadOnlySpan<float>.Empty));
        EngineResult result = await stream.FinishAsync(s => stages.Add(s));

        Assert.Equal("Ciao.", result.FinalText);
        Assert.Equal(2400, native.LastSampleCount);
        Assert.Equal(Options, native.LastOptions);
        Assert.Equal(new[] { EngineStage.Transcribing, EngineStage.Correcting, EngineStage.Done }, stages);
        Assert.False(native.StreamOpen);
        Assert.False(stream.Feed(block)); // dopo finish il flusso è liberato: nessuna chiamata nativa
        await Assert.ThrowsAsync<InvalidOperationException>(() => stream.FinishAsync(null));
    }

    [Fact]
    public async Task Stream_KeepsEngineBusyUntilFinish()
    {
        // Contratto: con un flusso aperto le altre operazioni avrebbero ZZ_ERR_BUSY, quindi si accodano.
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        IDictationStream stream = await engine.BeginStreamAsync(Options);

        Task load = engine.LoadAsync();
        await Task.Delay(100);
        Assert.False(load.IsCompleted);

        stream.Feed(new float[1600]);
        await stream.FinishAsync(null);
        await load;
        Assert.False(native.ConcurrentCallDetected);
    }

    [Fact]
    public async Task Stream_BeginError_ThrowsEngineExceptionAndFreesEngine()
    {
        var native = new FakeNative { StreamBeginStatus = ZzStatus.ModelIntegrity };
        using ZzEngine engine = ZzEngine.Create(native, Config());

        var ex = await Assert.ThrowsAsync<ZzEngineException>(() => engine.BeginStreamAsync(Options));
        Assert.Equal(ZzStatus.ModelIntegrity, ex.Status);

        await engine.DictateAsync(new float[1600], 1600, Options, null); // il motore non resta occupato
    }

    [Fact]
    public async Task Stream_FinishError_IsReportedLikeDictate()
    {
        var native = new FakeNative { NextStatus = ZzStatus.NoSpeech };
        using ZzEngine engine = ZzEngine.Create(native, Config());
        IDictationStream stream = await engine.BeginStreamAsync(Options);

        var ex = await Assert.ThrowsAsync<ZzEngineException>(() => stream.FinishAsync(null));
        Assert.Equal(ZzStatus.NoSpeech, ex.Status);
        Assert.Equal(1, native.StreamFinishCalls);
        Assert.Equal(0, native.StreamAbortCalls);
    }

    [Fact]
    public async Task Stream_FeedError_MarksStreamFaulted()
    {
        var native = new FakeNative { FeedStatus = ZzStatus.OutOfMemory };
        using ZzEngine engine = ZzEngine.Create(native, Config());
        IDictationStream stream = await engine.BeginStreamAsync(Options);

        Assert.False(stream.Feed(new float[160]));
        Assert.True(stream.IsFaulted);
        stream.Abort();
        Assert.Equal(1, native.StreamAbortCalls);
    }

    [Fact]
    public async Task Stream_Abort_FreesEngineOnce()
    {
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        IDictationStream stream = await engine.BeginStreamAsync(Options);
        stream.Feed(new float[1600]);

        stream.Abort();
        stream.Abort();
        stream.Dispose();

        Assert.Equal(1, native.StreamAbortCalls);
        Assert.Equal(0, native.StreamFinishCalls);
        Assert.False(stream.Feed(new float[10]));
        await engine.DictateAsync(new float[1600], 1600, Options, null);
        Assert.False(native.ConcurrentCallDetected);
    }

    [Fact]
    public async Task Stream_FinishCancellationCallsZzCancel()
    {
        var native = new FakeNative { DictateDelay = TimeSpan.FromSeconds(10) };
        using ZzEngine engine = ZzEngine.Create(native, Config());
        using var cts = new CancellationTokenSource();
        IDictationStream stream = await engine.BeginStreamAsync(Options);

        Task<EngineResult> task = stream.FinishAsync(null, cts.Token);
        await Task.Delay(100);
        cts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => task);
        Assert.True(native.CancelCalls >= 1);
        await engine.LoadAsync(); // coda liberata
    }

    [Fact]
    public async Task Stream_AlreadyCancelledFinish_AbortsWithoutResult()
    {
        var native = new FakeNative();
        using ZzEngine engine = ZzEngine.Create(native, Config());
        IDictationStream stream = await engine.BeginStreamAsync(Options);

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => stream.FinishAsync(null, new CancellationToken(true)));
        Assert.Equal(1, native.StreamAbortCalls);
        Assert.Equal(0, native.StreamFinishCalls);
    }

    [Fact]
    public async Task Dispose_AbortsOpenStream()
    {
        var native = new FakeNative();
        ZzEngine engine = ZzEngine.Create(native, Config());
        IDictationStream stream = await engine.BeginStreamAsync(Options);

        engine.Dispose();

        Assert.Equal(1, native.StreamAbortCalls);
        Assert.True(native.Destroyed);
        Assert.False(stream.Feed(new float[10]));
    }

    private static EngineConfig Config() => new("w.bin", "l.gguf", null, null);
}
