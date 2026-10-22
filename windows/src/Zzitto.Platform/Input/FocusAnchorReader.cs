// Lettura della posizione del campo attivo per l'icona flottante. Spec §2.6. Aggiunto il 22/10/2026 alle 11:32.
//
// Ordine: cursore Win32 (GetGUIThreadInfo, veloce e affidabile nelle app classiche) → cursore UI Automation
// (TextPattern2) → rettangolo del controllo. Se nulla è disponibile si restituisce null e l'icona resta
// nell'ultima posizione scelta dall'utente.
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Input;

public sealed class FocusAnchorReader
{
    private readonly UiaClient _uia = new();

    /// <summary>Da chiamare su un thread del pool (MTA), mai dal thread di un'interfaccia.</summary>
    public FieldAnchor? Read()
    {
        (IntPtr window, uint threadId, uint processId) = ProcessInfo.Foreground();
        if (window == IntPtr.Zero)
        {
            return null;
        }

        if (processId == (uint)Environment.ProcessId || ProcessInfo.IsElevatedOrInaccessible(processId))
        {
            // Finestre di Zzitto o elevate (UIPI): nessuna informazione affidabile (Spec §2.6).
            return new FieldAnchor(null, null, false, (int)processId);
        }

        ScreenRect? caret = ReadWin32Caret(threadId);
        UiaField? field = _uia.GetFocusedField();
        if (field is null)
        {
            return caret is null ? null : new FieldAnchor(caret, null, true, (int)processId);
        }

        caret ??= field.GetCaretRectangle();
        return new FieldAnchor(caret, field.BoundingRectangle, field.Facts.IsEditable, (int)processId);
    }

    private static ScreenRect? ReadWin32Caret(uint threadId)
    {
        var info = new GUITHREADINFO { CbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<GUITHREADINFO>() };
        if (!Win32.GetGUIThreadInfo(threadId, ref info) || info.HwndCaret == IntPtr.Zero)
        {
            return null;
        }

        RECT rect = info.RcCaret;
        if (rect.Bottom <= rect.Top)
        {
            return null;
        }

        Win32.MapWindowPoints(info.HwndCaret, IntPtr.Zero, ref rect, 2);
        return ScreenRect.FromLtrb(rect.Left, rect.Top, Math.Max(rect.Right, rect.Left + 1), rect.Bottom);
    }
}
