// Spec §8.1, §8.2. Aggiunto il 22/10/2026 alle 11:28.
using Zzitto.Core.Licensing;

namespace Zzitto.Licensing;

/// <summary>
/// Implementazione Windows di <see cref="ILicenseService"/>: prova di 30 giorni gestita dal Microsoft Store
/// e acquisto una tantum dell'app. Spec §8.2.
/// </summary>
/// <remarks>
/// Flusso di <see cref="RefreshAsync"/> (a ogni avvio):
/// 1. Stato "acquistato" cifrato con DPAPI presente → Purchased, lo Store non si legge più (Spec §8.2).
/// 2. Altrimenti si legge la licenza dallo Store (con timeout). Se è definitiva si salva lo stato cifrato;
///    se è di prova si salva la fine della prova per l'uso senza Store.
/// 3. Se lo Store non risponde si usa l'ultima lettura salvata: la prova continua (Spec §8.1).
///    Senza alcuna lettura salvata lo stato resta Unknown.
/// L'evento <see cref="StateChanged"/> può arrivare da un thread qualsiasi: chi aggiorna la UI
/// deve passare al thread UI (DispatcherQueue).
/// </remarks>
public sealed class StoreLicenseService : ILicenseService, IDisposable
{
    /// <summary>Attesa massima della risposta dello Store all'avvio.</summary>
    public static readonly TimeSpan DefaultStoreTimeout = TimeSpan.FromSeconds(15);

    private readonly IStoreLicenseSource _store;
    private readonly ILicenseCache _cache;
    private readonly TimeProvider _time;
    private readonly TimeSpan _storeTimeout;
    private readonly bool _ownsStore;
    private readonly SemaphoreSlim _lock = new(1, 1);
    private LicenseState _current = new(LicenseStatus.Unknown);

    public StoreLicenseService(
        IStoreLicenseSource store,
        ILicenseCache cache,
        TimeProvider timeProvider,
        TimeSpan? storeTimeout = null)
        : this(store, cache, timeProvider, storeTimeout, ownsStore: false)
    {
    }

    private StoreLicenseService(
        IStoreLicenseSource store,
        ILicenseCache cache,
        TimeProvider timeProvider,
        TimeSpan? storeTimeout,
        bool ownsStore)
    {
        _store = store;
        _cache = cache;
        _time = timeProvider;
        _storeTimeout = storeTimeout ?? DefaultStoreTimeout;
        _ownsStore = ownsStore;
        _store.LicenseChanged += OnStoreLicenseChanged;
    }

    /// <summary>Istanza di produzione: Microsoft Store, DPAPI, file in %LOCALAPPDATA%\Zzitto\Licensing.</summary>
    public static StoreLicenseService CreateDefault() => new(
        new WindowsStoreLicenseSource(),
        new ProtectedLicenseCache(FileBlobStore.CreateDefault(), new DpapiProtector()),
        TimeProvider.System,
        storeTimeout: null,
        ownsStore: true);

    public LicenseState Current => Volatile.Read(ref _current);

    public event EventHandler<LicenseState>? StateChanged;

