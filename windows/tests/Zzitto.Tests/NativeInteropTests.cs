// Test del P/Invoke verso zz_engine.dll (motore finto, Spec §4.3, §12): layout delle strutture,
// marshalling UTF-8, callback degli stadi, VAD, SHA-256. Aggiunto il 22/10/2026 alle 11:32.
//
// Il target msbuild/Zzitto.Native.targets compila la DLL e la copia accanto ai test;
// zzitto.runsettings imposta ZZ_MOCK_FAST=1.
using System.Text;
using Zzitto.Platform.Engine;

namespace Zzitto.Tests;

public class NativeInteropTests
{
    private static readonly ZzNativeLibrary Native = new();

    [Fact]
    public unsafe void StructLayouts_MatchTheCContract()
    {
        // Dimensioni di zz_engine_config, zz_dictation_options e zz_result con MSVC a 64 bit.
        Assert.Equal(56, sizeof(ZzEngineConfigNative));
        Assert.Equal(32, sizeof(ZzDictationOptionsNative));
        Assert.Equal(48, sizeof(ZzResultNative));
    }

    [Fact]
    public void ApiVersion_IsOne()
    {
        Assert.Equal(1u, Native.ApiVersion());
        Assert.False(string.IsNullOrEmpty(Native.VersionString()));
        Assert.Equal("nessun parlato rilevato", Native.StatusString(ZzStatus.NoSpeech));
    }

    [Fact]
    public async Task Shortcut_RoundTripsUtf8()
    {
        using ZzEngine engine = ZzEngine.Create(Native, new EngineConfig("w.bin", "l.gguf", null, null));
        const string expansion = "Natale Zaramella – città, perché ✓";
        await engine.SetVocabularyAsync(new[] { "Zzitto", "Zaramella" },
            new[] { new KeyValuePair<string, string>("firma", expansion) });

        EngineResult result = await engine.ProcessTextAsync("Firma.", new DictationOptions());

        Assert.Equal(expansion, result.FinalText);
        Assert.True(result.FromShortcut);
    }

    [Fact]
    public async Task ProcessText_KeepsAccentedCharacters()
    {
        using ZzEngine engine = ZzEngine.Create(Native, new EngineConfig("w.bin", "l.gguf", null, null));
        EngineResult result = await engine.ProcessTextAsync("ehm perché la città è bella", new DictationOptions());
        Assert.Contains("città", result.FinalText, StringComparison.Ordinal);
        Assert.Contains("perch", result.FinalText, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Dictate_InvokesStageCallbackThroughNativeBoundary()
    {
        using ZzEngine engine = ZzEngine.Create(Native, new EngineConfig("w.bin", "l.gguf", null, null));
        await engine.LoadAsync();
        var stages = new List<EngineStage>();
        float[] pcm = new float[16000];

        EngineResult result = await engine.DictateAsync(pcm, pcm.Length, new DictationOptions(), s =>
        {
            lock (stages)
            {
                stages.Add(s);
            }
        });

        Assert.False(string.IsNullOrEmpty(result.FinalText));
        Assert.Equal(EngineStage.Transcribing, stages[0]);
        Assert.Equal(EngineStage.Done, stages[^1]);
    }

    [Fact]
    public async Task Dictate_TooShort_IsNoSpeech()
    {
        using ZzEngine engine = ZzEngine.Create(Native, new EngineConfig("w.bin", "l.gguf", null, null));
        await engine.LoadAsync();
        var ex = await Assert.ThrowsAsync<ZzEngineException>(() => engine.DictateAsync(new float[100], 100, new DictationOptions(), null));
        Assert.Equal(ZzStatus.NoSpeech, ex.Status);
    }

    [Fact]
    public void Vad_DetectsEndOfSpeech()
    {
        using var vad = new ZzVad(Native, 300);
        float[] speech = Enumerable.Range(0, 4800).Select(i => i % 2 == 0 ? 0.3f : -0.3f).ToArray();
        float[] silence = new float[9600];

        Assert.False(vad.Process(speech));
        Assert.True(vad.HasSpeech);
        Assert.True(vad.Process(silence));
        vad.Reset();
        Assert.False(vad.HasSpeech);
    }

    [Fact]
    public void Sha256File_ComputesKnownDigest()
    {
        string folder = Path.Combine(Path.GetTempPath(), "zzitto-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        string path = Path.Combine(folder, "abc.txt");
        try
        {
            File.WriteAllBytes(path, Encoding.ASCII.GetBytes("abc"));
            Assert.Equal(ZzStatus.Ok, Native.Sha256File(path, out string? hex));
            Assert.Equal("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex);
        }
        finally
        {
            Directory.Delete(folder, recursive: true);
        }
    }
}
