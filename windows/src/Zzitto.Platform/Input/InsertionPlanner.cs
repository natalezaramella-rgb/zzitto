// Scelta del metodo di inserimento nel campo attivo. Spec §2.4, §2.6. Aggiunto il 22/10/2026 alle 11:32.
//
// Ordine: UI Automation (dove sicuro) → SendInput Unicode → appunti con avviso "premi Ctrl+V".
// UI Automation (ValuePattern.SetValue) riscrive l'intero valore del controllo: si usa solo su campi Edit
// di framework nativi (Win32, WinForms, WPF, XAML) dove la posizione della selezione è verificabile.
// Nei browser, in Electron e nei documenti (Word) SetValue perderebbe formattazione o non avviserebbe
// l'app, quindi si digita con SendInput, che rispetta il cursore e l'annullamento dell'app.
// Con finestre a privilegi elevati SendInput viene bloccato da UIPI senza errori: solo appunti (§2.6).
namespace Zzitto.Platform.Input;

public enum InsertionMethod
{
    UiAutomation,
    SendInput,
    Clipboard,
}

public sealed record InsertionContext(
    bool TargetIsElevated,
    bool TargetIsOwnProcess,
    FieldFacts? Field,
    bool SelectionLocatable,
    int CurrentValueLength);

public static class InsertionPlanner
{
    /// <summary>Framework i cui campi Edit gestiscono SetValue come una modifica utente.</summary>
    public static readonly IReadOnlySet<string> UiaSafeFrameworks =
        new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "Win32", "WinForm", "WPF", "XAML", "DirectUI" };

    /// <summary>Oltre questa lunghezza riscrivere tutto il valore è troppo invasivo: si digita.</summary>
    public const int MaxUiaValueLength = 20000;

    public static IReadOnlyList<InsertionMethod> Plan(InsertionContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        if (context.TargetIsElevated)
        {
            return new[] { InsertionMethod.Clipboard };
        }

        var methods = new List<InsertionMethod>(3);
        if (CanUseUiAutomation(context))
        {
            methods.Add(InsertionMethod.UiAutomation);
        }

        methods.Add(InsertionMethod.SendInput);
        methods.Add(InsertionMethod.Clipboard);
        return methods;
    }

    public static bool CanUseUiAutomation(InsertionContext context)
    {
        ArgumentNullException.ThrowIfNull(context);
        FieldFacts? field = context.Field;
        return field is not null
            && field.IsEdit
            && field.HasValuePattern
            && !field.IsReadOnly
            && !field.IsPassword
            && UiaSafeFrameworks.Contains(field.FrameworkId)
            && context.CurrentValueLength <= MaxUiaValueLength
            && (context.SelectionLocatable || context.CurrentValueLength == 0);
    }

    /// <summary>"Invia" preme Invio solo se il campo lo consente (Spec §5.9): non nei documenti né nelle password.</summary>
    public static bool CanPressSend(FieldFacts? field) =>
        field is null || (!field.IsDocument && !field.IsReadOnly);

    /// <summary>
    /// La copia temporanea per leggere la selezione (Spec §5.10) si evita nelle console, dove Ctrl+C
    /// interrompe il programma, nelle password e nelle finestre elevate.
    /// </summary>
    public static bool CanUseClipboardCopyForSelection(string windowClass, FieldFacts? field, bool targetIsElevated)
    {
        ArgumentNullException.ThrowIfNull(windowClass);
        if (targetIsElevated || field is { IsPassword: true } || field is { HasTextPattern: true })
        {
            return false;
        }

        return !ConsoleWindowClasses.Contains(windowClass);
    }

    public static readonly IReadOnlySet<string> ConsoleWindowClasses =
        new HashSet<string>(StringComparer.Ordinal) { "ConsoleWindowClass", "CASCADIA_HOSTING_WINDOW_CLASS", "PseudoConsoleWindow" };
}
