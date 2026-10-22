// Inserimento nel campo attivo di qualsiasi applicazione Windows. Spec §2.4, §2.6, §5.4, §5.9, §5.10, §10.
// Aggiunto il 22/10/2026 alle 11:32.
//
//  - UI Automation dove sicuro (InsertionPlanner), poi SendInput Unicode, infine appunti con avviso Ctrl+V;
//  - nessun privilegio di amministratore: con finestre elevate solo appunti;
//  - selezione per la riscrittura letta con TextPattern o con copia temporanea e ripristino degli appunti;
//  - tutte le chiamate UIA girano su thread del pool (MTA), con un tempo massimo.
using Zzitto.Platform.Dictation;
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Input;

public sealed class WindowsTextTarget : ITextTarget
{
    private static readonly TimeSpan UiaTimeout = TimeSpan.FromMilliseconds(1500);

    private readonly UiaClient _uia = new();
    private readonly IClipboardService _clipboard;
    private readonly object _lock = new();
    private LastInsertion? _last;

    public WindowsTextTarget(IClipboardService clipboard)
    {
        _clipboard = clipboard ?? throw new ArgumentNullException(nameof(clipboard));
    }

    public async Task<TargetContext> CaptureAsync(bool allowClipboardSelection, CancellationToken cancellationToken)
    {
        // Dopo la scorciatoia globale l'utente può avere ancora Ctrl/Alt premuti.
        await KeyboardInjector.WaitForModifiersReleasedAsync(TimeSpan.FromMilliseconds(1500), cancellationToken).ConfigureAwait(false);

        (IntPtr window, _, uint processId) = ProcessInfo.Foreground();
        bool own = processId == (uint)Environment.ProcessId;
        bool elevated = !own && ProcessInfo.IsElevatedOrInaccessible(processId);
        string windowClass = window == IntPtr.Zero ? string.Empty : Win32.GetClassName(window);

        UiaField? field = null;
        string? selected = null;
        if (!elevated)
        {
            (field, selected) = await RunUiaAsync(() =>
            {
                UiaField? f = _uia.GetFocusedField();
                return (f, f?.GetSelectedText());
            }, cancellationToken).ConfigureAwait(false);
        }

        // Spec §5.10: se UI Automation non espone la selezione, copia temporanea con ripristino.
        if (allowClipboardSelection && selected is null && window != IntPtr.Zero
            && InsertionPlanner.CanUseClipboardCopyForSelection(windowClass, field?.Facts, elevated))
        {
            selected = await CopySelectionAsync(window, cancellationToken).ConfigureAwait(false);
        }

        return new TargetContext
        {
            Window = window,
            ProcessId = processId,
            WindowClass = windowClass,
            IsElevated = elevated,
            IsOwnProcess = own,
            Field = field?.Facts,
            SelectedText = string.IsNullOrEmpty(selected) ? null : selected,
            NativeField = field,
        };
    }

    public async Task<InsertionOutcome> InsertAsync(TargetContext context, string text, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(context);
        ArgumentNullException.ThrowIfNull(text);
        if (text.Length == 0)
        {
            return new InsertionOutcome(InsertionMethod.SendInput);
        }

        await KeyboardInjector.WaitForModifiersReleasedAsync(TimeSpan.FromMilliseconds(1500), cancellationToken).ConfigureAwait(false);

        var field = context.NativeField as UiaField;
        // Dati per decidere se UIA è utilizzabile, letti ora (il campo può essere cambiato durante la dettatura).
        (string? value, string? before, string? selection) = field is null || context.IsElevated
            ? (null, null, null)
            : await RunUiaAsync(() => (field.GetValue(), field.GetTextBeforeSelection(), field.GetSelectedText()), cancellationToken)
                .ConfigureAwait(false);

        int selStart = 0;
        int selLength = 0;
        bool locatable = value is not null && before is not null
            && TextEditMath.TryLocateSelection(value, before, selection ?? string.Empty, out selStart, out selLength);

        var planContext = new InsertionContext(context.IsElevated, context.IsOwnProcess, context.Field, locatable, value?.Length ?? 0);
        IReadOnlyList<InsertionMethod> plan = InsertionPlanner.Plan(planContext);

        LastInsertion? record = null;
        InsertionOutcome outcome = await Task.Run(() => InsertionChain.Execute(plan, method =>
        {
            switch (method)
            {
                case InsertionMethod.UiAutomation:
                    return field is not null && value is not null
                        && TryInsertWithUia(field, value, selStart, selLength, text, out record);
                case InsertionMethod.SendInput:
                    return TryInsertWithSendInput(context, text, out record);
                case InsertionMethod.Clipboard:
                    record = null;
                    return _clipboard.SetText(text);
                default:
                    return false;
            }
        }), cancellationToken).ConfigureAwait(false);

        lock (_lock)
        {
            _last = record;
        }

        return outcome;
    }

