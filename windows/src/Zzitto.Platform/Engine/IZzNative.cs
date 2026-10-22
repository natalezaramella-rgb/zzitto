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

    // ---- Dettatura in streaming (API 2, Spec §11). Aggiunto il 22/10/2026 alle 16:15. ----

    /// <summary>zz_stream_begin: apre un flusso (bloccante, carica i modelli se serve). Le opzioni vengono copiate.</summary>
    ZzStatus StreamBegin(IntPtr engine, DictationOptions options, out IntPtr stream);

    /// <summary>zz_stream_feed: non bloccante, chiamabile dal thread di cattura. Nessuna copia lato gestito.</summary>
    ZzStatus StreamFeed(IntPtr stream, ReadOnlySpan<float> pcm16kMono);

    /// <summary>zz_stream_finish: bloccante; libera sempre il flusso, anche in caso di errore.</summary>
    ZzStatus StreamFinish(IntPtr stream, Action<EngineStage>? onStage, out EngineResult? result);

    /// <summary>zz_stream_abort: annulla e libera il flusso senza risultato.</summary>
    void StreamAbort(IntPtr stream);

    void Cancel(IntPtr engine);

    IntPtr CreateVad(int silenceMs);

    void DestroyVad(IntPtr vad);

    void ResetVad(IntPtr vad);

    bool ProcessVad(IntPtr vad, ReadOnlySpan<float> pcm16kMono);

    bool VadHasSpeech(IntPtr vad);

    ZzStatus Sha256File(string path, out string? hexDigest);
}
