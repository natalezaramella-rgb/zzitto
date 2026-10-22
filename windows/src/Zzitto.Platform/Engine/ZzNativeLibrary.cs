// Implementazione reale di IZzNative: P/Invoke verso zz_engine.dll con marshalling UTF-8.
// Spec §4.3, §10 (nessun testo o audio nei log). Aggiunto il 22/10/2026 alle 11:32.
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;

namespace Zzitto.Platform.Engine;

public sealed unsafe class ZzNativeLibrary : IZzNative
{
    public uint ApiVersion() => ZzNativeMethods.zz_api_version();

    public string VersionString() => Marshal.PtrToStringUTF8(ZzNativeMethods.zz_version_string()) ?? string.Empty;

    public string StatusString(ZzStatus status) =>
        Marshal.PtrToStringUTF8(ZzNativeMethods.zz_status_string((int)status)) ?? status.ToString();

    public ZzStatus CreateEngine(EngineConfig config, out IntPtr engine)
    {
        ArgumentNullException.ThrowIfNull(config);
        using var strings = new Utf8StringBag();
        ZzEngineConfigNative native;
        ZzNativeMethods.zz_engine_config_init(&native);
        native.StructSize = (uint)sizeof(ZzEngineConfigNative);
        native.WhisperModelPath = strings.Add(config.WhisperModelPath);
        native.LlmModelPath = strings.Add(config.LlmModelPath);
        native.WhisperSha256 = strings.Add(config.WhisperSha256);
        native.LlmSha256 = strings.Add(config.LlmSha256);
        native.NThreads = config.Threads;
        native.UseGpu = config.UseGpu ? 1 : 0;
        native.SequentialLoading = config.SequentialLoading ? 1 : 0;

        IntPtr handle = IntPtr.Zero;
        var status = (ZzStatus)ZzNativeMethods.zz_engine_create(&native, &handle);
        engine = handle;
        return status;
    }

    public void DestroyEngine(IntPtr engine) => ZzNativeMethods.zz_engine_destroy(engine);

    public ZzStatus Load(IntPtr engine) => (ZzStatus)ZzNativeMethods.zz_engine_load(engine);

    public ZzStatus Unload(IntPtr engine) => (ZzStatus)ZzNativeMethods.zz_engine_unload(engine);

    public bool IsLoaded(IntPtr engine) => ZzNativeMethods.zz_engine_is_loaded(engine) != 0;

    public ZzStatus SetDictionary(IntPtr engine, IReadOnlyList<string> words)
    {
        ArgumentNullException.ThrowIfNull(words);
        using var strings = new Utf8StringBag();
        var pointers = new IntPtr[words.Count];
        for (int i = 0; i < words.Count; i++)
        {
            pointers[i] = strings.Add(words[i]);
        }

        fixed (IntPtr* p = pointers)
        {
            return (ZzStatus)ZzNativeMethods.zz_engine_set_dictionary(engine, words.Count == 0 ? null : p, (nuint)words.Count);
        }
    }

    public ZzStatus SetShortcuts(IntPtr engine, IReadOnlyList<KeyValuePair<string, string>> shortcuts)
    {
        ArgumentNullException.ThrowIfNull(shortcuts);
        using var strings = new Utf8StringBag();
        var keys = new IntPtr[shortcuts.Count];
        var values = new IntPtr[shortcuts.Count];
        for (int i = 0; i < shortcuts.Count; i++)
        {
            keys[i] = strings.Add(shortcuts[i].Key);
            values[i] = strings.Add(shortcuts[i].Value);
        }

        fixed (IntPtr* k = keys)
        fixed (IntPtr* v = values)
        {
            bool empty = shortcuts.Count == 0;
            return (ZzStatus)ZzNativeMethods.zz_engine_set_shortcuts(engine, empty ? null : k, empty ? null : v, (nuint)shortcuts.Count);
        }
    }

    public ZzStatus Dictate(IntPtr engine, ReadOnlySpan<float> pcm16kMono, DictationOptions options,
        Action<EngineStage>? onStage, out EngineResult? result)
    {
        return Run(engine, null, pcm16kMono, options, onStage, rewrite: false, out result);
    }

    public ZzStatus Rewrite(IntPtr engine, string selectedText, ReadOnlySpan<float> pcm16kMono, DictationOptions options,
        Action<EngineStage>? onStage, out EngineResult? result)
    {
        ArgumentNullException.ThrowIfNull(selectedText);
        return Run(engine, selectedText, pcm16kMono, options, onStage, rewrite: true, out result);
    }

    public ZzStatus ProcessText(IntPtr engine, string rawText, DictationOptions options, out EngineResult? result)
    {
        ArgumentNullException.ThrowIfNull(rawText);
        using var strings = new Utf8StringBag();
        ZzDictationOptionsNative nativeOptions = BuildOptions(options, strings);
        IntPtr raw = strings.Add(rawText);
        ZzResultNative nativeResult = default;
        var status = (ZzStatus)ZzNativeMethods.zz_process_text(engine, raw, &nativeOptions, &nativeResult);
        result = TakeResult(status, &nativeResult);
        return status;
    }

    public void Cancel(IntPtr engine) => ZzNativeMethods.zz_cancel(engine);

    public IntPtr CreateVad(int silenceMs) => ZzNativeMethods.zz_vad_create(silenceMs);

    public void DestroyVad(IntPtr vad) => ZzNativeMethods.zz_vad_destroy(vad);