    public async Task<bool> UndoLastInsertAsync(CancellationToken cancellationToken)
    {
        LastInsertion? last;
        lock (_lock)
        {
            last = _last;
            _last = null;
        }

        if (last is null)
        {
            return false;
        }

        await KeyboardInjector.WaitForModifiersReleasedAsync(TimeSpan.FromMilliseconds(1500), cancellationToken).ConfigureAwait(false);
        if (last.Method == InsertionMethod.UiAutomation && last.Field is not null && last.PreviousValue is not null)
        {
            UiaField field = last.Field;
            string previous = last.PreviousValue;
            string? inserted = last.NewValue;
            return await RunUiaAsync(() =>
            {
                // Si ripristina solo se nel frattempo il campo non è cambiato.
                if (field.GetValue() != inserted || !field.TrySetValue(previous))
                {
                    return false;
                }

                field.TryPlaceCaret(last.CaretBefore);
                return true;
            }, cancellationToken).ConfigureAwait(false);
        }

        if (last.Method == InsertionMethod.SendInput && Win32.GetForegroundWindow() == last.Window)
        {
            return KeyboardInjector.Send(KeyboardSequenceBuilder.Repeat(KeyboardSequenceBuilder.VkBack, last.BackspaceCount));
        }

        return false;
    }

    public async Task<bool> DeletePreviousSentenceAsync(TargetContext context, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(context);
        if (context.IsElevated || context.NativeField is not UiaField field || Win32.GetForegroundWindow() != context.Window)
        {
            return false;
        }

        string? before = await RunUiaAsync(() => field.GetTextBeforeSelection(), cancellationToken).ConfigureAwait(false);
        if (string.IsNullOrEmpty(before))
        {
            return false;
        }

        int length = TextEditMath.LastSentenceLength(before);
        if (length == 0)
        {
            return false;
        }

        int keys = TextEditMath.BackspaceCount(before[^length..]);
        await KeyboardInjector.WaitForModifiersReleasedAsync(TimeSpan.FromMilliseconds(1500), cancellationToken).ConfigureAwait(false);
        return KeyboardInjector.Send(KeyboardSequenceBuilder.Repeat(KeyboardSequenceBuilder.VkBack, keys));
    }

    public async Task<bool> PressSendAsync(TargetContext context, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(context);
        if (context.IsElevated || !InsertionPlanner.CanPressSend(context.Field) || Win32.GetForegroundWindow() != context.Window)
        {
            return false;
        }

        await KeyboardInjector.WaitForModifiersReleasedAsync(TimeSpan.FromMilliseconds(1500), cancellationToken).ConfigureAwait(false);
        return KeyboardInjector.Send(KeyboardSequenceBuilder.Repeat(KeyboardSequenceBuilder.VkReturn, 1));
    }

    private static bool TryInsertWithUia(UiaField field, string value, int start, int length, string text, out LastInsertion? record)
    {
        record = null;
        // I campi Win32 multilinea usano "\r\n".
        bool crlf = value.Contains("\r\n", StringComparison.Ordinal) || field.Facts.FrameworkId.Equals("Win32", StringComparison.OrdinalIgnoreCase);
        string insert = TextEditMath.NormalizeNewLines(text, crlf);
        (string newValue, int caret) = TextEditMath.ReplaceRange(value, start, length, insert);
        if (!field.TrySetValue(newValue))
        {
            return false;
        }

        // Verifica: il controllo deve riportare esattamente il nuovo valore.
        if (field.GetValue() != newValue)
        {
            return false;
        }

        field.TryPlaceCaret(caret);
        record = new LastInsertion(InsertionMethod.UiAutomation, IntPtr.Zero, 0, field, value, newValue, start + length);
        return true;
    }

    private static bool TryInsertWithSendInput(TargetContext context, string text, out LastInsertion? record)
    {
        record = null;
        // Mai digitare in una finestra diversa da quella in cui l'utente ha avviato la dettatura.
        if (context.Window == IntPtr.Zero || Win32.GetForegroundWindow() != context.Window)
        {
            return false;
        }

        if (!KeyboardInjector.Send(KeyboardSequenceBuilder.ForText(text)))
        {
            return false;
        }

        record = new LastInsertion(InsertionMethod.SendInput, context.Window, TextEditMath.BackspaceCount(text), null, null, null, 0);
        return true;
    }

    private async Task<string?> CopySelectionAsync(IntPtr window, CancellationToken cancellationToken)
    {
        ClipboardSnapshot snapshot = await Task.Run(() => _clipboard.Snapshot(), cancellationToken).ConfigureAwait(false);
        try
        {
            uint before = _clipboard.SequenceNumber;
            if (Win32.GetForegroundWindow() != window
                || !KeyboardInjector.Send(KeyboardSequenceBuilder.Chord(KeyboardSequenceBuilder.VkControl, Win32.VK_C)))
            {
                return null;
            }

            // L'app destinataria aggiorna gli appunti in modo asincrono.
            for (int i = 0; i < 20 && _clipboard.SequenceNumber == before; i++)
            {
                await Task.Delay(15, cancellationToken).ConfigureAwait(false);
            }

            if (_clipboard.SequenceNumber == before)
            {
                return null; // niente selezionato
            }

            return await Task.Run(() => _clipboard.GetText(), cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            await Task.Run(() => _clipboard.Restore(snapshot), CancellationToken.None).ConfigureAwait(false);
            snapshot.Wipe();
        }
    }

    private static async Task<T> RunUiaAsync<T>(Func<T> action, CancellationToken cancellationToken)
    {
        // Thread del pool = MTA; un'app bloccata non deve bloccare la dettatura.
        Task<T> task = Task.Run(action, cancellationToken);
        Task finished = await Task.WhenAny(task, Task.Delay(UiaTimeout, cancellationToken)).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        return finished == task ? await task.ConfigureAwait(false) : default!;
    }

    private sealed record LastInsertion(
        InsertionMethod Method,
        IntPtr Window,
        int BackspaceCount,
        UiaField? Field,
        string? PreviousValue,
        string? NewValue,
        int CaretBefore);
}
