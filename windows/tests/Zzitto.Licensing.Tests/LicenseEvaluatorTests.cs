// Spec §8.2, §12: giorni rimanenti, scadenza, orologio indietro. Aggiunto il 22/10/2026 alle 11:28.
using Zzitto.Core.Licensing;

namespace Zzitto.Licensing.Tests;

public class LicenseEvaluatorTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 22, 10, 0, 0, TimeSpan.Zero);

    [Theory]
    [InlineData(30 * 24 * 60, 30)]
    [InlineData(29 * 24 * 60 + 1, 30)]
    [InlineData(29 * 24 * 60, 29)]
    [InlineData(24 * 60, 1)]
    [InlineData(1, 1)]
    [InlineData(0, 0)]
    [InlineData(-60, 0)]
    public void GiorniRimanenti(int remainingMinutes, int expected) =>
        Assert.Equal(expected, LicenseEvaluator.DaysLeft(TimeSpan.FromMinutes(remainingMinutes)));

    [Fact]
    public void GiorniRimanenti_ProvaIllimitata_NonVaInOverflow() =>
        Assert.Equal(30, LicenseEvaluator.DaysLeft(TimeSpan.MaxValue));

    [Fact]
    public void LicenzaDefinitiva_Acquistata() =>
        Assert.Equal(new LicenseState(LicenseStatus.Purchased),
            LicenseEvaluator.FromStore(new StoreLicenseSnapshot(true, false, TimeSpan.Zero)));

    [Fact]
    public void ProvaAttiva() =>
        Assert.Equal(new LicenseState(LicenseStatus.Trial, 12),
            LicenseEvaluator.FromStore(new StoreLicenseSnapshot(true, true, TimeSpan.FromDays(11.5))));

    [Fact]
    public void ProvaSenzaTempoRimanente_Scaduta() =>
        Assert.Equal(LicenseStatus.TrialExpired,
            LicenseEvaluator.FromStore(new StoreLicenseSnapshot(true, true, TimeSpan.Zero)).Status);

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public void LicenzaNonAttiva_Scaduta(bool isTrial) =>
        Assert.Equal(LicenseStatus.TrialExpired,
            LicenseEvaluator.FromStore(new StoreLicenseSnapshot(false, isTrial, TimeSpan.FromDays(5))).Status);

    [Fact]
    public void Cache_ProvaInCorso() =>
        Assert.Equal(new LicenseState(LicenseStatus.Trial, 10),
            LicenseEvaluator.FromCache(new TrialCache(Now.AddDays(10), Now), Now));

    [Fact]
    public void Cache_ProvaScaduta() =>
        Assert.Equal(LicenseStatus.TrialExpired,
            LicenseEvaluator.FromCache(new TrialCache(Now.AddDays(-1), Now.AddDays(-2)), Now).Status);

    [Fact]
    public void Cache_OrologioIndietro_NonRestituisceGiorni()
    {
        var cache = new TrialCache(TrialEndsUtc: Now.AddDays(10), LastSeenUtc: Now.AddDays(5));
        // L'utente porta l'orologio indietro di 20 giorni: contano i 5 giorni già visti.
        Assert.Equal(new LicenseState(LicenseStatus.Trial, 5), LicenseEvaluator.FromCache(cache, Now.AddDays(-20)));
    }

    [Fact]
    public void FineProva_Limitata() =>
        Assert.Equal(Now.AddDays(30), LicenseEvaluator.TrialEnd(Now, TimeSpan.MaxValue));
}