    /// <summary>Da chiamare a ogni avvio. Non lancia eccezioni (nemmeno se annullato).</summary>
    public async Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        try
        {
            await _lock.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Annullato oppure servizio già chiuso (Dispose).
            return;
        }
        try
        {
            await RefreshCoreAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Spec §8.1: un errore imprevisto non deve bloccare l'app; lo stato resta quello precedente.
        }
        finally
        {
            _lock.Release();
        }
    }

    /// <summary>
    /// Avvia l'acquisto una tantum nello Store. Va chiamato dal thread UI con l'handle della finestra principale
    /// (<c>WinRT.Interop.WindowNative.GetWindowHandle(window)</c>).
    /// </summary>
    public async Task<PurchaseOutcome> PurchaseAsync(nint windowHandle, CancellationToken cancellationToken = default)
    {
        if (Current.Status == LicenseStatus.Purchased || _cache.IsPurchased())
        {
            SetState(new LicenseState(LicenseStatus.Purchased));
            return PurchaseOutcome.AlreadyOwned;
        }

        StorePurchaseResultKind result;
        try
        {
            // Prima chiamata asincrona, senza ConfigureAwait(false): resta sul thread UI (Spec §8.2).
            result = await _store.RequestPurchaseAsync(windowHandle, cancellationToken);
        }
        catch (OperationCanceledException)
        {
            return PurchaseOutcome.Cancelled;
        }
        catch (Exception)
        {
            return PurchaseOutcome.Error;
        }

        switch (result)
        {
            case StorePurchaseResultKind.Succeeded:
                await ConfirmPurchaseAsync(cancellationToken).ConfigureAwait(false);
                return PurchaseOutcome.Purchased;
            case StorePurchaseResultKind.AlreadyPurchased:
                await ConfirmPurchaseAsync(cancellationToken).ConfigureAwait(false);
                return PurchaseOutcome.AlreadyOwned;
            case StorePurchaseResultKind.NotPurchased:
                return PurchaseOutcome.Cancelled;
            default:
                return PurchaseOutcome.Error;
        }
    }

    public void Dispose()
    {
        _store.LicenseChanged -= OnStoreLicenseChanged;
        if (_ownsStore && _store is IDisposable disposable)
        {
            disposable.Dispose();
        }
        _lock.Dispose();
    }

    private async Task RefreshCoreAsync(CancellationToken cancellationToken)
    {
        // Spec §8.2: dopo l'acquisto la licenza non si legge più dallo Store.
        if (_cache.IsPurchased())
        {
            SetState(new LicenseState(LicenseStatus.Purchased));
            return;
        }

        StoreLicenseSnapshot? license = await TryReadStoreAsync(cancellationToken).ConfigureAwait(false);
        DateTimeOffset now = _time.GetUtcNow();

        if (license is not null)
        {
            LicenseState state = LicenseEvaluator.FromStore(license);
            if (state.Status != LicenseStatus.Purchased && Current.Status == LicenseStatus.Purchased)
            {
                // Acquisto confermato dallo Store in questa sessione ma licenza non ancora aggiornata:
                // resta sbloccato; al prossimo avvio si rilegge.
                return;
            }
            switch (state.Status)
            {
                case LicenseStatus.Purchased:
                    _cache.TrySavePurchased();
                    break;
                case LicenseStatus.Trial:
                    // L'ultimo istante visto non torna mai indietro, anche se l'orologio è stato spostato.
                    DateTimeOffset previousSeen = _cache.ReadTrial()?.LastSeenUtc ?? now;
                    DateTimeOffset seen = previousSeen > now ? previousSeen : now;
                    _cache.WriteTrial(new TrialCache(LicenseEvaluator.TrialEnd(now, license.TrialTimeRemaining), seen));
                    break;
                case LicenseStatus.TrialExpired:
                    // Salvata come scaduta, così senza Store non torna attiva.
                    _cache.WriteTrial(new TrialCache(now, now));
                    break;
            }
            SetState(state);
            return;
        }

        // Spec §8.1: Store non raggiungibile → la prova continua in base all'ultima lettura.
        TrialCache? cached = _cache.ReadTrial();
        if (cached is null || Current.Status == LicenseStatus.Purchased)
        {
            return;
        }
        if (now > cached.LastSeenUtc)
        {
            _cache.WriteTrial(cached with { LastSeenUtc = now });
        }
        SetState(LicenseEvaluator.FromCache(cached, now));
    }

    private async Task<StoreLicenseSnapshot?> TryReadStoreAsync(CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        try
        {
            Task<StoreLicenseSnapshot> read = _store.GetAppLicenseAsync(timeout.Token);
            return await read.WaitAsync(_storeTimeout, _time, cancellationToken).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Timeout, Store assente o non raggiungibile, app non installata dallo Store.
            timeout.Cancel();
            return null;
        }
    }

    private async Task ConfirmPurchaseAsync(CancellationToken cancellationToken)
    {
        // Lo Store ha confermato il pagamento: l'app si sblocca subito in questa sessione.
        SetState(new LicenseState(LicenseStatus.Purchased));
        try
        {
            await _lock.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        catch (Exception)
        {
            return;
        }
        try
        {
            // Lo stato si salva solo quando la licenza dello Store risulta definitiva; altrimenti
            // al prossimo avvio si rilegge dallo Store (Spec §8.2).
            StoreLicenseSnapshot? license = await TryReadStoreAsync(cancellationToken).ConfigureAwait(false);
            if (license is not null && LicenseEvaluator.FromStore(license).Status == LicenseStatus.Purchased)
            {
                _cache.TrySavePurchased();
            }
        }
        finally
        {
            _lock.Release();
        }
    }

    private void OnStoreLicenseChanged(object? sender, EventArgs e) => _ = RefreshAsync();

    private void SetState(LicenseState state)
    {
        LicenseState previous = Interlocked.Exchange(ref _current, state);
        if (previous != state)
        {
            StateChanged?.Invoke(this, state);
        }
    }
}
