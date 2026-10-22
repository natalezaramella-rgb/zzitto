// Test della logica di inserimento (Spec §2.4, §2.6, §5.4, §5.9, §5.10, §12). Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Dictation;
using Zzitto.Platform.Input;

namespace Zzitto.Tests;

public class InsertionPlannerTests
{
    private static FieldFacts Edit(string framework = "Win32", bool password = false, bool readOnly = false, bool value = true, bool text = true) =>
        new(FieldFacts.EditControlType, framework, "Edit", password, readOnly, value, text, 100);

    [Fact]
    public void ElevatedTarget_UsesClipboardOnly()
    {
        // Spec §2.6: nessun privilegio di amministratore, fallback degli appunti.
        var plan = InsertionPlanner.Plan(new InsertionContext(true, false, Edit(), true, 10));
        Assert.Equal(new[] { InsertionMethod.Clipboard }, plan);
    }

    [Fact]
    public void NativeEdit_TriesUiAutomationFirst()
    {
        var plan = InsertionPlanner.Plan(new InsertionContext(false, false, Edit(), true, 10));
        Assert.Equal(new[] { InsertionMethod.UiAutomation, InsertionMethod.SendInput, InsertionMethod.Clipboard }, plan);
    }

    [Theory]
    [InlineData("Chrome")]
    [InlineData("Gecko")]
    [InlineData("")]
    public void BrowsersAndUnknownFrameworks_SkipUiAutomation(string framework)
    {
        var plan = InsertionPlanner.Plan(new InsertionContext(false, false, Edit(framework), true, 10));
        Assert.Equal(new[] { InsertionMethod.SendInput, InsertionMethod.Clipboard }, plan);
    }

    [Fact]
    public void Documents_SkipUiAutomation()
    {
        var word = new FieldFacts(FieldFacts.DocumentControlType, "Win32", "_WwG", false, false, true, true, 1);
        Assert.False(InsertionPlanner.CanUseUiAutomation(new InsertionContext(false, false, word, true, 10)));
    }

    [Fact]
    public void PasswordReadOnlyOrUnlocatable_SkipUiAutomation()
    {
        Assert.False(InsertionPlanner.CanUseUiAutomation(new InsertionContext(false, false, Edit(password: true), true, 10)));
        Assert.False(InsertionPlanner.CanUseUiAutomation(new InsertionContext(false, false, Edit(readOnly: true), true, 10)));
        Assert.False(InsertionPlanner.CanUseUiAutomation(new InsertionContext(false, false, Edit(), false, 10)));
        Assert.True(InsertionPlanner.CanUseUiAutomation(new InsertionContext(false, false, Edit(), false, 0)));
        Assert.False(InsertionPlanner.CanUseUiAutomation(new InsertionContext(false, false, Edit(), true, InsertionPlanner.MaxUiaValueLength + 1)));
        Assert.False(InsertionPlanner.CanUseUiAutomation(new InsertionContext(false, false, null, true, 0)));
    }

    [Fact]
    public void Send_NotInDocuments()
    {
        Assert.True(InsertionPlanner.CanPressSend(null));
        Assert.True(InsertionPlanner.CanPressSend(Edit("Chrome")));
        Assert.False(InsertionPlanner.CanPressSend(new FieldFacts(FieldFacts.DocumentControlType, "Win32", "_WwG", false, false, true, true, 1)));
    }

    [Theory]
    [InlineData("ConsoleWindowClass")]
    [InlineData("CASCADIA_HOSTING_WINDOW_CLASS")]
    public void ClipboardCopy_NeverInConsoles(string windowClass)
    {
        // In una console Ctrl+C interromperebbe il programma.
        Assert.False(InsertionPlanner.CanUseClipboardCopyForSelection(windowClass, null, false));
    }

    [Fact]
    public void ClipboardCopy_OnlyWhenUiAutomationCannotReadSelection()
    {
        Assert.True(InsertionPlanner.CanUseClipboardCopyForSelection("Chrome_WidgetWin_1", null, false));
        Assert.False(InsertionPlanner.CanUseClipboardCopyForSelection("Chrome_WidgetWin_1", Edit(text: true), false));
        Assert.True(InsertionPlanner.CanUseClipboardCopyForSelection("Chrome_WidgetWin_1", Edit(text: false), false));
        Assert.False(InsertionPlanner.CanUseClipboardCopyForSelection("Chrome_WidgetWin_1", Edit(password: true, text: false), false));
        Assert.False(InsertionPlanner.CanUseClipboardCopyForSelection("Notepad", null, true));
    }
}

public class InsertionChainTests
{
    [Fact]
    public void FirstSuccessfulMethodWins()
    {
        var tried = new List<InsertionMethod>();
        InsertionOutcome outcome = InsertionChain.Execute(
            new[] { InsertionMethod.UiAutomation, InsertionMethod.SendInput, InsertionMethod.Clipboard },
            m =>
            {
                tried.Add(m);
                return m == InsertionMethod.SendInput;
            });

        Assert.Equal(InsertionMethod.SendInput, outcome.Method);
        Assert.Equal(new[] { InsertionMethod.UiAutomation, InsertionMethod.SendInput }, tried);
        Assert.False(outcome.UsedClipboard);
    }

