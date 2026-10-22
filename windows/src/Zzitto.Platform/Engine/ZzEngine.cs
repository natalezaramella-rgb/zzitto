// Wrapper asincrono e sicuro del motore nativo. Spec §4.2 (AI mai sul thread UI, modelli caricati alla
// prima dettatura e scaricati per inattività), §4.3, §5.2, §5.10. Aggiunto il 22/10/2026 alle 11:32.
//
// Garanzie:
//  - una sola operazione nativa alla volta (il motore restituirebbe ZZ_ERR_BUSY): le chiamate si accodano;
//  - ogni chiamata bloccante gira su un thread dedicato, mai sul chiamante;
//  - CancellationToken -> zz_cancel (thread-safe per contratto);
//  - Dispose attende l'operazione in corso prima di zz_engine_destroy.
namespace Zzitto.Platform.Engine;

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
}

public sealed class ZzEngine : IDictationEngine
{
    private readonly IZzNative _native;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private IntPtr _handle;
    private int _disposed;

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
}
