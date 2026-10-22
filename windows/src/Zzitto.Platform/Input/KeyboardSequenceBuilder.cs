// Sequenze di tasti per SendInput: testo Unicode, Backspace, Invio, Ctrl+C/Ctrl+V.
// Spec §2.4 (fallback SendInput), §5.4/§5.9 (annulla, cancella frase, invia). Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Input;

/// <summary>Un evento di tastiera: tasto virtuale oppure carattere Unicode, premuto o rilasciato.</summary>
public readonly record struct KeyStroke(ushort VirtualKey, char Unicode, bool KeyUp)
{
    public bool IsUnicode => VirtualKey == 0;

    public static KeyStroke Down(ushort vk) => new(vk, '\0', false);

    public static KeyStroke Up(ushort vk) => new(vk, '\0', true);

    public static KeyStroke UnicodeDown(char c) => new(0, c, false);

    public static KeyStroke UnicodeUp(char c) => new(0, c, true);
}

public static class KeyboardSequenceBuilder
{
    public const ushort VkBack = 0x08;
    public const ushort VkTab = 0x09;
    public const ushort VkReturn = 0x0D;
    public const ushort VkShift = 0x10;
    public const ushort VkControl = 0x11;

    /// <summary>
    /// Digita il testo come caratteri Unicode (KEYEVENTF_UNICODE), indipendente dal layout di tastiera.
    /// Gli a capo diventano Maiusc+Invio, così nelle chat non si invia il messaggio per errore;
    /// le tabulazioni diventano il tasto Tab.
    /// </summary>
    public static IReadOnlyList<KeyStroke> ForText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var strokes = new List<KeyStroke>(text.Length * 2);
        for (int i = 0; i < text.Length; i++)
        {
            char c = text[i];
            switch (c)
            {
                case '\r':
                    if (i + 1 < text.Length && text[i + 1] == '\n')
                    {
                        continue; // "\r\n" → un solo a capo
                    }

                    AddNewLine(strokes);
                    break;
                case '\n':
                    AddNewLine(strokes);
                    break;
                case '\t':
                    strokes.Add(KeyStroke.Down(VkTab));
                    strokes.Add(KeyStroke.Up(VkTab));
                    break;
                default:
                    // Le coppie surrogate si inviano come due unità UTF-16 consecutive (supportato da Windows).
                    strokes.Add(KeyStroke.UnicodeDown(c));
                    strokes.Add(KeyStroke.UnicodeUp(c));
                    break;
            }
        }

        return strokes;
    }

    public static IReadOnlyList<KeyStroke> Repeat(ushort vk, int count)
    {
        if (count < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(count));
        }

        var strokes = new List<KeyStroke>(count * 2);
        for (int i = 0; i < count; i++)
        {
            strokes.Add(KeyStroke.Down(vk));
            strokes.Add(KeyStroke.Up(vk));
        }

        return strokes;
    }

    public static IReadOnlyList<KeyStroke> Chord(ushort modifier, ushort vk) => new[]
    {
        KeyStroke.Down(modifier),
        KeyStroke.Down(vk),
        KeyStroke.Up(vk),
        KeyStroke.Up(modifier),
    };

    private static void AddNewLine(List<KeyStroke> strokes)
    {
        strokes.Add(KeyStroke.Down(VkShift));
        strokes.Add(KeyStroke.Down(VkReturn));
        strokes.Add(KeyStroke.Up(VkReturn));
        strokes.Add(KeyStroke.Up(VkShift));
    }
}
