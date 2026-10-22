// Spec §8.1, §8.2. Aggiunto il 22/10/2026 alle 11:28.
using Zzitto.Core.Licensing;

namespace Zzitto.Licensing;

/// <summary>Regole pure di calcolo dello stato di licenza (Spec §12: testabili senza Store).</summary>
public static class LicenseEvaluator
{
    /// <summary>Durata della prova configurata in Partner Center. Spec §8.2.</summary>
    public const int TrialDays = 30;

    /// <summary>
    /// Giorni rimanenti arrotondati per eccesso: con 29 giorni e 1 ora rimanenti si mostrano 30 giorni,
    /// con 1 minuto si mostra 1 giorno. Limitati a <see cref="TrialDays"/> (anche con prova illimitata).
    /// </summary>
    public static int DaysLeft(TimeSpan remaining)
    {
        if (remaining <= TimeSpan.Zero)
        {
            return 0;
        }
        double days = Math.Ceiling(remaining.TotalDays);
        return (int)Math.Min(days, TrialDays);
    }

    /// <summary>Stato a partire dalla licenza letta dallo Store. Spec §8.2.</summary>
    public static LicenseState FromStore(StoreLicenseSnapshot license)
    {
        if (!license.IsActive)
        {
            // Prova scaduta, licenza revocata o app non installata dallo Store.
            return new LicenseState(LicenseStatus.TrialExpired);
        }
        if (!license.IsTrial)
        {
            return new LicenseState(LicenseStatus.Purchased);
        }
        int left = DaysLeft(license.TrialTimeRemaining);
        return left > 0
            ? new LicenseState(LicenseStatus.Trial, left)
            : new LicenseState(LicenseStatus.TrialExpired);
    }

    /// <summary>
    /// Stato a partire dall'ultima lettura salvata, quando lo Store non risponde (Spec §8.1: la prova continua).
    /// L'orologio spostato indietro non restituisce giorni: si usa il massimo tra ora attuale e ultimo istante visto.
    /// </summary>
    public static LicenseState FromCache(TrialCache cache, DateTimeOffset nowUtc)
    {
        DateTimeOffset effectiveNow = nowUtc > cache.LastSeenUtc ? nowUtc : cache.LastSeenUtc;
        int left = DaysLeft(cache.TrialEndsUtc - effectiveNow);
        return left > 0
            ? new LicenseState(LicenseStatus.Trial, left)
            : new LicenseState(LicenseStatus.TrialExpired);
    }

    /// <summary>Fine della prova da salvare, limitata a 30 giorni per evitare overflow con prove illimitate.</summary>
    public static DateTimeOffset TrialEnd(DateTimeOffset nowUtc, TimeSpan remaining)
    {
        TimeSpan max = TimeSpan.FromDays(TrialDays);
        TimeSpan bounded = remaining < TimeSpan.Zero ? TimeSpan.Zero : remaining > max ? max : remaining;
        return nowUtc + bounded;
    }
}
