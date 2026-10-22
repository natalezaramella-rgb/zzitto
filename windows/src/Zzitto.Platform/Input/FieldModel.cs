// Modello del campo di testo attivo, indipendente da UI Automation (così la logica si testa, Spec §12).
// Spec §2.4, §2.6. Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Input;

/// <summary>Rettangolo in pixel fisici dello schermo.</summary>
public readonly record struct ScreenRect(int Left, int Top, int Width, int Height)
{
    public int Right => Left + Width;

    public int Bottom => Top + Height;

    public bool IsEmpty => Width <= 0 || Height <= 0;

    public static ScreenRect FromLtrb(int left, int top, int right, int bottom) =>
        new(left, top, right - left, bottom - top);

    public bool Contains(int x, int y) => x >= Left && x < Right && y >= Top && y < Bottom;
}

/// <summary>Caratteristiche del controllo con il focus, lette con UI Automation.</summary>
public sealed record FieldFacts(
    int ControlType,
    string FrameworkId,
    string ClassName,
    bool IsPassword,
    bool IsReadOnly,
    bool HasValuePattern,
    bool HasTextPattern,
    int ProcessId)
{
    public const int EditControlType = 50004;
    public const int DocumentControlType = 50030;

    public bool IsEdit => ControlType == EditControlType;

    public bool IsDocument => ControlType == DocumentControlType;

    /// <summary>Il controllo accetta testo (campo o documento modificabile).</summary>
    public bool IsEditable => !IsReadOnly && (IsEdit || IsDocument || HasValuePattern || HasTextPattern);
}

/// <summary>Posizione del cursore di testo o del controllo, per l'icona flottante (Spec §2.6).</summary>
public sealed record FieldAnchor(ScreenRect? Caret, ScreenRect? Element, bool IsEditable, int ProcessId);