    public void ResetVad(IntPtr vad) => ZzNativeMethods.zz_vad_reset(vad);

    public bool ProcessVad(IntPtr vad, ReadOnlySpan<float> pcm16kMono)
    {
        fixed (float* p = pcm16kMono)
        {
            return ZzNativeMethods.zz_vad_process(vad, p, (nuint)pcm16kMono.Length) != 0;
        }
    }

    public bool VadHasSpeech(IntPtr vad) => ZzNativeMethods.zz_vad_has_speech(vad) != 0;

    public ZzStatus Sha256File(string path, out string? hexDigest)
    {
        ArgumentNullException.ThrowIfNull(path);
        using var strings = new Utf8StringBag();
        IntPtr nativePath = strings.Add(path);
        byte* buffer = stackalloc byte[65];
        var status = (ZzStatus)ZzNativeMethods.zz_sha256_file(nativePath, buffer);
        hexDigest = status == ZzStatus.Ok ? Marshal.PtrToStringUTF8((IntPtr)buffer) : null;
        return status;
    }

    private static ZzStatus Run(IntPtr engine, string? selectedText, ReadOnlySpan<float> pcm, DictationOptions options,
        Action<EngineStage>? onStage, bool rewrite, out EngineResult? result)
    {
        using var strings = new Utf8StringBag();
        ZzDictationOptionsNative nativeOptions = BuildOptions(options, strings);
        ZzResultNative nativeResult = default;

        // La callback nativa riceve un GCHandle verso il delegato gestito (Spec §5.2: stadi Trascrizione/Correzione).
        GCHandle callbackHandle = onStage is null ? default : GCHandle.Alloc(onStage);
        try
        {
            IntPtr userData = onStage is null ? IntPtr.Zero : GCHandle.ToIntPtr(callbackHandle);
            delegate* unmanaged[Cdecl]<int, IntPtr, void> callback = null;
            if (onStage is not null)
            {
                callback = &OnStageNative;
            }

            int status;
            fixed (float* p = pcm)
            {
                status = rewrite
                    ? ZzNativeMethods.zz_rewrite(engine, strings.Add(selectedText), p, (nuint)pcm.Length, &nativeOptions,
                        callback, userData, &nativeResult)
                    : ZzNativeMethods.zz_dictate(engine, p, (nuint)pcm.Length, &nativeOptions, callback, userData,
                        &nativeResult);
            }

            result = TakeResult((ZzStatus)status, &nativeResult);
            return (ZzStatus)status;
        }
        finally
        {
            if (callbackHandle.IsAllocated)
            {
                callbackHandle.Free();
            }
        }
    }

    [UnmanagedCallersOnly(CallConvs = new[] { typeof(CallConvCdecl) })]
    private static void OnStageNative(int stage, IntPtr userData)
    {
        // Le eccezioni non devono mai attraversare il confine nativo.
        try
        {
            if (userData != IntPtr.Zero && GCHandle.FromIntPtr(userData).Target is Action<EngineStage> callback)
            {
                callback((EngineStage)stage);
            }
        }
        catch (Exception)
        {
            // Ignorata: un errore dell'interfaccia non deve interrompere il motore.
        }
    }

    private static ZzDictationOptionsNative BuildOptions(DictationOptions options, Utf8StringBag strings)
    {
        ArgumentNullException.ThrowIfNull(options);
        ZzDictationOptionsNative native;
        ZzNativeMethods.zz_dictation_options_init(&native);
        native.StructSize = (uint)sizeof(ZzDictationOptionsNative);
        native.Language = strings.Add(options.Language);
        native.Mode = (int)options.Mode;
        native.Translate = (int)options.Translate;
        native.VoiceCommandsEnabled = options.VoiceCommandsEnabled ? 1 : 0;
        native.CorrectionTimeoutMs = options.CorrectionTimeoutMs;
        return native;
    }

    private static EngineResult? TakeResult(ZzStatus status, ZzResultNative* native)
    {
        try
        {
            if (status != ZzStatus.Ok)
            {
                return null;
            }

            return new EngineResult(
                Marshal.PtrToStringUTF8(native->RawText) ?? string.Empty,
                Marshal.PtrToStringUTF8(native->FinalText) ?? string.Empty,
                (EngineActions)native->Actions,
                native->UsedFallback != 0,
                native->FromShortcut != 0,
                TimeSpan.FromMilliseconds(native->MsTranscribe),
                TimeSpan.FromMilliseconds(native->MsCorrect));
        }
        finally
        {
            // zz_result_free accetta anche un risultato azzerato (puntatori NULL).
            ZzNativeMethods.zz_result_free(native);
        }
    }

    /// <summary>Stringhe UTF-8 allocate per la durata di una chiamata nativa.</summary>
    private sealed class Utf8StringBag : IDisposable
    {
        private readonly List<IntPtr> _allocations = new();

        public IntPtr Add(string? value)
        {
            if (value is null)
            {
                return IntPtr.Zero;
            }

            IntPtr p = Marshal.StringToCoTaskMemUTF8(value);
            _allocations.Add(p);
            return p;
        }

        public void Dispose()
        {
            foreach (IntPtr p in _allocations)
            {
                Marshal.ZeroFreeCoTaskMemUTF8(p); // Spec §10: azzera anche i testi dettati
            }

            _allocations.Clear();
        }
    }
}
