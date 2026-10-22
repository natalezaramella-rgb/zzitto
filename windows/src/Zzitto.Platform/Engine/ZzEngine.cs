// Wrapper asincrono e sicuro del motore nativo. Spec §4.2 (AI mai sul thread UI, modelli caricati alla
// prima dettatura e scaricati per inattività), §4.3, §5.2, §5.10. Aggiunto il 22/10/2026 alle 11:32.
//
// Garanzie:
//  - una sola operazione nativa alla volta (il motore restituirebbe ZZ_ERR_BUSY): le chiamate si accodano;
//  - ogni chiamata bloccante gira su un thread dedicato, mai sul chiamante;
//  - CancellationToken -> zz_cancel (thread-safe per contratto);
//  - Dispose attende l'operazione in corso prima di zz_engine_destroy.
//  - Streaming (API 2, Spec §11; aggiunto il 22/10/2026 alle 16:15): un flusso aperto occupa il motore dall'apertura
//    a FinishAsync/Abort (il motore restituirebbe ZZ_ERR_BUSY), quindi tiene la stessa coda delle altre operazioni.
namespace Zzitto.Platform.Engine;

/// <summary>
/// Dettatura in streaming (zz_stream_*, Spec §11): il motore trascrive mentre l'utente parla.
/// Feed dal thread di cattura; FinishAsync o Abort una sola volta (Dispose equivale ad Abort se il flusso è aperto).
/// </summary>
public interface IDictationStream : IDisposable
{
    /// <summary>true dopo il primo zz_stream_feed fallito: il flusso non accetta più audio.</summary>
    bool IsFaulted { get; }

    /// <summary>
    /// Inoltra un blocco PCM 16 kHz mono senza bloccare (zz_stream_feed copia i campioni). Mai eccezioni:
    /// restituisce false se il flusso è chiuso o in errore.
    /// </summary>
    bool Feed(ReadOnlySpan<float> pcm16kMono);

    /// <summary>
    /// Chiude il flusso e restituisce lo stesso risultato di <see cref="IDictationEngine.DictateAsync"/>, con la stessa
    /// gestione degli errori (ZzEngineException, OperationCanceledException). Gira su un thread dedicato.
    /// </summary>
    Task<EngineResult> FinishAsync(Action<EngineStage>? onStage, CancellationToken cancellationToken = default);

    /// <summary>Annulla e libera il flusso senza risultato. Idempotente, mai eccezioni.</summary>
    void Abort();
}

public interface IDictationEngine : IDisposable
{
    bool IsLoaded { get; }

    Task LoadAsync(CancellationToken cancellationToken = default);

    Task UnloadAsync(CancellationToken cancellationToken = default);

    Task SetVocabularyAsync(IReadOnlyList<string> dictionary, IReadOnlyList<KeyValuePair<string, string>> shortcuts,
        CancellationToken cancellationToken = default);

    Task<EngineResult> DictateAsync(float[] pcm16kMono, int sampleCount, DictationOptions options,
        Action<EngineStage>? onStage, CancellationToken cancellationToken = default);

    Task<EngineResult> RewriteAsync(string selectedText, float[] pcm16kMono, int sampleCount, DictationOptions options,
        Action<EngineStage>? onStage, CancellationToken cancellationToken = default);

    Task<EngineResult> ProcessTextAsync(string rawText, DictationOptions options, CancellationToken cancellationToken = default);

    /// <summary>
    /// Apre un flusso di dettatura (zz_stream_begin, Spec §11) su un thread dedicato: carica i modelli se serve.
    /// Errori come <see cref="DictateAsync"/>. Il motore resta occupato fino a FinishAsync o Abort.
    /// </summary>
    Task<IDictationStream> BeginStreamAsync(DictationOptions options, CancellationToken cancellationToken = default);
}

public sealed class ZzEngine : IDictationEngine
{
    private readonly IZzNative _native;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private IntPtr _handle;
    private int _disposed;
    private ZzDictationStream? _activeStream;

    private ZzEngine(IZzNative native, IntPtr handle)
    {
        _native = native;
        _handle = handle;
    }

    /// <summary>Versione del motore (es. "zzitto-engine MOCK api=1 ...").</summary>
    public string Version => _native.VersionString();

    public bool IsLoaded => _handle != IntPtr.Zero && Volatile.Read(ref _disposed) == 0 && _native.IsLoaded(_handle);

    /// <summary>Crea il motore verificando la versione del contratto (ZZ_ENGINE_API_VERSION).</summary>
    public static ZzEngine Create(IZzNative native, EngineConfig config)
    {
        ArgumentNullException.ThrowIfNull(native);
        ArgumentNullException.ThrowIfNull(config);

        uint api = native.ApiVersion();
        if (api != ZzNativeMethods.ExpectedApiVersion)
        {
            throw new ZzEngineException(ZzStatus.Internal,
                $"Versione del contratto del motore non supportata: {api} (attesa {ZzNativeMethods.ExpectedApiVersion}).");
        }

        ZzStatus status = native.CreateEngine(config, out IntPtr handle);
        if (status != ZzStatus.Ok || handle == IntPtr.Zero)
        {
            throw new ZzEngineException(status == ZzStatus.Ok ? ZzStatus.Internal : status,
                "Creazione del motore fallita: " + native.StatusString(status));
        }

        return new ZzEngine(native, handle);
    }

