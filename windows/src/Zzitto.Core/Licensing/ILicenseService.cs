// Contratto tra l'app Windows (agente C) e Zzitto.Licensing (agente D).
// Spec §8.1, §8.2. Aggiunto il 22/10/2026 alle 11:15.
//
// Regole: durante la prova la licenza si legge dallo Store a ogni avvio;
// dopo l'acquisto confermato non si fanno più accessi a internet.
namespace Zzitto.Core.Licensing;

public enum LicenseStatus
{
    Unknown,
    Trial,
    TrialExpired,
    Purchased,
}

public sealed record LicenseState(LicenseStatus Status, int TrialDaysLeft = 0)
{
    public bool CanDictate => Status is LicenseStatus.Trial or LicenseStatus.Purchased;
}

public enum PurchaseOutcome
{
    Purchased,
    AlreadyOwned,
    Cancelled,
    Error,
}

public interface ILicenseService
{
    LicenseState Current { get; }

    event EventHandler<LicenseState>? StateChanged;

    /// <summary>Da chiamare a ogni avvio. Non lancia eccezioni.</summary>
    Task RefreshAsync(CancellationToken cancellationToken = default);

    /// <summary>Avvia l'acquisto una tantum nello Store. Richiede l'handle della finestra principale.</summary>
    Task<PurchaseOutcome> PurchaseAsync(nint windowHandle, CancellationToken cancellationToken = default);
}
