// Ciclo di dettatura completo: licenza → ascolto → trascrizione → correzione → inserimento → azioni.
// Spec §5.2–§5.12, §8.1 (dettatura bloccata a prova scaduta), §10 (audio solo in memoria),
// §4.2 (modelli caricati alla prima dettatura e scaricati per inattività). Aggiunto il 22/10/2026 alle 11:32.
//
// Streaming (Spec §11, API 2; aggiunto il 22/10/2026 alle 16:15): all'avvio dell'ascolto, dopo il precaricamento dei
// modelli, si apre un flusso zz_stream_* e ogni blocco del microfono gli viene inoltrato dal thread di cattura (i blocchi
// arrivati prima dell'apertura si inoltrano all'aggancio); allo stop resta solo zz_stream_finish. Il buffer in memoria
// resta: serve per la riscrittura (Spec §5.10, sempre su zz_rewrite) e per il ripiego su zz_dictate se il flusso non si
// apre o un feed fallisce. La macchina a stati (Spec §5.2) non cambia.
using Zzitto.Core.Licensing;
using Zzitto.Platform.Audio;
using Zzitto.Platform.Engine;
using Zzitto.Platform.Storage;

namespace Zzitto.Platform.Dictation;

/// <summary>Avvisi brevi per l'utente, mostrati con colore dell'icona o fumetto (Spec §2.5, §2.6).</summary>
public enum DictationNotice
{
    PurchaseRequired,
    ClipboardFallback,
    CorrectionFallback,
    NoSpeech,
    MaxDurationReached,
    MicrophoneError,
    MicrophoneAccessDenied,
    EngineError,
    ModelIntegrityError,
    InsertionFailed,
    CommandExecuted,
    Undone,
    NothingToUndo,
}

public sealed class DictationController : IDisposable
{
    /// <summary>Tempo trascorso in Errore prima di tornare a Inattivo.</summary>
    public static readonly TimeSpan ErrorDisplayTime = TimeSpan.FromMilliseconds(2500);

    private readonly IDictationEngine? _engine;
    private readonly IAudioSource _audio;
    private readonly IVoiceActivityDetectorFactory _vadFactory;
    private readonly ITextTarget _target;
    private readonly ILicenseService _license;
    private readonly ISettingsProvider _settings;
    private readonly IHistoryStore _history;
    private readonly TimeProvider _time;
    private readonly Action? _onListeningStarted;
    private readonly object _sessionLock = new();
    private Session? _session;
    private CancellationTokenSource? _unloadCts;
    private bool _disposed;

    public DictationController(
        IDictationEngine? engine,
        IAudioSource audio,
        IVoiceActivityDetectorFactory vadFactory,
        ITextTarget target,
        ILicenseService license,
        ISettingsProvider settings,
        IHistoryStore history,
        Action? onListeningStarted = null,
        TimeProvider? time = null)
    {
        _engine = engine;
        _audio = audio ?? throw new ArgumentNullException(nameof(audio));
        _vadFactory = vadFactory ?? throw new ArgumentNullException(nameof(vadFactory));
        _target = target ?? throw new ArgumentNullException(nameof(target));
        _license = license ?? throw new ArgumentNullException(nameof(license));
        _settings = settings ?? throw new ArgumentNullException(nameof(settings));
        _history = history ?? throw new ArgumentNullException(nameof(history));
        _onListeningStarted = onListeningStarted;
        _time = time ?? TimeProvider.System;
    }

    public DictationStateMachine StateMachine { get; } = new();

    public DictationState State => StateMachine.State;

    public event EventHandler<DictationNotice>? Notice;

    /// <summary>La prova è scaduta: l'icona porta alla schermata di acquisto (Spec §8.1).</summary>
    public event EventHandler? PurchaseRequired;

    /// <summary>Task dell'ultima elaborazione (per i test e per attendere la fine prima di uscire).</summary>
    public Task Completion { get; private set; } = Task.CompletedTask;

    /// <summary>Clic sull'icona o scorciatoia: avvia l'ascolto, oppure lo ferma se è in corso (Spec §5.2).</summary>
    public Task ToggleAsync()
    {
        switch (StateMachine.State)
        {
            case DictationState.Idle:
            case DictationState.Error:
                StartListening();
                return Task.CompletedTask;
            case DictationState.Listening:
                return StopListeningAsync(maxDurationReached: false);
            default:
                // Durante l'elaborazione un tocco non fa nulla; per interrompere c'è "Annulla".
                return Task.CompletedTask;
        }
    }

