// Spec §8.2. Aggiunto il 22/10/2026 alle 11:28.
namespace Zzitto.Licensing;

/// <summary>
/// Vista minima di <c>Windows.Services.Store.StoreAppLicense</c>, indipendente dalle API WinRT (testabile).
/// </summary>
/// <param name="IsActive">La licenza dà diritto a usare l'app (prova in corso o acquisto).</param>
/// <param name="IsTrial">La licenza è di prova.</param>
/// <param name="TrialTimeRemaining">Tempo di prova rimanente, gestito dal Microsoft Store.</param>
public sealed record StoreLicenseSnapshot(bool IsActive, bool IsTrial, TimeSpan TrialTimeRemaining);

/// <summary>Esito di <c>StoreContext.RequestPurchaseAsync</c> (<c>StorePurchaseStatus</c>) più il caso di errore locale.</summary>
public enum StorePurchaseResultKind
{
    Succeeded,
    AlreadyPurchased,
    NotPurchased,
    NetworkError,
    ServerError,
    Failed,
}
