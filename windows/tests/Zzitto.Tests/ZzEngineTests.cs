// Test del wrapper ZzEngine con l'API nativa finta (Spec §4.2, §4.3, §12). Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Engine;

namespace Zzitto.Tests;

public class ZzEngineTests
{
    private static readonly DictationOptions Options = new("it", CorrectionMode.Formal, TranslateTarget.English, true, 5000);

    [Fact]
    public void Create_RejectsWrongApiVersion()
    {
        var native = new FakeNative { Api = 2 };
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

    private static EngineConfig Config() => new("w.bin", "l.gguf", null, null);
}
