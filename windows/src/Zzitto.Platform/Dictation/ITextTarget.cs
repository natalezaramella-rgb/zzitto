// Destinazione del testo dettato: il campo attivo di qualsiasi applicazione. Spec §2.4, §2.6, §5.4, §5.9, §5.10.
// Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Input;

namespace Zzitto.Platform.Dictation;

/// <summary>Fotografia del campo di destinazione presa all'avvio della dettatura.</summary>
public sealed class TargetContext
{
    public IntPtr Window { get; init; }

    public uint ProcessId { get; init; }

    public string WindowClass { get; init; } = string.Empty;

    public bool IsElevated { get; init; }

    public bool IsOwnProcess { get; init; }

    public FieldFacts? Field { get; init; }

    /// <summary>Testo selezionato: se non è vuoto la dettatura è una riscrittura (Spec §5.10).</summary>
    public string? SelectedText { get; init; }

    public bool IsRewrite => !string.IsNullOrEmpty(SelectedText);

    /// <summary>Il campo è una password: il testo non va nella cronologia (Spec §10).</summary>
    public bool IsPassword => Field?.IsPassword == true;

    /// <summary>Riferimento UI Automation (uso interno della piattaforma).</summary>
    internal object? NativeField { get; init; }
}

public sealed record InsertionOutcome(InsertionMethod? Method)
{
    public bool Success => Method is not null;

    public bool UsedClipboard => Method == InsertionMethod.Clipboard;

    public static InsertionOutcome Failed { get; } = new((InsertionMethod?)null);
}

public interface ITextTarget
{
    /// <summary>Rileva finestra, campo e selezione correnti. Non lancia eccezioni.</summary>
    Task<TargetContext> CaptureAsync(bool allowClipboardSelection, CancellationToken cancellationToken);

    /// <summary>Inserisce il testo al posto della selezione (o al cursore) con i fallback del §2.6.</summary>
    Task<InsertionOutcome> InsertAsync(TargetContext context, string text, CancellationToken cancellationToken);

    /// <summary>Annulla l'ultimo inserimento (Spec §5.4, comando "annulla" §5.9).</summary>
    Task<bool> UndoLastInsertAsync(CancellationToken cancellationToken);

    /// <summary>Cancella l'ultima frase prima del cursore (ZZ_ACTION_DELETE_PREVIOUS_SENTENCE).</summary>
    Task<bool> DeletePreviousSentenceAsync(TargetContext context, CancellationToken cancellationToken);

    /// <summary>Preme Invio se il campo lo consente (ZZ_ACTION_SEND).</summary>
    Task<bool> PressSendAsync(TargetContext context, CancellationToken cancellationToken);
}

/// <summary>Esecuzione ordinata dei metodi di inserimento: il primo che riesce vince (Spec §2.4, §2.6).</summary>
public static class InsertionChain
{
    public static InsertionOutcome Execute(IReadOnlyList<InsertionMethod> plan, Func<InsertionMethod, bool> attempt)
    {
        ArgumentNullException.ThrowIfNull(plan);
        ArgumentNullException.ThrowIfNull(attempt);
        foreach (InsertionMethod method in plan)
        {
            bool ok;
            try
            {
                ok = attempt(method);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                ok = false;
            }

            if (ok)
            {
                return new InsertionOutcome(method);
            }
        }

        return InsertionOutcome.Failed;
    }
}
