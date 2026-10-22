// Calcoli puri sul testo del campo: inserimento al posto della selezione, cancellazione dell'ultima frase,
// numero di tasti Backspace. Spec §2.4, §5.4, §5.9, §5.10. Aggiunto il 22/10/2026 alle 11:32.
using System.Globalization;

namespace Zzitto.Platform.Input;

public static class TextEditMath
{
    /// <summary>
    /// Ricava inizio e lunghezza della selezione nel valore del campo, dati il testo prima della selezione
    /// e il testo selezionato letti con TextPattern. Restituisce false se non sono coerenti con il valore
    /// (in quel caso UI Automation non è affidabile e si passa a SendInput).
    /// </summary>
    public static bool TryLocateSelection(string value, string textBefore, string selectedText, out int start, out int length)
    {
        ArgumentNullException.ThrowIfNull(value);
        ArgumentNullException.ThrowIfNull(textBefore);
        ArgumentNullException.ThrowIfNull(selectedText);
        start = 0;
        length = 0;
        if (!value.StartsWith(textBefore, StringComparison.Ordinal))
        {
            return false;
        }

        if (string.CompareOrdinal(value, textBefore.Length, selectedText, 0, selectedText.Length) != 0
            || textBefore.Length + selectedText.Length > value.Length)
        {
            return false;
        }

        start = textBefore.Length;
        length = selectedText.Length;
        return true;
    }

    /// <summary>Nuovo valore dopo aver sostituito la selezione con <paramref name="insert"/>; caret dopo il testo.</summary>
    public static (string NewValue, int Caret) ReplaceRange(string value, int start, int length, string insert)
    {
        ArgumentNullException.ThrowIfNull(value);
        ArgumentNullException.ThrowIfNull(insert);
        if (start < 0 || length < 0 || start + length > value.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(start));
        }

        string newValue = string.Concat(value.AsSpan(0, start), insert, value.AsSpan(start + length));
        return (newValue, start + insert.Length);
    }

    /// <summary>
    /// Adatta gli a capo al controllo: i campi Win32 multilinea usano "\r\n", il motore restituisce "\n".
    /// </summary>
    public static string NormalizeNewLines(string text, bool useCrLf)
    {
        ArgumentNullException.ThrowIfNull(text);
        string lf = text.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n');
        return useCrLf ? lf.Replace("\n", "\r\n", StringComparison.Ordinal) : lf;
    }

    /// <summary>
    /// Lunghezza (in caratteri UTF-16) dell'ultima frase prima del cursore, compresi gli spazi che la seguono.
    /// Una frase termina con . ! ? … o con un a capo. Spec §5.9 "cancella l'ultima frase".
    /// </summary>
    public static int LastSentenceLength(string textBeforeCaret)
    {
        ArgumentNullException.ThrowIfNull(textBeforeCaret);
        int end = textBeforeCaret.Length;

        // Salta spazi finali.
        int i = end;
        while (i > 0 && textBeforeCaret[i - 1] is ' ' or '\t' or ' ')
        {
            i--;
        }

        // Salta la punteggiatura che chiude la frase da cancellare.
        while (i > 0 && IsSentenceTerminator(textBeforeCaret[i - 1]))
        {
            i--;
        }

        // Risale fino al terminatore della frase precedente o a un a capo.
        while (i > 0 && !IsSentenceTerminator(textBeforeCaret[i - 1]) && textBeforeCaret[i - 1] is not '\n' and not '\r')
        {
            i--;
        }

        // Gli spazi tra la frase precedente e quella cancellata restano.
        while (i < end && textBeforeCaret[i] is ' ' or '\t' or ' ')
        {
            i++;
        }

        return end - i;
    }

    /// <summary>
    /// Tasti Backspace necessari per cancellare <paramref name="text"/> appena digitato: un tasto per
    /// elemento testuale (una coppia surrogata, un emoji composto o "\r\n" contano come uno).
    /// </summary>
    public static int BackspaceCount(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        return text.Length == 0 ? 0 : new StringInfo(text).LengthInTextElements;
    }

    private static bool IsSentenceTerminator(char c) => c is '.' or '!' or '?' or '…';
}