    /// <summary>Annulla in qualsiasi stato (Spec §5.2).</summary>
    public void Cancel()
    {
        Session? session;
        lock (_sessionLock)
        {
            session = _session;
        }

        switch (StateMachine.State)
        {
            case DictationState.Listening:
                if (session is not null && session.TryBeginStop())
                {
                    _audio.Stop();
                    // Spec §11: il flusso si annulla senza risultato (in background: zz_stream_abort può attendere il motore).
                    AbortInBackground(session.TakeStream(out _));
                    session.Buffer.Clear();
                    session.Cancellation.Cancel();
                    StateMachine.FireIf(DictationState.Listening, DictationTrigger.Cancel);
                    EndSession(session);
                }

                break;
            case DictationState.Transcribing:
            case DictationState.Correcting:
                // Il motore riceve zz_cancel e la dettatura torna a Inattivo.
                try
                {
                    session?.Cancellation.Cancel();
                }
                catch (ObjectDisposedException)
                {
                    // La dettatura è terminata nel frattempo.
                }

                break;
            case DictationState.Error:
                StateMachine.Fire(DictationTrigger.Cancel);
                break;
        }
    }

    /// <summary>Annulla l'ultimo inserimento con un tocco (Spec §5.4, §5.10).</summary>
    public async Task<bool> UndoLastInsertAsync()
    {
        if (StateMachine.State != DictationState.Idle)
        {
            return false;
        }

        bool ok = await _target.UndoLastInsertAsync(CancellationToken.None).ConfigureAwait(false);
        RaiseNotice(ok ? DictationNotice.Undone : DictationNotice.NothingToUndo);
        return ok;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        Cancel();
        _unloadCts?.Cancel();
        _unloadCts?.Dispose();
        _unloadCts = null;
    }

    private void StartListening()
    {
        if (_disposed)
        {
            return;
        }

        // Spec §8.1: allo scadere della prova la dettatura si blocca e si va all'acquisto.
        if (!_license.Current.CanDictate)
        {
            RaiseNotice(DictationNotice.PurchaseRequired);
            PurchaseRequired?.Invoke(this, EventArgs.Empty);
            return;
        }

        if (_engine is null)
        {
            Fail(DictationNotice.EngineError);
            return;
        }

        ZzittoSettings settings = _settings.Current;
        Session session;
        lock (_sessionLock)
        {
            if (_session is not null)
            {
                return;
            }

            IVoiceActivityDetector vad;
            try
            {
                vad = _vadFactory.Create(settings.SilenceTimeoutMs);
            }
            catch (Exception)
            {
                Fail(DictationNotice.EngineError);
                return;
            }

            session = new Session(settings, vad, IAudioSource.TargetSampleRate * settings.MaxDurationSeconds);
            _session = session;
        }

        if (!StateMachine.Fire(DictationTrigger.Start))
        {
            EndSession(session);
            return;
        }

        CancelPendingUnload();
        try
        {
            // Spec §11: l'ascolto parte subito; il campo e la selezione si leggono in parallelo.
            _audio.Start(samples => OnSamples(session, samples), ex => OnAudioError(session, ex));
        }
        catch (Exception ex)
        {
            EndSession(session);
            Fail(ex is MicrophoneException { AccessDenied: true } ? DictationNotice.MicrophoneAccessDenied : DictationNotice.MicrophoneError);
            return;
        }

        _onListeningStarted?.Invoke();
        Task<TargetContext> context = _target.CaptureAsync(settings.ClipboardSelectionFallback, session.Cancellation.Token);
        session.Context = context;
        session.Preload = OpenStreamAsync(session, context, session.Cancellation.Token);
    }

    private void OnSamples(Session session, ReadOnlySpan<float> samples)
    {
        if (session.IsStopping)
        {
            return;
        }

        // Spec §10: audio solo in memoria; Spec §11: lo stesso blocco va subito al flusso del motore.
        bool room = session.AppendAndFeed(samples);
        bool silenceAfterSpeech = session.Vad.Process(samples);
        if (!room)
        {
            // Spec §5.2: durata massima raggiunta.
            _ = StopListeningAsync(maxDurationReached: true);
        }
        else if (silenceAfterSpeech)
        {
            // Spec §5.2: stop automatico dopo il silenzio configurato.
            _ = StopListeningAsync(maxDurationReached: false);
        }
    }

    private void OnAudioError(Session session, Exception error)
    {
        if (!session.TryBeginStop())
        {
            return;
        }

        _ = Task.Run(() =>
        {
            _audio.Stop();
            AbortInBackground(session.TakeStream(out _));
            session.Buffer.Clear();
            EndSession(session);
            Fail(error is MicrophoneException { AccessDenied: true } ? DictationNotice.MicrophoneAccessDenied : DictationNotice.MicrophoneError);
        });
    }