    public Task LoadAsync(CancellationToken cancellationToken = default) =>
        RunAsync(h => Check(_native.Load(h)), cancellationToken, cancellable: false);

    public Task UnloadAsync(CancellationToken cancellationToken = default) =>
        RunAsync(h => Check(_native.Unload(h)), cancellationToken, cancellable: false);

    public Task SetVocabularyAsync(IReadOnlyList<string> dictionary, IReadOnlyList<KeyValuePair<string, string>> shortcuts,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(dictionary);
        ArgumentNullException.ThrowIfNull(shortcuts);
        return RunAsync(h =>
        {
            Check(_native.SetDictionary(h, dictionary)); // Spec §5.5
            Check(_native.SetShortcuts(h, shortcuts));   // Spec §5.11
        }, cancellationToken, cancellable: false);
    }

    public Task<EngineResult> DictateAsync(float[] pcm16kMono, int sampleCount, DictationOptions options,
        Action<EngineStage>? onStage, CancellationToken cancellationToken = default)
    {
        ValidateAudio(pcm16kMono, sampleCount);
        ArgumentNullException.ThrowIfNull(options);
        return RunAsync(h =>
        {
            ZzStatus status = _native.Dictate(h, new ReadOnlySpan<float>(pcm16kMono, 0, sampleCount), options, onStage,
                out EngineResult? result);
            return TakeResult(status, result, cancellationToken);
        }, cancellationToken, cancellable: true);
    }

    public Task<EngineResult> RewriteAsync(string selectedText, float[] pcm16kMono, int sampleCount, DictationOptions options,
        Action<EngineStage>? onStage, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(selectedText);
        ValidateAudio(pcm16kMono, sampleCount);
        ArgumentNullException.ThrowIfNull(options);
        return RunAsync(h =>
        {
            // Spec §5.10: l'audio contiene l'istruzione, final_text sostituisce la selezione.
            ZzStatus status = _native.Rewrite(h, selectedText, new ReadOnlySpan<float>(pcm16kMono, 0, sampleCount), options,
                onStage, out EngineResult? result);
            return TakeResult(status, result, cancellationToken);
        }, cancellationToken, cancellable: true);
    }

    public Task<EngineResult> ProcessTextAsync(string rawText, DictationOptions options, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(rawText);
        ArgumentNullException.ThrowIfNull(options);
        return RunAsync(h =>
        {
            ZzStatus status = _native.ProcessText(h, rawText, options, out EngineResult? result);
            return TakeResult(status, result, cancellationToken);
        }, cancellationToken, cancellable: true);
    }

    public async Task<IDictationStream> BeginStreamAsync(DictationOptions options, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        bool handedOver = false;
        try
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            cancellationToken.ThrowIfCancellationRequested();
            IntPtr handle = _handle;

            // Spec §4.2, §11: zz_stream_begin può caricare i modelli, quindi thread dedicato. Non si collega zz_cancel
            // qui: un annullamento arrivato a flusso già aperto lo interromperebbe; si controlla il token al ritorno.
            IntPtr streamHandle = await Task.Factory.StartNew(() =>
            {
                ZzStatus status = _native.StreamBegin(handle, options, out IntPtr opened);
                if (status == ZzStatus.Cancelled)
                {
                    throw new OperationCanceledException("Dettatura annullata.", cancellationToken);
                }

                Check(status);
                if (opened == IntPtr.Zero)
                {
                    throw new ZzEngineException(ZzStatus.Internal, "Il motore non ha aperto il flusso.");
                }

                return opened;
            }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default).ConfigureAwait(false);

            if (cancellationToken.IsCancellationRequested || Volatile.Read(ref _disposed) != 0)
            {
                // Annullata o motore in chiusura durante l'apertura: il flusso non viene consegnato.
                _native.StreamAbort(streamHandle);
                ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
                throw new OperationCanceledException("Dettatura annullata.", cancellationToken);
            }

            var stream = new ZzDictationStream(this, handle, streamHandle);
            Volatile.Write(ref _activeStream, stream);
            handedOver = true; // il semaforo si rilascia in FinishAsync o Abort
            return stream;
        }
        finally
        {
            if (!handedOver)
            {
                _gate.Release();
            }
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        IntPtr handle = _handle;
        if (handle == IntPtr.Zero)
        {
            return;
        }

        _native.Cancel(handle);
        // Un flusso rimasto aperto occupa il motore: si annulla per poterlo distruggere (Spec §11).
        Volatile.Read(ref _activeStream)?.Abort();
        // Attende l'eventuale operazione in corso (annullata) prima di distruggere il motore.
        if (!_gate.Wait(TimeSpan.FromSeconds(15)))
        {
            // Meglio non liberare il motore che distruggerlo mentre un thread nativo lo usa.
            return;
        }

        try
        {
            _handle = IntPtr.Zero;
            _native.DestroyEngine(handle);
        }
        finally
        {
            _gate.Release();
        }
    }

