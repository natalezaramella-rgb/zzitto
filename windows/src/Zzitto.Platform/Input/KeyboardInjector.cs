// Invio di tasti simulati con SendInput. Spec §2.4 (fallback SendInput), §2.6. Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Input;

internal static unsafe class KeyboardInjector
{
    /// <summary>Marca gli eventi generati da Zzitto (dwExtraInfo), utile per riconoscerli.</summary>
    public static readonly IntPtr ZzittoInputTag = new(0x5A5A1770);

    private const int ChunkSize = 256;

    /// <summary>Invia la sequenza; false se il sistema ne ha accettata solo una parte.</summary>
    public static bool Send(IReadOnlyList<KeyStroke> strokes)
    {
        ArgumentNullException.ThrowIfNull(strokes);
        if (strokes.Count == 0)
        {
            return true;
        }

        INPUT[] inputs = new INPUT[strokes.Count];
        for (int i = 0; i < strokes.Count; i++)
        {
            inputs[i] = ToInput(strokes[i]);
        }

        try
        {
            for (int offset = 0; offset < inputs.Length; offset += ChunkSize)
            {
                int count = Math.Min(ChunkSize, inputs.Length - offset);
                fixed (INPUT* p = &inputs[offset])
                {
                    uint sent = Win32.SendInput((uint)count, p, sizeof(INPUT));
                    if (sent != (uint)count)
                    {
                        return false;
                    }
                }
            }

            return true;
        }
        finally
        {
            Array.Clear(inputs); // Spec §10: il testo non resta in memoria
        }
    }

    /// <summary>
    /// Attende che l'utente rilasci Ctrl, Alt, Maiusc e Win (es. dopo la scorciatoia globale),
    /// altrimenti i tasti simulati diventerebbero combinazioni.
    /// </summary>
    public static async Task WaitForModifiersReleasedAsync(TimeSpan timeout, CancellationToken cancellationToken)
    {
        DateTime limit = DateTime.UtcNow + timeout;
        while (AnyModifierDown() && DateTime.UtcNow < limit)
        {
            await Task.Delay(15, cancellationToken).ConfigureAwait(false);
        }
    }

    public static bool AnyModifierDown() =>
        IsDown(Win32.VK_CONTROL) || IsDown(Win32.VK_MENU) || IsDown(Win32.VK_SHIFT)
        || IsDown(Win32.VK_LWIN) || IsDown(Win32.VK_RWIN);

    private static bool IsDown(int vk) => (Win32.GetAsyncKeyState(vk) & 0x8000) != 0;

    private static INPUT ToInput(KeyStroke stroke)
    {
        var input = new INPUT { Type = Win32.INPUT_KEYBOARD };
        if (stroke.IsUnicode)
        {
            input.Data.Keyboard = new KEYBDINPUT
            {
                Vk = 0,
                Scan = stroke.Unicode,
                Flags = Win32.KEYEVENTF_UNICODE | (stroke.KeyUp ? Win32.KEYEVENTF_KEYUP : 0),
                ExtraInfo = ZzittoInputTag,
            };
        }
        else
        {
            input.Data.Keyboard = new KEYBDINPUT
            {
                Vk = stroke.VirtualKey,
                Scan = 0,
                Flags = stroke.KeyUp ? Win32.KEYEVENTF_KEYUP : 0,
                ExtraInfo = ZzittoInputTag,
            };
        }

        return input;
    }
}
