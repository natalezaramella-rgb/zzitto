// Spec §8.1, §8.2. Aggiunto il 22/10/2026 alle 11:28.
using System.Globalization;
using System.Text;

namespace Zzitto.Licensing;

/// <summary>
/// Ultima lettura valida della prova, usata solo quando lo Store non risponde.
/// </summary>
/// <param name="TrialEndsUtc">Fine della prova calcolata dall'ultima lettura dello Store.</param>
/// <param name="LastSeenUtc">Istante più avanzato mai osservato (difesa contro l'orologio spostato indietro).</param>
public sealed record TrialCache(DateTimeOffset TrialEndsUtc, DateTimeOffset LastSeenUtc);

/// <summary>Stato di licenza persistito in locale. Nessun metodo lancia eccezioni.</summary>
public interface ILicenseCache
{
    bool IsPurchased();

    /// <summary>Salva lo stato "acquistato". Restituisce true se salvato e riletto correttamente.</summary>
    bool TrySavePurchased();

    TrialCache? ReadTrial();

    void WriteTrial(TrialCache cache);
}

/// <summary>
/// Spec §8.2: dopo l'acquisto confermato lo stato si salva cifrato con DPAPI e lo Store non si legge più.
/// Si salvano solo un contenuto fisso e due istanti, nessun dato dell'utente (Spec §10).
/// </summary>
public sealed class ProtectedLicenseCache : ILicenseCache
{
    public const string PurchasedBlob = "purchase.bin";
    public const string TrialBlob = "trial.bin";
    private const string PurchasedPayload = "zzitto-purchased-v1|Zaramella.Zzitto";
    private const string TrialPrefix = "zzitto-trial-v1";

    private readonly IBlobStore _store;
    private readonly ILocalProtector _protector;

    public ProtectedLicenseCache(IBlobStore store, ILocalProtector protector)
    {
        _store = store;
        _protector = protector;
    }

    public bool IsPurchased() => ReadText(PurchasedBlob) == PurchasedPayload;

    public bool TrySavePurchased() => WriteText(PurchasedBlob, PurchasedPayload) && IsPurchased();

    public TrialCache? ReadTrial()
    {
        string? text = ReadText(TrialBlob);
        if (text is null)
        {
            return null;
        }
        string[] parts = text.Split('|');
        if (parts.Length != 3 || parts[0] != TrialPrefix
            || !long.TryParse(parts[1], NumberStyles.Integer, CultureInfo.InvariantCulture, out long endTicks)
            || !long.TryParse(parts[2], NumberStyles.Integer, CultureInfo.InvariantCulture, out long seenTicks)
            || !IsValidTicks(endTicks) || !IsValidTicks(seenTicks))
        {
            return null;
        }
        return new TrialCache(
            new DateTimeOffset(endTicks, TimeSpan.Zero),
            new DateTimeOffset(seenTicks, TimeSpan.Zero));
    }

    public void WriteTrial(TrialCache cache)
    {
        string text = string.Join('|',
            TrialPrefix,
            cache.TrialEndsUtc.UtcTicks.ToString(CultureInfo.InvariantCulture),
            cache.LastSeenUtc.UtcTicks.ToString(CultureInfo.InvariantCulture));
        WriteText(TrialBlob, text);
    }

    private static bool IsValidTicks(long ticks) =>
        ticks >= DateTimeOffset.MinValue.UtcTicks && ticks <= DateTimeOffset.MaxValue.UtcTicks;

    private string? ReadText(string name)
    {
        try
        {
            byte[]? data = _store.Read(name);
            return data is null ? null : Encoding.UTF8.GetString(_protector.Unprotect(data));
        }
        catch (Exception)
        {
            // File manomesso, di un altro utente o illeggibile: equivale ad assente.
            return null;
        }
    }

    private bool WriteText(string name, string text)
    {
        try
        {
            _store.Write(name, _protector.Protect(Encoding.UTF8.GetBytes(text)));
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }
}
