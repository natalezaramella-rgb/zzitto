// Spec §7.1: stessi hash del §7. Aggiornati da tools/models/prepare-models.* (agente E).
// Modelli inclusi nel pacchetto MSIX nella cartella Models/ (sola lettura, cartella di installazione).
// Aggiunto il 22/10/2026 alle 11:32. Formato dei marcatori: tools/models/README.md.
// Attenzione: il segnaposto testuale degli hash deve comparire SOLO nelle tre assegnazioni marcate,
// perché lo script di rilascio verifica che non resti nel file.
namespace Zzitto.Platform.Engine;

public static class ModelManifest
{
    public const string ModelsFolder = "Models";

    public const string WhisperFile = "whisper-medium-q5_0.bin";

    // zz-model-hash: whisper-medium-q5_0.bin
    public const string WhisperSha256 = "DA_IMPOSTARE";

    // zz-model-size: whisper-medium-q5_0.bin
    public const long WhisperSizeBytes = 0;

    // Spec §11 (22/10/2026 22:58): Whisper small è il modello predefinito (più veloce); medium resta con
    // l'opzione "Massima precisione".
    public const string WhisperSmallFile = "whisper-small-q5_1.bin";

    // zz-model-hash: whisper-small-q5_1.bin
    public const string WhisperSmallSha256 = "DA_IMPOSTARE";

    // zz-model-size: whisper-small-q5_1.bin
    public const long WhisperSmallSizeBytes = 0;

    public const string LlmFile = "phi-4-mini-instruct-q4_k_m.gguf";

    // zz-model-hash: phi-4-mini-instruct-q4_k_m.gguf
    public const string LlmSha256 = "DA_IMPOSTARE";

    // zz-model-size: phi-4-mini-instruct-q4_k_m.gguf
    public const long LlmSizeBytes = 0;

    // zz-models-version
    public const string ModelsVersion = "DA_IMPOSTARE";

    /// <summary>Valore iniziale degli hash prima dello script di rilascio (composto per non ripetere il letterale).</summary>
    public const string Placeholder = "DA_" + "IMPOSTARE";

    /// <summary>true quando entrambi gli hash sono stati impostati dallo script di rilascio.</summary>
    public static bool HashesConfigured =>
        IsValidSha256(WhisperSha256) && IsValidSha256(WhisperSmallSha256) && IsValidSha256(LlmSha256);

    public static string WhisperPath(string installRoot) => Path.Combine(installRoot, ModelsFolder, WhisperFile);

    public static string WhisperSmallPath(string installRoot) => Path.Combine(installRoot, ModelsFolder, WhisperSmallFile);

    public static string LlmPath(string installRoot) => Path.Combine(installRoot, ModelsFolder, LlmFile);

    /// <summary>
    /// Spec §7, tools/models/README.md: finché un hash non è impostato i modelli sono "non verificati" e il
    /// motore reale non li deve caricare. Solo il motore finto (Spec §16.1) può funzionare senza hash.
    /// </summary>
    public static bool CanUseEngine(string engineVersion) =>
        HashesConfigured || engineVersion.Contains("zzitto-engine MOCK", StringComparison.Ordinal);

    /// <summary>
    /// Configurazione del motore per i modelli del pacchetto. Gli hash non impostati passano come NULL
    /// (ammesso dal contratto solo per il motore finto: vedi <see cref="CanUseEngine"/>).
    /// </summary>
    /// <param name="maxPrecision">Spec §5.7, §11 (22/10/2026 22:58): true = Whisper medium, false = small (predefinito).</param>
    public static EngineConfig CreateConfig(string installRoot, bool useGpu, bool maxPrecision = false)
    {
        ArgumentException.ThrowIfNullOrEmpty(installRoot);
        return new EngineConfig(
            maxPrecision ? WhisperPath(installRoot) : WhisperSmallPath(installRoot),
            LlmPath(installRoot),
            NormalizeHash(maxPrecision ? WhisperSha256 : WhisperSmallSha256),
            NormalizeHash(LlmSha256),
            Threads: 0,
            UseGpu: useGpu,
            SequentialLoading: false); // Spec §7, §11: dal 22/10/2026 entrambi i modelli in RAM (reattività)
    }

    public static string? NormalizeHash(string value) =>
        IsValidSha256(value) ? value.ToLowerInvariant() : null;

    public static bool IsValidSha256(string? value)
    {
        if (value is null || value.Length != 64)
        {
            return false;
        }

        foreach (char c in value)
        {
            if (!Uri.IsHexDigit(c))
            {
                return false;
            }
        }

        return true;
    }
}