    private async Task StopListeningAsync(bool maxDurationReached)
    {
        Session? session;
        lock (_sessionLock)
        {
            session = _session;
        }

        if (session is null || !session.TryBeginStop())
        {
            return;
        }

        // Stop attende il thread di cattura: mai dal thread di cattura stesso.
        await Task.Run(_audio.Stop).ConfigureAwait(false);
        if (!StateMachine.FireIf(DictationState.Listening, DictationTrigger.Stop))
        {
            session.Buffer.Clear();
            EndSession(session);
            return;
        }

        if (maxDurationReached)
        {
            RaiseNotice(DictationNotice.MaxDurationReached);
        }

        Task processing = ProcessAsync(session);
        Completion = processing;
        await processing.ConfigureAwait(false);
    }

    private async Task ProcessAsync(Session session)
    {
        CancellationToken token = session.Cancellation.Token;
        IDictationEngine engine = _engine!;
        try
        {
            TargetContext context = await (session.Context ?? Task.FromResult(new TargetContext())).ConfigureAwait(false);
            (float[] samples, int count) = session.Buffer.Snapshot();
            if (count == 0)
            {
                throw new ZzEngineException(ZzStatus.NoSpeech, "Nessun audio.");
            }

            // Precaricamento e apertura del flusso (non lanciano mai): dopo, nessun flusso viene più agganciato.
            if (session.Preload is not null)
            {
                await session.Preload.ConfigureAwait(false);
            }

            IDictationStream? stream = session.TakeStream(out bool streamFaulted);

            DictationOptions options = session.Settings.ToDictationOptions();
            void OnStage(EngineStage stage)
            {
                if (stage == EngineStage.Correcting)
                {
                    StateMachine.FireIf(DictationState.Transcribing, DictationTrigger.StageCorrecting);
                }
            }

            EngineResult result;
            if (stream is not null && !streamFaulted && !stream.IsFaulted && !context.IsRewrite)
            {
                // Spec §11: l'audio è già nel motore, resta da trascrivere solo l'ultimo pezzo.
                result = await stream.FinishAsync(OnStage, token).ConfigureAwait(false);
            }
            else
            {
                // Flusso non aperto o feed fallito: ripiego su zz_dictate con l'audio in memoria (stesso risultato).
                // Spec §5.10: la riscrittura usa sempre zz_rewrite.
                stream?.Abort();
                await engine.LoadAsync(token).ConfigureAwait(false);
                result = context.IsRewrite
                    ? await engine.RewriteAsync(context.SelectedText!, samples, count, options, OnStage, token).ConfigureAwait(false)
                    : await engine.DictateAsync(samples, count, options, OnStage, token).ConfigureAwait(false);
            }

            // Spec §10: l'audio si distrugge subito dopo la trascrizione.
            session.Buffer.Clear();

            if (!StateMachine.Fire(DictationTrigger.ResultReady))
            {
                return; // annullata nel frattempo
            }

            await ApplyResultAsync(context, result, session).ConfigureAwait(false);
            StateMachine.FireIf(DictationState.Inserting, DictationTrigger.Inserted);
        }
        catch (OperationCanceledException)
        {
            StateMachine.Fire(DictationTrigger.Cancel);
        }
        catch (ZzEngineException ex) when (ex.Status == ZzStatus.NoSpeech)
        {
            StateMachine.Fire(DictationTrigger.Cancel);
            RaiseNotice(DictationNotice.NoSpeech);
        }
        catch (ZzEngineException ex) when (ex.Status == ZzStatus.Cancelled)
        {
            StateMachine.Fire(DictationTrigger.Cancel);
        }
        catch (ZzEngineException ex)
        {
            Fail(ex.Status == ZzStatus.ModelIntegrity ? DictationNotice.ModelIntegrityError : DictationNotice.EngineError);
        }
        catch (Exception)
        {
            Fail(DictationNotice.EngineError);
        }
        finally
        {
            session.Buffer.Clear();
            EndSession(session);
            ScheduleUnload();
        }
    }

