// Test del ciclo di dettatura con motore, microfono e campo finti (Spec §5.2–§5.12, §8.1, §10, §12).
// Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Core.Licensing;
using Zzitto.Platform.Audio;
using Zzitto.Platform.Dictation;
using Zzitto.Platform.Engine;
using Zzitto.Platform.Input;
using Zzitto.Platform.Storage;

namespace Zzitto.Tests;

public class DictationControllerTests
{
    private readonly FakeEngine _engine = new();
    private readonly FakeAudio _audio = new();
    private readonly FakeVadFactory _vad = new();
    private readonly FakeTarget _target = new();
    private readonly FakeLicense _license = new();
    private readonly FakeSettings _settings = new();
    private readonly FakeHistory _history = new();
    private readonly List<DictationNotice> _notices = new();

    private DictationController Create(IDictationEngine? engine = null)
    {
        var controller = new DictationController(engine ?? _engine, _audio, _vad, _target, _license, _settings, _history);
        controller.Notice += (_, n) =>
        {
            lock (_notices)
            {
                _notices.Add(n);
            }
        };
        return controller;
    }

    [Fact]
    public async Task TapStartsListening_SilenceStops_TextIsInserted()
    {
        using DictationController controller = Create();
        var states = new List<DictationState>();
        controller.StateMachine.StateChanged += (_, e) =>
        {
            lock (states)
            {
                states.Add(e.Current);
            }
        };

        await controller.ToggleAsync();
        Assert.Equal(DictationState.Listening, controller.State);
        Assert.True(_audio.Running);
        Assert.Equal(2000, _vad.LastSilenceMs);

        _audio.Push(16000);
        _vad.Vad.EndOnNextBlock = true; // Spec §5.2: stop automatico dopo il silenzio
        _audio.Push(1600);

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Inserted.Count == 1);
        Assert.Equal("Prova.", _target.Inserted[0]);
        Assert.Equal(17600, _engine.LastSampleCount);
        Assert.Contains(DictationState.Correcting, states);
        Assert.Single(_history.Entries);
        Assert.Equal("ehm prova", _history.Entries[0].RawText);
        Assert.True(_vad.Vad.Disposed);
    }

    [Fact]
    public async Task SecondTapStopsListening()
    {
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(8000);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Inserted.Count == 1);
        Assert.False(_audio.Running);
        Assert.Equal(1, _engine.DictateCalls);
    }

    [Fact]
    public async Task TrialExpired_BlocksDictationAndAsksForPurchase()
    {
        // Spec §8.1: allo scadere della prova la dettatura si blocca e l'icona porta all'acquisto.
        _license.Current = new LicenseState(LicenseStatus.TrialExpired);
        using DictationController controller = Create();
        bool purchaseRequested = false;
        controller.PurchaseRequired += (_, _) => purchaseRequested = true;

        await controller.ToggleAsync();

        Assert.True(purchaseRequested);
        Assert.Equal(DictationState.Idle, controller.State);
        Assert.False(_audio.Running);
        Assert.Contains(DictationNotice.PurchaseRequired, _notices);
    }

    [Fact]
    public async Task UnknownLicense_BlocksDictation()
    {
        _license.Current = new LicenseState(LicenseStatus.Unknown);
        using DictationController controller = Create();
        await controller.ToggleAsync();
        Assert.False(_audio.Running);
        Assert.Contains(DictationNotice.PurchaseRequired, _notices);
    }

    [Fact]
    public async Task MicrophoneDenied_GoesToError()
    {
        _audio.StartError = new MicrophoneException("negato", unchecked((int)0x80070005));
        using DictationController controller = Create();

        await controller.ToggleAsync();

        Assert.Equal(DictationState.Error, controller.State);
        Assert.Contains(DictationNotice.MicrophoneAccessDenied, _notices);
    }

    [Fact]
    public async Task MissingEngine_GoesToError()
    {
        using var controller = new DictationController(null, _audio, _vad, _target, _license, _settings, _history);
        await controller.ToggleAsync();
        Assert.Equal(DictationState.Error, controller.State);
        Assert.False(_audio.Running);
    }

    [Fact]
    public async Task NoSpeech_ReturnsToIdleWithNotice()
    {
        _engine.Error = new ZzEngineException(ZzStatus.NoSpeech, "nessun parlato");
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _notices.Contains(DictationNotice.NoSpeech));
        Assert.Empty(_target.Inserted);
        Assert.Empty(_history.Entries);
    }

    [Fact]
    public async Task EngineFailure_GoesToErrorThenIdle()
    {
        _engine.Error = new ZzEngineException(ZzStatus.ModelIntegrity, "hash");
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => _notices.Contains(DictationNotice.ModelIntegrityError));
        await TestWait.UntilAsync(() => controller.State == DictationState.Idle, timeoutMs: 6000);
    }

    [Fact]
    public async Task CancelWhileListening_DiscardsAudio()
    {
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(16000);

        controller.Cancel();

        Assert.Equal(DictationState.Idle, controller.State);
        Assert.False(_audio.Running);
        await Task.Delay(50);
        Assert.Equal(0, _engine.DictateCalls);
        Assert.True(_vad.Vad.Disposed);
    }

    [Fact]
    public async Task CancelWhileTranscribing_ReturnsToIdle()
    {
        _engine.Gate = new TaskCompletionSource();
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        _ = controller.ToggleAsync();
        await TestWait.UntilAsync(() => controller.State == DictationState.Transcribing);

        controller.Cancel();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle);
        Assert.Empty(_target.Inserted);
    }

    [Fact]
    public async Task MaxDuration_StopsListening()
    {
        _settings.Current = new ZzittoSettings { MaxDurationSeconds = 10 };
        using DictationController controller = Create();
        await controller.ToggleAsync();

        _audio.Push(16000 * 11); // oltre il limite

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Inserted.Count == 1);
        Assert.Equal(16000 * 10, _engine.LastSampleCount);
        Assert.Contains(DictationNotice.MaxDurationReached, _notices);
    }

    [Fact]
    public async Task Selection_TriggersRewrite()
    {
        // Spec §5.10: testo selezionato + istruzione a voce.
        _target.Context = new TargetContext { Window = new IntPtr(1), SelectedText = "ciao a tutti" };
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Inserted.Count == 1);
        Assert.Equal(1, _engine.RewriteCalls);
        Assert.Equal(0, _engine.DictateCalls);
        Assert.Equal("ciao a tutti", _engine.LastSelectedText);
        Assert.True(_history.Entries[0].IsRewrite);
    }

    [Fact]
    public async Task Actions_RunInTheRightOrder()
    {
        // Spec §5.9: annulla e "cancella l'ultima frase" prima del testo, "invia" dopo.
        _engine.Result = new EngineResult("x", "Testo.", EngineActions.UndoLastInsert | EngineActions.DeletePreviousSentence | EngineActions.Send,
            false, false, TimeSpan.Zero, TimeSpan.Zero);
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Calls.Contains("send"));
        Assert.Equal(new[] { "capture", "undo", "delete-sentence", "insert", "send" }, _target.Calls);
        Assert.Contains(DictationNotice.CommandExecuted, _notices);
    }

    [Fact]
    public async Task UndoOnly_InsertsNothing()
    {
        _engine.Result = new EngineResult("annulla", string.Empty, EngineActions.UndoLastInsert, false, false, TimeSpan.Zero, TimeSpan.Zero);
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Calls.Contains("undo"));
        Assert.Empty(_target.Inserted);
        Assert.Empty(_history.Entries);
    }

    [Fact]
    public async Task ClipboardFallback_RaisesNotice()
    {
        // Spec §2.6: inserimento non riuscito → appunti con avviso "premi Ctrl+V".
        _target.Outcome = new InsertionOutcome(InsertionMethod.Clipboard);
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => _notices.Contains(DictationNotice.ClipboardFallback));
    }

    [Fact]
    public async Task CorrectionFallback_RaisesNotice()
    {
        // Spec §5.3: testo grezzo inserito e segnalato.
        _engine.Result = new EngineResult("ciao", "ciao", EngineActions.None, true, false, TimeSpan.Zero, TimeSpan.Zero);
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => _notices.Contains(DictationNotice.CorrectionFallback));
    }

    [Fact]
    public async Task PasswordField_IsNotStoredInHistory()
    {
        _target.Context = new TargetContext
        {
            Window = new IntPtr(1),
            Field = new FieldFacts(50004, "Win32", "Edit", IsPassword: true, false, true, false, 1),
        };
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Inserted.Count == 1);
        Assert.Empty(_history.Entries);
    }

    [Fact]
    public async Task HistoryDisabled_IsNotStored()
    {
        _settings.Current = new ZzittoSettings { HistoryEnabled = false };
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Inserted.Count == 1);
        Assert.Empty(_history.Entries);
    }

    [Fact]
    public async Task OptionsComeFromSettings()
    {
        _settings.Current = new ZzittoSettings { Mode = CorrectionMode.Formal, Translate = TranslateTarget.Spanish, Language = "it" };
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        await controller.ToggleAsync();

        await TestWait.UntilAsync(() => controller.State == DictationState.Idle && _target.Inserted.Count == 1);
        Assert.Equal(CorrectionMode.Formal, _engine.LastOptions!.Mode);
        Assert.Equal(TranslateTarget.Spanish, _engine.LastOptions.Translate);
    }

    [Fact]
    public async Task TapDuringProcessing_IsIgnored()
    {
        _engine.Gate = new TaskCompletionSource();
        using DictationController controller = Create();
        await controller.ToggleAsync();
        _audio.Push(1600);
        _ = controller.ToggleAsync();
        await TestWait.UntilAsync(() => controller.State == DictationState.Transcribing);

        await controller.ToggleAsync();
        Assert.Equal(DictationState.Transcribing, controller.State);

        _engine.Gate.SetResult();
        await TestWait.UntilAsync(() => controller.State == DictationState.Idle);
        Assert.Equal(1, _engine.DictateCalls);
    }
}
