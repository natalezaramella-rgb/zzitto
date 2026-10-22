// Astrazione 1:1 dell'API C del motore, così il wrapper ZzEngine si testa con un finto (Spec §12).
// L'implementazione reale è ZzNativeLibrary (P/Invoke verso zz_engine.dll). Spec §4.3.
// Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Engine;

public interface IZzNative
{
    uint ApiVersion();

    string VersionString();

    string StatusString(ZzStatus status);

    ZzStatus CreateEngine(EngineConfig config, out IntPtr engine);

    void DestroyEngine(IntPtr engine);

    ZzStatus Load(IntPtr engine);

    ZzStatus Unload(IntPtr engine);

    bool IsLoaded(IntPtr engine);

    ZzStatus SetDictionary(IntPtr engine, IReadOnlyList<string> words);

    ZzStatus SetShortcuts(IntPtr engine, IReadOnlyList<KeyValuePair<string, string>> shortcuts);

    ZzStatus Dictate(IntPtr engine, ReadOnlySpan<float> pcm16kMono, DictationOptions options,
        Action<EngineStage>? onStage, out EngineResult? result);

    ZzStatus Rewrite(IntPtr engine, string selectedText, ReadOnlySpan<float> pcm16kMono, DictationOptions options,
        Action<EngineStage>? onStage, out EngineResult? result);

    ZzStatus ProcessText(IntPtr engine, string rawText, DictationOptions options, out EngineResult? result);

    void Cancel(IntPtr engine);

    IntPtr CreateVad(int silenceMs);

    void DestroyVad(IntPtr vad);

    void ResetVad(IntPtr vad);

    bool ProcessVad(IntPtr vad, ReadOnlySpan<float> pcm16kMono);

    bool VadHasSpeech(IntPtr vad);

    ZzStatus Sha256File(string path, out string? hexDigest);
}
