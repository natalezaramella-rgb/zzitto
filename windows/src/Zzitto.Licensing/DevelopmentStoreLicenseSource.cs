// Spec §8.2, §12. Aggiunto il 22/10/2026 alle 11:28.
#if DEBUG
namespace Zzitto.Licensing;

/// <summary>
/// SOLO per le build Debug: un'app avviata da Visual Studio non è installata dal Microsoft Store e
/// <c>StoreContext.GetAppLicenseAsync</c> restituisce una licenza non attiva (dettatura bloccata).
/// Questa sorgente simula una prova di 30 giorni; non esiste nelle build Release.
/// Uso: <c>new StoreLicenseService(new DevelopmentStoreLicenseSource(), cache, TimeProvider.System)</c>.
/// </summary>
public sealed class DevelopmentStoreLicenseSource : IStoreLicenseSource
{
    private static readonly StoreLicenseSnapshot Trial =
        new(IsActive: true, IsTrial: true, TimeSpan.FromDays(LicenseEvaluator.TrialDays));

    // Mai sollevato: la licenza simulata non cambia.
    public event EventHandler? LicenseChanged { add { } remove { } }

    public Task<StoreLicenseSnapshot> GetAppLicenseAsync(CancellationToken cancellationToken) =>
        Task.FromResult(Trial);

    /// <summary>
    /// Simula un acquisto riuscito ma la licenza resta di prova: l'app si sblocca solo nella sessione
    /// e nulla viene salvato, così una prova in Debug non lascia uno sblocco sul PC dello sviluppatore.
    /// </summary>
    public Task<StorePurchaseResultKind> RequestPurchaseAsync(nint windowHandle, CancellationToken cancellationToken) =>
        Task.FromResult(StorePurchaseResultKind.Succeeded);
}
#endif