    private static void ValidateAudio(float[] pcm, int sampleCount)
    {
        ArgumentNullException.ThrowIfNull(pcm);
        if (sampleCount < 0 || sampleCount > pcm.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(sampleCount));
        }
    }

    private EngineResult TakeResult(ZzStatus status, EngineResult? result, CancellationToken cancellationToken)
    {
        if (status == ZzStatus.Cancelled)
        {
            throw new OperationCanceledException("Dettatura annullata.", cancellationToken);
        }

        Check(status);
        return result ?? throw new ZzEngineException(ZzStatus.Internal, "Il motore non ha restituito un risultato.");
    }

    private void Check(ZzStatus status)
    {
        if (status != ZzStatus.Ok)
        {
            throw new ZzEngineException(status, _native.StatusString(status));
        }
    }

    private Task RunAsync(Action<IntPtr> work, CancellationToken cancellationToken, bool cancellable) =>
        RunAsync<bool>(h =>
        {
            work(h);
            return true;
        }, cancellationToken, cancellable);

    private async Task<T> RunAsync<T>(Func<IntPtr, T> work, CancellationToken cancellationToken, bool cancellable)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
            cancellationToken.ThrowIfCancellationRequested();
            IntPtr handle = _handle;

            // Spec §4.2: thread dedicato, mai il thread UI né un thread del pool bloccato a lungo.
            return await Task.Factory.StartNew(() =>
            {
                using CancellationTokenRegistration registration = cancellable
                    ? cancellationToken.Register(() => _native.Cancel(handle))
                    : default;
                return work(handle);
            }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }
    /// <summary>Chiamato una sola volta da FinishAsync o Abort: libera la coda delle operazioni.</summary>
    private void EndStream(ZzDictationStream stream)
    {
        Interlocked.CompareExchange(ref _activeStream, null, stream);
        _gate.Release();
    }

    /// <summary>Flusso aperto su zz_stream_*. Spec §11. Aggiunto il 22/10/2026 alle 16:15.</summary>
    private sealed class ZzDictationStream : IDictationStream
    {
        private readonly ZzEngine _owner;
        private readonly IntPtr _engineHandle;
        private readonly object _sync = new();
        private IntPtr _stream;
        private bool _faulted;

        public ZzDictationStream(ZzEngine owner, IntPtr engineHandle, IntPtr stream)
        {
            _owner = owner;
            _engineHandle = engineHandle;
            _stream = stream;
        }

        public bool IsFaulted
        {
            get
            {
                lock (_sync)
                {
                    return _faulted;
                }
            }
        }

        public bool Feed(ReadOnlySpan<float> pcm16kMono)
        {
            // Il lock impedisce che Finish/Abort liberino il flusso durante zz_stream_feed (che è breve: copia e ritorna).
            lock (_sync)
            {
                if (_stream == IntPtr.Zero || _faulted)
                {
                    return false;
                }

                try
                {
                    if (_owner._native.StreamFeed(_stream, pcm16kMono) == ZzStatus.Ok)
                    {
                        return true;
                    }
                }
                catch (Exception)
                {
                    // Thread di cattura: nessuna eccezione deve uscire; il chiamante ripiega su zz_dictate.
                }

                _faulted = true;
                return false;
            }
        }

        public async Task<EngineResult> FinishAsync(Action<EngineStage>? onStage, CancellationToken cancellationToken = default)
        {
            IntPtr stream = Detach();
            if (stream == IntPtr.Zero)
            {
                throw new InvalidOperationException("Il flusso di dettatura è già chiuso.");
            }

            try
            {
                // Spec §4.2: thread dedicato; CancellationToken -> zz_cancel, che interrompe anche il flusso.
                return await Task.Factory.StartNew(() =>
                {
                    if (cancellationToken.IsCancellationRequested)
                    {
                        _owner._native.StreamAbort(stream);
                        throw new OperationCanceledException("Dettatura annullata.", cancellationToken);
                    }

                    using CancellationTokenRegistration registration =
                        cancellationToken.Register(() => _owner._native.Cancel(_engineHandle));
                    ZzStatus status = _owner._native.StreamFinish(stream, onStage, out EngineResult? result);
                    return _owner.TakeResult(status, result, cancellationToken);
                }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default).ConfigureAwait(false);
            }
            finally
            {
                _owner.EndStream(this);
            }
        }

        public void Abort()
        {
            IntPtr stream = Detach();
            if (stream == IntPtr.Zero)
            {
                return;
            }

            try
            {
                // zz_cancel prima: il thread di trascrizione del motore si ferma al prossimo controllo e l'abort è rapido.
                _owner._native.Cancel(_engineHandle);
                _owner._native.StreamAbort(stream);
            }
            catch (Exception)
            {
                // Abort non lancia mai (si chiama anche da Dispose e dai percorsi di annullamento).
            }
            finally
            {
                _owner.EndStream(this);
            }
        }

        public void Dispose() => Abort();

        private IntPtr Detach()
        {
            lock (_sync)
            {
                IntPtr stream = _stream;
                _stream = IntPtr.Zero;
                return stream;
            }
        }
    }
}
