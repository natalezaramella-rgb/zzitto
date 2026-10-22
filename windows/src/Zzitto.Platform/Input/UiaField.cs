// Accesso al campo con il focus tramite UI Automation: proprietà, selezione, valore, cursore.
// Spec §2.4 (inserimento con UI Automation), §2.6 (posizione dell'icona), §5.10 (lettura della selezione).
// Aggiunto il 22/10/2026 alle 11:32.
//
// Le chiamate UIA vanno fatte da thread MTA (thread del pool), mai dal thread di un'interfaccia:
// un'app che non risponde bloccherebbe la nostra. Ogni chiamata è protetta: un errore COM significa
// "informazione non disponibile" e attiva i fallback.
using System.Runtime.InteropServices;
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Input;

internal sealed class UiaClient
{
    private readonly Lazy<IUIAutomation?> _automation = new(CreateAutomation, LazyThreadSafetyMode.ExecutionAndPublication);

    public bool IsAvailable => _automation.Value is not null;

    /// <summary>Campo con il focus della tastiera, oppure null se UIA non lo espone.</summary>
    public UiaField? GetFocusedField()
    {
        IUIAutomation? automation = _automation.Value;
        if (automation is null)
        {
            return null;
        }

        try
        {
            if (automation.GetFocusedElement(out IUIAutomationElement element) < 0 || element is null)
            {
                return null;
            }

            return new UiaField(element);
        }
        catch (COMException)
        {
            return null;
        }
        catch (InvalidCastException)
        {
            return null;
        }
    }

    private static IUIAutomation? CreateAutomation()
    {
        try
        {
            return (IUIAutomation)new CUIAutomation8ComObject();
        }
        catch (COMException)
        {
            try
            {
                return (IUIAutomation)new CUIAutomationComObject();
            }
            catch (COMException)
            {
                return null;
            }
        }
    }
}

internal sealed class UiaField
{
    private readonly IUIAutomationElement _element;

    public UiaField(IUIAutomationElement element)
    {
        _element = element;
        Facts = ReadFacts();
    }

    public FieldFacts Facts { get; }

    public ScreenRect? BoundingRectangle
    {
        get
        {
            object? value = GetProperty(UiaConstants.BoundingRectangleProperty);
            if (value is double[] { Length: 4 } r && r[2] > 0 && r[3] > 0)
            {
                return new ScreenRect((int)Math.Round(r[0]), (int)Math.Round(r[1]), (int)Math.Round(r[2]), (int)Math.Round(r[3]));
            }

            return null;
        }
    }

    /// <summary>Rettangolo del cursore (TextPattern2.GetCaretRange), se l'app lo espone.</summary>
    public ScreenRect? GetCaretRectangle()
    {
        IUIAutomationTextPattern2? pattern2 = GetPattern<IUIAutomationTextPattern2>(UiaConstants.TextPattern2);
        if (pattern2 is not null)
        {
            if (Safe(() => pattern2.GetCaretRange(out _, out IUIAutomationTextRange range) >= 0 ? range : null) is { } caret)
            {
                ScreenRect? rect = FirstRectangle(caret);
                if (rect is not null)
                {
                    return rect;
                }
            }
        }

        // In alternativa: il rettangolo della selezione corrente (spesso vuota = cursore).
        IUIAutomationTextRange? selection = GetSelectionRange();
        return selection is null ? null : FirstRectangle(selection);
    }

    /// <summary>Testo selezionato secondo TextPattern; null se il controllo non lo espone.</summary>
    public string? GetSelectedText()
    {
        IUIAutomationTextRange? selection = GetSelectionRange();
        if (selection is null)
        {
            return null;
        }

        return Safe(() => selection.GetText(-1, out string text) >= 0 ? text : null);
    }

    /// <summary>
    /// Testo dall'inizio del documento fino all'inizio della selezione (o al cursore).
    /// Serve a calcolare la posizione del cursore nel valore e a cancellare l'ultima frase (Spec §5.9).
    /// </summary>
    public string? GetTextBeforeSelection()
    {
        IUIAutomationTextPattern? pattern = GetPattern<IUIAutomationTextPattern>(UiaConstants.TextPattern);
        IUIAutomationTextRange? selection = GetSelectionRange();
        if (pattern is null || selection is null)
        {
            return null;
        }

        return Safe(() =>
        {
            if (pattern.GetDocumentRange(out IUIAutomationTextRange document) < 0 || document is null)
            {
                return null;
            }

            if (document.Clone(out IUIAutomationTextRange before) < 0 || before is null)
            {
                return null;
            }

            if (before.MoveEndpointByRange(UiaConstants.EndpointEnd, selection, UiaConstants.EndpointStart) < 0)
            {
                return null;
            }

            return before.GetText(-1, out string text) >= 0 ? text : null;
        });
    }