    private async Task ApplyResultAsync(TargetContext context, EngineResult result, Session session)
    {
        CancellationToken token = CancellationToken.None; // l'inserimento non si interrompe a metà
        bool commandExecuted = false;

        // Spec §5.9: "annulla" → annulla l'ultimo inserimento, nessun testo.
        if (result.Actions.HasFlag(EngineActions.UndoLastInsert))
        {
            bool undone = await _target.UndoLastInsertAsync(token).ConfigureAwait(false);
            RaiseNotice(undone ? DictationNotice.Undone : DictationNotice.NothingToUndo);
        }

        // Spec §5.9: "cancella l'ultima frase" senza frasi nella dettatura → si cancella nel campo, prima del nuovo testo.
        if (result.Actions.HasFlag(EngineActions.DeletePreviousSentence))
        {
            commandExecuted |= await _target.DeletePreviousSentenceAsync(context, token).ConfigureAwait(false);
        }

        if (!string.IsNullOrEmpty(result.FinalText))
        {
            InsertionOutcome outcome = await _target.InsertAsync(context, result.FinalText, token).ConfigureAwait(false);
            if (!outcome.Success)
            {
                RaiseNotice(DictationNotice.InsertionFailed);
            }
            else if (outcome.UsedClipboard)
            {
                // Spec §2.6: testo negli appunti con avviso "premi Ctrl+V".
                RaiseNotice(DictationNotice.ClipboardFallback);
            }

            // Spec §5.6: cronologia (mai per i campi password).
            if (session.Settings.HistoryEnabled && !context.IsPassword)
            {
                _history.Add(new HistoryEntry(_time.GetUtcNow(), result.RawText, result.FinalText, context.IsRewrite));
            }
        }

        // Spec §5.9: "invia" → Invio dopo l'inserimento, se il campo lo consente.
        if (result.Actions.HasFlag(EngineActions.Send))
        {
            commandExecuted |= await _target.PressSendAsync(context, token).ConfigureAwait(false);
        }

        if (commandExecuted)
        {
            RaiseNotice(DictationNotice.CommandExecuted);
        }

        // Spec §5.3: correzione fallita o in timeout → testo grezzo, segnalato.
        if (result.UsedFallback)
        {
            RaiseNotice(DictationNotice.CorrectionFallback);
        }
    }

