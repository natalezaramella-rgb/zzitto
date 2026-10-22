// Spec §8.2. Aggiunto il 22/10/2026 alle 11:28.
using Windows.Services.Store;

namespace Zzitto.Licensing;

/// <summary>
/// Implementazione con <c>Windows.Services.Store.StoreContext</c> per l'app desktop WinUI 3 pacchettizzata (MSIX).
/// </summary>
/// <remarks>
/// - Spec §8.2: la prova di 30 giorni è configurata e gestita dal Microsoft Store; qui si legge soltanto.
/// - <c>GetAppLicenseAsync</c> usa la licenza conservata localmente da Windows, quindi in genere funziona
///   anche senza rete; il collegamento a internet è a carico del servizio dello Store, non dell'app.
/// - Nelle app desktop lo <c>StoreContext</c> va associato alla finestra con
///   <c>WinRT.Interop.InitializeWithWindow.Initialize</c> prima di mostrare la finestra d'acquisto,
///   altrimenti <c>RequestPurchaseAsync</c> fallisce con 0x80070578 (ERROR_INVALID_WINDOW_HANDLE).
/// - <c>RequestPurchaseAsync</c> va chiamato sul thread UI e non funziona in un processo con privilegi elevati.
/// </remarks>
public sealed class WindowsStoreLicenseSource : IStoreLicenseSource, IDisposable
{
    private readonly object _sync = new();
    private StoreContext? _context;
    private nint _initializedWindow;

    public event EventHandler? LicenseChanged;

    private StoreContext Context
    {
        get
        {
            lock (_sync)
            {
                if (_context is null)
                {
                    _context = StoreContext.GetDefault();
                    _context.OfflineLicensesChanged += OnOfflineLicensesChanged;
                }
                return _context;
            }
        }
    }

    public async Task<StoreLicenseSnapshot> GetAppLicenseAsync(CancellationToken cancellationToken)
    {
        StoreAppLicense license = await Context.GetAppLicenseAsync().AsTask(cancellationToken).ConfigureAwait(false);
        return new StoreLicenseSnapshot(license.IsActive, license.IsTrial, license.TrialTimeRemaining);
    }

    public async Task<StorePurchaseResultKind> RequestPurchaseAsync(nint windowHandle, CancellationToken cancellationToken)
    {
        StoreContext context = Context;
        AssociateWindow(context, windowHandle);

        // Nessun ConfigureAwait(false): RequestPurchaseAsync deve restare sul thread UI.
        StoreProductResult productResult = await context.GetStoreProductForCurrentAppAsync().AsTask(cancellationToken);
        if (productResult.ExtendedError is not null || productResult.Product is null)
        {
            return StorePurchaseResultKind.NetworkError;
        }

        StorePurchaseResult purchase = await context.RequestPurchaseAsync(productResult.Product.StoreId).AsTask(cancellationToken);
        return purchase.Status switch
        {
            StorePurchaseStatus.Succeeded => StorePurchaseResultKind.Succeeded,
            StorePurchaseStatus.AlreadyPurchased => StorePurchaseResultKind.AlreadyPurchased,
            StorePurchaseStatus.NotPurchased => StorePurchaseResultKind.NotPurchased,
            StorePurchaseStatus.NetworkError => StorePurchaseResultKind.NetworkError,
            StorePurchaseStatus.ServerError => StorePurchaseResultKind.ServerError,
            _ => StorePurchaseResultKind.Failed,
        };
    }

    public void Dispose()
    {
        lock (_sync)
        {
            if (_context is not null)
            {
                _context.OfflineLicensesChanged -= OnOfflineLicensesChanged;
                _context = null;
            }
        }
    }

    private void AssociateWindow(StoreContext context, nint windowHandle)
    {
        lock (_sync)
        {
            // IInitializeWithWindow si inizializza una sola volta per oggetto: la finestra principale
            // dell'app non cambia durante la vita del processo.
            if (_initializedWindow != 0 || windowHandle == 0)
            {
                return;
            }
            WinRT.Interop.InitializeWithWindow.Initialize(context, windowHandle);
            _initializedWindow = windowHandle;
        }
    }

    private void OnOfflineLicensesChanged(StoreContext sender, object args) =>
        LicenseChanged?.Invoke(this, EventArgs.Empty);
}
