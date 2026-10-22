// Tipi gestiti che rispecchiano il contratto del motore (core-native/include/zz_engine.h).
// Spec §4.3, §5.2–§5.12. Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Engine;

/// <summary>Codici di stato di zz_status (valori identici al contratto).</summary>
public enum ZzStatus
{
    Ok = 0,
    InvalidArgument = 1,
    NotLoaded = 2,
    ModelLoad = 3,
    ModelIntegrity = 4,   // Spec §7: hash SHA-256 non corrispondente
    OutOfMemory = 5,
    Cancelled = 6,
    Timeout = 7,
    Busy = 8,
    NoSpeech = 9,
    Internal = 99,
}

/// <summary>Stadi notificati dal motore durante zz_dictate/zz_rewrite (Spec §5.2).</summary>
public enum EngineStage
{
    Transcribing = 1,
    Correcting = 2,
    Done = 3,
}

/// <summary>Modalità di correzione (Spec §5.3).</summary>
public enum CorrectionMode
{
    Faithful = 0,
    Clean = 1,
    Formal = 2,
}

/// <summary>Traduzione offline (Spec §5.12).</summary>
public enum TranslateTarget
{
    None = 0,
    English = 1,
    Spanish = 2,
}

/// <summary>Azioni che la piattaforma esegue dopo l'inserimento (Spec §5.9, ZZ_ACTION_*).</summary>
[Flags]
public enum EngineActions : uint
{
    None = 0,
    UndoLastInsert = 1u << 0,
    DeletePreviousSentence = 1u << 1,
    Send = 1u << 2,
}

/// <summary>Configurazione del motore (zz_engine_config). Spec §7, §7.1.</summary>
public sealed record EngineConfig(
    string WhisperModelPath,
    string LlmModelPath,
    string? WhisperSha256,
    string? LlmSha256,
    int Threads = 0,
    bool UseGpu = true,
    bool SequentialLoading = true);

/// <summary>Opzioni di una dettatura (zz_dictation_options). Spec §5.3, §5.9, §5.12.</summary>
public sealed record DictationOptions(
    string Language = "it",
    CorrectionMode Mode = CorrectionMode.Clean,
    TranslateTarget Translate = TranslateTarget.None,
    bool VoiceCommandsEnabled = true,
    int CorrectionTimeoutMs = 10000);

/// <summary>Risultato di una dettatura (copia gestita di zz_result; la memoria nativa è già liberata).</summary>
public sealed record EngineResult(
    string RawText,
    string FinalText,
    EngineActions Actions,
    bool UsedFallback,
    bool FromShortcut,
    TimeSpan TranscribeTime,
    TimeSpan CorrectTime);

/// <summary>Errore restituito dal motore.</summary>
public sealed class ZzEngineException : Exception
{
    public ZzEngineException(ZzStatus status, string message)
        : base(message)
    {
        Status = status;
    }

    public ZzStatus Status { get; }
}