    [Fact]
    public void ExceptionsFallThroughToClipboard()
    {
        InsertionOutcome outcome = InsertionChain.Execute(
            new[] { InsertionMethod.UiAutomation, InsertionMethod.SendInput, InsertionMethod.Clipboard },
            m => m == InsertionMethod.Clipboard ? true : throw new InvalidOperationException());

        Assert.True(outcome.Success);
        Assert.True(outcome.UsedClipboard);
    }

    [Fact]
    public void AllFail_ReportsFailure()
    {
        InsertionOutcome outcome = InsertionChain.Execute(new[] { InsertionMethod.SendInput }, _ => false);
        Assert.False(outcome.Success);
        Assert.Null(outcome.Method);
    }
}

public class TextEditMathTests
{
    [Fact]
    public void LocateSelection_ConsistentWithValue()
    {
        Assert.True(TextEditMath.TryLocateSelection("Ciao mondo bello", "Ciao ", "mondo", out int start, out int length));
        Assert.Equal(5, start);
        Assert.Equal(5, length);
    }

    [Fact]
    public void LocateSelection_InconsistentIsRejected()
    {
        Assert.False(TextEditMath.TryLocateSelection("Ciao mondo", "Salve ", "", out _, out _));
        Assert.False(TextEditMath.TryLocateSelection("Ciao mondo", "Ciao ", "gente", out _, out _));
        Assert.False(TextEditMath.TryLocateSelection("Ciao", "Ciao", "x", out _, out _));
    }

    [Fact]
    public void ReplaceRange_InsertsAtCaretAndReplacesSelection()
    {
        Assert.Equal(("Ciao bella gente", 11), TextEditMath.ReplaceRange("Ciao gente", 5, 0, "bella "));
        Assert.Equal(("Ciao a tutti", 12), TextEditMath.ReplaceRange("Ciao mondo", 5, 5, "a tutti"));
    }

    [Fact]
    public void NewLines_AreAdaptedToTheControl()
    {
        Assert.Equal("a\r\nb\r\nc", TextEditMath.NormalizeNewLines("a\nb\r\nc", useCrLf: true));
        Assert.Equal("a\nb\nc", TextEditMath.NormalizeNewLines("a\r\nb\rc", useCrLf: false));
    }

    [Theory]
    [InlineData("Ciao. Come stai? Bene", 4)]
    [InlineData("Ciao. Come stai? ", 11)]
    [InlineData("Una sola frase.", 15)]
    [InlineData("Prima riga\nseconda riga", 12)]
    [InlineData("", 0)]
    [InlineData("Fine!   ", 8)]
    public void LastSentenceLength(string text, int expected)
    {
        Assert.Equal(expected, TextEditMath.LastSentenceLength(text));
    }

    [Fact]
    public void BackspaceCount_CountsTextElements()
    {
        Assert.Equal(4, TextEditMath.BackspaceCount("ciao"));
        Assert.Equal(1, TextEditMath.BackspaceCount("\r\n"));
        Assert.Equal(2, TextEditMath.BackspaceCount("a😀"));
        Assert.Equal(0, TextEditMath.BackspaceCount(string.Empty));
    }
}

public class KeyboardSequenceBuilderTests
{
    [Fact]
    public void Text_IsTypedAsUnicode()
    {
        IReadOnlyList<KeyStroke> strokes = KeyboardSequenceBuilder.ForText("è");
        Assert.Equal(new[] { KeyStroke.UnicodeDown('è'), KeyStroke.UnicodeUp('è') }, strokes);
    }

    [Fact]
    public void NewLine_IsShiftEnter()
    {
        // Nelle chat Invio invierebbe il messaggio: l'a capo dettato è Maiusc+Invio.
        IReadOnlyList<KeyStroke> strokes = KeyboardSequenceBuilder.ForText("a\r\nb");
        Assert.Equal(8, strokes.Count);
        Assert.Equal(KeyStroke.Down(KeyboardSequenceBuilder.VkShift), strokes[2]);
        Assert.Equal(KeyStroke.Down(KeyboardSequenceBuilder.VkReturn), strokes[3]);
        Assert.Equal(KeyStroke.Up(KeyboardSequenceBuilder.VkShift), strokes[5]);
    }

    [Fact]
    public void SurrogatePairs_AreSentAsTwoUnits()
    {
        IReadOnlyList<KeyStroke> strokes = KeyboardSequenceBuilder.ForText("😀");
        Assert.Equal(4, strokes.Count);
        Assert.True(char.IsHighSurrogate(strokes[0].Unicode));
        Assert.True(char.IsLowSurrogate(strokes[2].Unicode));
    }

    [Fact]
    public void Repeat_AndChord()
    {
        Assert.Equal(6, KeyboardSequenceBuilder.Repeat(KeyboardSequenceBuilder.VkBack, 3).Count);
        Assert.Empty(KeyboardSequenceBuilder.Repeat(KeyboardSequenceBuilder.VkBack, 0));
        IReadOnlyList<KeyStroke> chord = KeyboardSequenceBuilder.Chord(KeyboardSequenceBuilder.VkControl, 0x43);
        Assert.Equal(new[]
        {
            KeyStroke.Down(KeyboardSequenceBuilder.VkControl), KeyStroke.Down(0x43), KeyStroke.Up(0x43), KeyStroke.Up(KeyboardSequenceBuilder.VkControl),
        }, chord);
    }
}
