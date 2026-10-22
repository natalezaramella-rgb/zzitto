// Spec §8.2. Aggiunto il 22/10/2026 alle 11:28.
namespace Zzitto.Licensing;

/// <summary>Astrazione sopra il Microsoft Store, sostituibile nei test (Spec §12).</summary>
public interface IStoreLicenseSource
{
    /// <summary>La licenza è cambiata (evento <c>StoreContext.OfflineLicensesChanged</c>).</summary>
    event EventHandler? LicenseChanged;

    /// <summary>Legge la licenza dell'app. Lancia un'eccezione se lo Store non risponde.</summary>
    Task<StoreLicenseSnapshot> GetAppLicenseAsync(CancellationToken cancellationToken);

    /// <summary>
    /// Mostra la finestra d'acquisto dello Store per l'app stessa. Va chiamato sul thread UI
    /// con l'handle della finestra principale (app desktop WinUI 3).
    /// </summary>
    Task<StorePurchaseResultKind> RequestPurchaseAsync(nint windowHandle, CancellationToken cancellationToken);
}
