// Impostazioni dell'app (Spec §5.7, §2.4, §2.6, §4.2, §5.6, §5.9, §5.12). Aggiunto il 22/10/2026 alle 11:32.
// Non contengono testo dettato: si salvano in chiaro. Dizionario, scorciatoie e cronologia sono cifrati
// a parte (Spec §5.6, §5.11, §10).
using System.Text.Json.Serialization;
using Zzitto.Platform.Engine;

namespace Zzitto.Platform.Storage;

/// <summary>Comportamento dell'icona flottante (Spec §2.4, §2.6).</summary>
public enum OverlayMode
{
    /// <summary>Vicino al campo attivo; se l'app non lo espone, nell'ultima posizione scelta.</summary>
    FollowField = 0,

    /// <summary>Sempre nell'ultima posizione scelta dall'utente.</summary>
    FixedPosition = 1,

    /// <summary>Nascosta: restano l'area di notifica e la scorciatoia globale.</summary>
    Hidden = 2,
}

[Flags]
public enum HotkeyModifiers : uint
{
    None = 0,
    Alt = 0x0001,     // MOD_ALT
    Control = 0x0002, // MOD_CONTROL
    Shift = 0x0004,   // MOD_SHIFT
    Win = 0x0008,     // MOD_WIN
}

/// <summary>Scorciatoia da tastiera globale (Spec §2.4).</summary>
public sealed record HotkeyBinding(HotkeyModifiers Modifiers, uint VirtualKey)
{
    /// <summary>Predefinita: Ctrl+Alt+Spazio.</summary>
    public static HotkeyBinding Default { get; } = new(HotkeyModifiers.Control | HotkeyModifiers.Alt, 0x20);

    /// <summary>Una scorciatoia globale valida richiede almeno un modificatore diverso da Maiusc.</summary>
    [JsonIgnore]
    public bool IsValid => VirtualKey is > 0 and < 0xFF
        && (Modifiers & (HotkeyModifiers.Control | HotkeyModifiers.Alt | HotkeyModifiers.Win)) != 0
        && !IsModifierKey(VirtualKey);

    public static bool IsModifierKey(uint vk) => vk is 0x10 or 0x11 or 0x12 or 0x5B or 0x5C
        or 0xA0 or 0xA1 or 0xA2 or 0xA3 or 0xA4 or 0xA5;

    /// <summary>Testo leggibile, es. "Ctrl+Alt+Spazio".</summary>
    public override string ToString()
    {
        var parts = new List<string>(5);
        if (Modifiers.HasFlag(HotkeyModifiers.Control))
        {
            parts.Add("Ctrl");
        }

        if (Modifiers.HasFlag(HotkeyModifiers.Alt))
        {
            parts.Add("Alt");
        }

        if (Modifiers.HasFlag(HotkeyModifiers.Shift))
        {
            parts.Add("Maiusc");
        }

        if (Modifiers.HasFlag(HotkeyModifiers.Win))
        {
            parts.Add("Win");
        }

        parts.Add(KeyName(VirtualKey));
        return string.Join("+", parts);
    }

    public static string KeyName(uint vk) => vk switch
    {
        0x20 => "Spazio",
        0x0D => "Invio",
        0x09 => "Tab",
        0x1B => "Esc",
        0x08 => "Backspace",
        0x2D => "Ins",
        0x2E => "Canc",
        0x24 => "Home",
        0x23 => "Fine",
        0x21 => "PagSu",
        0x22 => "PagGiù",
        0x25 => "Sinistra",
        0x26 => "Su",
        0x27 => "Destra",
        0x28 => "Giù",
        0x13 => "Pausa",
        0x91 => "BlocScorr",
        >= 0x30 and <= 0x39 => ((char)vk).ToString(),
        >= 0x41 and <= 0x5A => ((char)vk).ToString(),
        >= 0x60 and <= 0x69 => "Num" + (vk - 0x60),
        >= 0x70 and <= 0x87 => "F" + (vk - 0x6F),
        _ => "0x" + vk.ToString("X2", System.Globalization.CultureInfo.InvariantCulture),
    };
}

public sealed record ZzittoSettings
{
    public const int CurrentVersion = 1;

    public int Version { get; init; } = CurrentVersion;

    // ---- Dettatura (Spec §5.7) ----
    public string Language { get; init; } = "it";

    public CorrectionMode Mode { get; init; } = CorrectionMode.Clean;

    public TranslateTarget Translate { get; init; } = TranslateTarget.None;

    /// <summary>Silenzio che ferma l'ascolto (Spec §5.2, predefinito 2 s).</summary>
    public int SilenceTimeoutMs { get; init; } = 2000;

    /// <summary>Durata massima di una dettatura (Spec §5.2, predefinita 5 minuti).</summary>
    public int MaxDurationSeconds { get; init; } = 300;

    public bool StartSound { get; init; } = true;

    public bool VoiceCommandsEnabled { get; init; } = true;

    public int CorrectionTimeoutMs { get; init; } = 10000;

    /// <summary>Spec §5.10: per leggere la selezione si può usare una copia temporanea negli appunti.</summary>
    public bool ClipboardSelectionFallback { get; init; } = true;

    // ---- Cronologia (Spec §5.6) ----
    public bool HistoryEnabled { get; init; } = true;

    public int HistoryRetentionDays { get; init; } = 30;

    public int HistoryMaxEntries { get; init; } = 200;

    // ---- App residente (Spec §2.4, §2.6) ----
    public HotkeyBinding Hotkey { get; init; } = HotkeyBinding.Default;

    public OverlayMode OverlayMode { get; init; } = OverlayMode.FollowField;

    /// <summary>Ultima posizione scelta dall'utente per l'icona (pixel fisici), null = non ancora scelta.</summary>
    public int? OverlayX { get; init; }

    public int? OverlayY { get; init; }

    // ---- Motore (Spec §4.2, §2.4) ----
    public int ModelUnloadMinutes { get; init; } = 5;

    public bool UseGpu { get; init; } = true;

    // ---- Tour (Spec §5.8) ----
    public bool TourCompleted { get; init; }

    public static IReadOnlyList<string> SupportedLanguages { get; } = new[] { "it", "en", "es", "fr", "de" };

    /// <summary>Riporta i valori negli intervalli ammessi (file modificati a mano o versioni precedenti).</summary>
    public ZzittoSettings Normalize() => this with
    {
        Version = CurrentVersion,
        Language = SupportedLanguages.Contains(Language) ? Language : "it",
        Mode = Enum.IsDefined(Mode) ? Mode : CorrectionMode.Clean,
        Translate = Enum.IsDefined(Translate) ? Translate : TranslateTarget.None,
        SilenceTimeoutMs = Math.Clamp(SilenceTimeoutMs, 500, 10000),
        MaxDurationSeconds = Math.Clamp(MaxDurationSeconds, 10, 900),
        CorrectionTimeoutMs = Math.Clamp(CorrectionTimeoutMs, 1000, 60000),
        HistoryRetentionDays = Math.Clamp(HistoryRetentionDays, 1, 365),
        HistoryMaxEntries = Math.Clamp(HistoryMaxEntries, 10, 1000),
        Hotkey = Hotkey is { IsValid: true } ? Hotkey : HotkeyBinding.Default,
        OverlayMode = Enum.IsDefined(OverlayMode) ? OverlayMode : OverlayMode.FollowField,
        ModelUnloadMinutes = Math.Clamp(ModelUnloadMinutes, 1, 120),
    };

    public DictationOptions ToDictationOptions() =>
        new(Language, Mode, Translate, VoiceCommandsEnabled, CorrectionTimeoutMs);
}