    public string? GetValue()
    {
        IUIAutomationValuePattern? pattern = GetPattern<IUIAutomationValuePattern>(UiaConstants.ValuePattern);
        return pattern is null ? null : Safe(() => pattern.GetCurrentValue(out string value) >= 0 ? value : null);
    }

    public bool TrySetValue(string value)
    {
        IUIAutomationValuePattern? pattern = GetPattern<IUIAutomationValuePattern>(UiaConstants.ValuePattern);
        return pattern is not null && Safe(() => pattern.SetValue(value) >= 0);
    }

    /// <summary>Posiziona il cursore dopo <paramref name="offset"/> caratteri (senza selezione).</summary>
    public bool TryPlaceCaret(int offset)
    {
        IUIAutomationTextPattern? pattern = GetPattern<IUIAutomationTextPattern>(UiaConstants.TextPattern);
        if (pattern is null)
        {
            return false;
        }

        return Safe(() =>
        {
            if (pattern.GetDocumentRange(out IUIAutomationTextRange document) < 0 || document is null)
            {
                return false;
            }

            // Comprime l'intervallo all'inizio, poi sposta entrambi gli estremi di "offset" caratteri.
            if (document.MoveEndpointByRange(UiaConstants.EndpointEnd, document, UiaConstants.EndpointStart) < 0)
            {
                return false;
            }

            if (offset > 0)
            {
                document.MoveEndpointByUnit(UiaConstants.EndpointEnd, UiaConstants.TextUnitCharacter, offset, out _);
                document.MoveEndpointByUnit(UiaConstants.EndpointStart, UiaConstants.TextUnitCharacter, offset, out _);
            }

            return document.Select() >= 0;
        });
    }

    private FieldFacts ReadFacts()
    {
        int controlType = GetProperty(UiaConstants.ControlTypeProperty) is int ct ? ct : 0;
        string frameworkId = GetProperty(UiaConstants.FrameworkIdProperty) as string ?? string.Empty;
        string className = GetProperty(UiaConstants.ClassNameProperty) as string ?? string.Empty;
        bool isPassword = GetProperty(UiaConstants.IsPasswordProperty) is true;
        bool hasValue = GetProperty(UiaConstants.IsValuePatternAvailableProperty) is true;
        bool hasText = GetProperty(UiaConstants.IsTextPatternAvailableProperty) is true;
        bool isReadOnly = hasValue && GetProperty(UiaConstants.ValueIsReadOnlyProperty) is true;
        int processId = GetProperty(UiaConstants.ProcessIdProperty) is int pid ? pid : 0;
        return new FieldFacts(controlType, frameworkId, className, isPassword, isReadOnly, hasValue, hasText, processId);
    }

    private IUIAutomationTextRange? GetSelectionRange()
    {
        IUIAutomationTextPattern? pattern = GetPattern<IUIAutomationTextPattern>(UiaConstants.TextPattern);
        if (pattern is null)
        {
            return null;
        }

        return Safe(() =>
        {
            if (pattern.GetSelection(out IUIAutomationTextRangeArray ranges) < 0 || ranges is null)
            {
                return null;
            }

            if (ranges.GetLength(out int length) < 0 || length < 1)
            {
                return null;
            }

            return ranges.GetElement(0, out IUIAutomationTextRange range) >= 0 ? range : null;
        });
    }

    private static ScreenRect? FirstRectangle(IUIAutomationTextRange range)
    {
        return Safe<ScreenRect?>(() =>
        {
            if (range.GetBoundingRectangles(out double[]? rects) < 0 || rects is null || rects.Length < 4)
            {
                return null;
            }

            // Un cursore può avere larghezza 0: si garantisce almeno 1 pixel.
            int width = Math.Max(1, (int)Math.Round(rects[2]));
            int height = (int)Math.Round(rects[3]);
            return height > 0 ? new ScreenRect((int)Math.Round(rects[0]), (int)Math.Round(rects[1]), width, height) : null;
        });
    }

    private object? GetProperty(int propertyId) =>
        Safe(() => _element.GetCurrentPropertyValue(propertyId, out object value) >= 0 ? value : null);

    private T? GetPattern<T>(int patternId)
        where T : class
    {
        return Safe(() => _element.GetCurrentPattern(patternId, out object? pattern) >= 0 ? pattern as T : null);
    }

    private static T? Safe<T>(Func<T?> action)
    {
        try
        {
            return action();
        }
        catch (COMException)
        {
            return default;
        }
        catch (InvalidCastException)
        {
            return default;
        }
        catch (UnauthorizedAccessException)
        {
            return default;
        }
    }

    private static bool Safe(Func<bool> action)
    {
        try
        {
            return action();
        }
        catch (COMException)
        {
            return false;
        }
        catch (InvalidCastException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }
}
