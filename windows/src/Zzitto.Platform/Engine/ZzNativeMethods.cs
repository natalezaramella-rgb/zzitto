// P/Invoke verso zz_engine.dll: tutte le funzioni del contratto (core-native/include/zz_engine.h, API v2).
// Spec §4.3. Aggiunto il 22/10/2026 alle 11:32; API 2 (dettatura in streaming, Spec §11) aggiunta il 22/10/2026 alle 16:15.
//
// Regole di marshalling:
//  - solo tipi blittabili; le stringhe UTF-8 si convertono a mano (Marshal.StringToCoTaskMemUTF8 /
//    Marshal.PtrToStringUTF8), perché il contratto è UTF-8 e non ANSI;
//  - le strutture rispecchiano l'allineamento naturale di MSVC su x64 e ARM64
//    (dimensioni attese: config 56, options 32, result 48 byte; verificate dai test).
#pragma warning disable CS0649 // campi scritti dal codice nativo
using System.Runtime.InteropServices;

namespace Zzitto.Platform.Engine;

[StructLayout(LayoutKind.Sequential)]
internal struct ZzEngineConfigNative
{
    public uint StructSize;
    public IntPtr WhisperModelPath;
    public IntPtr LlmModelPath;
    public IntPtr WhisperSha256;
    public IntPtr LlmSha256;
    public int NThreads;
    public int UseGpu;
    public int SequentialLoading;
}

[StructLayout(LayoutKind.Sequential)]
internal struct ZzDictationOptionsNative
{
    public uint StructSize;
    public IntPtr Language;
    public int Mode;
    public int Translate;
    public int VoiceCommandsEnabled;
    public int CorrectionTimeoutMs;
}

[StructLayout(LayoutKind.Sequential)]
internal struct ZzResultNative
{
    public IntPtr RawText;
    public IntPtr FinalText;
    public uint Actions;
    public int UsedFallback;
    public int FromShortcut;
    public long MsTranscribe;
    public long MsCorrect;
}

internal static unsafe class ZzNativeMethods
{
    public const string LibraryName = "zz_engine";

    public const uint ExpectedApiVersion = 2; // ZZ_ENGINE_API_VERSION (2: streaming, 22/10/2026 16:15)

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern uint zz_api_version();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern IntPtr zz_version_string();

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern IntPtr zz_status_string(int status);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_engine_config_init(ZzEngineConfigNative* config);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_dictation_options_init(ZzDictationOptionsNative* options);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_engine_create(ZzEngineConfigNative* config, IntPtr* outEngine);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_engine_destroy(IntPtr engine);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_engine_load(IntPtr engine);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_engine_unload(IntPtr engine);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_engine_is_loaded(IntPtr engine);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_engine_set_dictionary(IntPtr engine, IntPtr* words, nuint count);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_engine_set_shortcuts(IntPtr engine, IntPtr* keywords, IntPtr* expansions, nuint count);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_dictate(IntPtr engine, float* pcm, nuint nSamples, ZzDictationOptionsNative* options,
        delegate* unmanaged[Cdecl]<int, IntPtr, void> onStage, IntPtr userData, ZzResultNative* outResult);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_rewrite(IntPtr engine, IntPtr selectedText, float* pcm, nuint nSamples,
        ZzDictationOptionsNative* options, delegate* unmanaged[Cdecl]<int, IntPtr, void> onStage, IntPtr userData,
        ZzResultNative* outResult);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_process_text(IntPtr engine, IntPtr rawText, ZzDictationOptionsNative* options,
        ZzResultNative* outResult);

    // ---- Dettatura in streaming (API 2, Spec §11) ----

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_stream_begin(IntPtr engine, ZzDictationOptionsNative* options, IntPtr* outStream);

    // Non bloccante: copia i campioni e ritorna; chiamata dal thread di cattura WASAPI.
    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_stream_feed(IntPtr stream, float* pcm, nuint nSamples);

    // Bloccante; libera sempre il flusso.
    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_stream_finish(IntPtr stream, delegate* unmanaged[Cdecl]<int, IntPtr, void> onStage,
        IntPtr userData, ZzResultNative* outResult);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_stream_abort(IntPtr stream);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_cancel(IntPtr engine);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_result_free(ZzResultNative* result);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern IntPtr zz_vad_create(int silenceMs);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_vad_destroy(IntPtr vad);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern void zz_vad_reset(IntPtr vad);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_vad_process(IntPtr vad, float* pcm, nuint nSamples);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_vad_has_speech(IntPtr vad);

    [DllImport(LibraryName, CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    public static extern int zz_sha256_file(IntPtr path, byte* outHex);
}