    private async Task OpenStreamAsync(Session session, Task<TargetContext> context, CancellationToken token)
    {
        if (_engine is null)
        {
            return;
        }

        // Spec §4.2: i modelli si caricano alla prima dettatura, mentre l'utente parla.
        try
        {
            if (!_engine.IsLoaded)
            {
                await _engine.LoadAsync(token).ConfigureAwait(false);
            }
        }
        catch (Exception)
        {
            // L'errore riemerge con il messaggio corretto nel LoadAsync del ripiego in ProcessAsync.
            return;
        }

        // Spec §11: flusso aperto durante l'ascolto. Con una selezione (riscrittura, Spec §5.10) non serve.
        IDictationStream? stream = null;
        try
        {
            TargetContext target = await context.ConfigureAwait(false);
            if (target.IsRewrite || token.IsCancellationRequested)
            {
                return;
            }

            stream = await _engine.BeginStreamAsync(session.Settings.ToDictationOptions(), token).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Apertura non riuscita o annullata: ProcessAsync ripiega su zz_dictate (o riporta l'errore).
            return;
        }

        if (!session.TryAttachStream(stream))
        {
            // Ascolto già annullato o elaborazione già partita senza flusso.
            stream.Abort();
        }
    }

    private static void AbortInBackground(IDictationStream? stream)
    {
        if (stream is not null)
        {
            _ = Task.Run(stream.Abort);
        }
    }

    private void Fail(DictationNotice notice)
    {
        StateMachine.Fire(DictationTrigger.Fail);
        RaiseNotice(notice);
        _ = ResetAfterErrorAsync();
    }

    private async Task ResetAfterErrorAsync()
    {
        await Task.Delay(ErrorDisplayTime, _time).ConfigureAwait(false);
        StateMachine.FireIf(DictationState.Error, DictationTrigger.Reset);
    }

    private int _prewarming;

    /// <summary>
    /// Spec §11, §4.2 (22/10/2026 22:59): carica i modelli in anticipo (all'avvio e quando cambia il campo attivo),
    /// così la dettatura non aspetta il caricamento. Poi parte il normale scaricamento per inattività.
    /// </summary>
    public void Prewarm()
    {
        if (_engine is null || _disposed || _engine.IsLoaded || StateMachine.State != DictationState.Idle)
        {
            return;
        }

        if (Interlocked.Exchange(ref _prewarming, 1) == 1)
        {
            return;
        }

        CancelPendingUnload();
        _ = Task.Run(async () =>
        {
            try
            {
                await _engine.LoadAsync().ConfigureAwait(false);
                if (StateMachine.State == DictationState.Idle)
                {
                    ScheduleUnload();
                }
            }
            catch (Exception ex) when (ex is ZzEngineException or ObjectDisposedException or OperationCanceledException)
            {
                // L'errore riemerge, con il messaggio giusto, alla prossima dettatura.
            }
            finally
            {
                Interlocked.Exchange(ref _prewarming, 0);
            }
        });
    }

    private void ScheduleUnload()
    {
        if (_engine is null || _disposed)
        {
            return;
        }

        CancelPendingUnload();
        var cts = new CancellationTokenSource();
        _unloadCts = cts;
        TimeSpan delay = TimeSpan.FromMinutes(_settings.Current.ModelUnloadMinutes);
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(delay, _time, cts.Token).ConfigureAwait(false);
                if (StateMachine.State == DictationState.Idle && _engine.IsLoaded)
                {
                    await _engine.UnloadAsync(cts.Token).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                // Una nuova dettatura è iniziata.
            }
            catch (ObjectDisposedException)
            {
                // Motore già chiuso all'uscita.
            }
            catch (ZzEngineException)
            {
                // Scaricamento non riuscito: si riproverà dopo la prossima dettatura.
            }
        });
    }

    private void CancelPendingUnload()
    {
        CancellationTokenSource? cts = Interlocked.Exchange(ref _unloadCts, null);
        if (cts is not null)
        {
            cts.Cancel();
            cts.Dispose();
        }
    }

    private void EndSession(Session session)
    {
        lock (_sessionLock)
        {
            if (ReferenceEquals(_session, session))
            {
                _session = null;
            }
        }

        session.Dispose();
    }

    private void RaiseNotice(DictationNotice notice) => Notice?.Invoke(this, notice);

    private sealed class Session : IDisposable
    {
        private readonly object _feedLock = new();
        private int _stopping;
        private int _disposed;
        private IDictationStream? _stream;
        private bool _streamClosed;
        private bool _streamFaulted;

        public Session(ZzittoSettings settings, IVoiceActivityDetector vad, int maxSamples)
        {
            Settings = settings;
            Vad = vad;
            Buffer = new PcmBuffer(maxSamples);
        }

        public ZzittoSettings Settings { get; }

        public IVoiceActivityDetector Vad { get; }

        public PcmBuffer Buffer { get; }

        public CancellationTokenSource Cancellation { get; } = new();

        public Task<TargetContext>? Context { get; set; }

        public Task? Preload { get; set; }

        public bool IsStopping => Volatile.Read(ref _stopping) != 0;

        /// <summary>true solo per il primo che ferma l'ascolto (silenzio, durata massima, tocco, annulla).</summary>
        public bool TryBeginStop() => Interlocked.Exchange(ref _stopping, 1) == 0;

        /// <summary>
        /// Thread di cattura: aggiunge il blocco al buffer e inoltra al flusso la parte accettata (Spec §5.2, §11).
        /// Restituisce false se la durata massima è stata raggiunta.
        /// </summary>
        public bool AppendAndFeed(ReadOnlySpan<float> samples)
        {
            lock (_feedLock)
            {
                int before = Buffer.Count;
                bool room = Buffer.Append(samples);
                int taken = Buffer.Count - before;
                if (_stream is not null && !_streamFaulted && taken > 0 && !_stream.Feed(samples[..taken]))
                {
                    _streamFaulted = true;
                }

                return room;
            }
        }

        /// <summary>
        /// Aggancia il flusso appena aperto inoltrandogli l'audio già catturato; da qui in poi i blocchi arrivano dal
        /// thread di cattura. false se il flusso non serve più (ascolto annullato o elaborazione già partita).
        /// </summary>
        public bool TryAttachStream(IDictationStream stream)
        {
            lock (_feedLock)
            {
                if (_streamClosed)
                {
                    return false;
                }

                (float[] data, int count) = Buffer.Snapshot();
                if (count > 0 && !stream.Feed(new ReadOnlySpan<float>(data, 0, count)))
                {
                    _streamFaulted = true;
                }

                _stream = stream;
                return true;
            }
        }

        /// <summary>Stacca il flusso (se c'è) e impedisce di agganciarne altri; il chiamante lo chiude o lo annulla.</summary>
        public IDictationStream? TakeStream(out bool faulted)
        {
            lock (_feedLock)
            {
                _streamClosed = true;
                IDictationStream? stream = _stream;
                _stream = null;
                faulted = _streamFaulted;
                return stream;
            }
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0)
            {
                return;
            }

            // Un flusso mai chiuso (errore prima dello stop) si annulla: il motore torna libero (Spec §11).
            AbortInBackground(TakeStream(out _));
            Buffer.Clear();
            Vad.Dispose();
            Cancellation.Dispose();
        }
    }
}
