// Spec §8.1, §8.2, §12: flusso di prova, acquisto, Store non raggiungibile. Aggiunto il 22/10/2026 alle 11:28.
using Zzitto.Core.Licensing;

namespace Zzitto.Licensing.Tests;

public class StoreLicenseServiceTests
{
    private readonly FakeStore _store = new();
    private readonly InMemoryBlobStore _blobs = new();
    private readonly FakeProtector _protector = new();
    private readonly FakeTimeProvider _time = new();

    private StoreLicenseService NewService() =>
        new(_store, new ProtectedLicenseCache(_blobs, _protector), _time, TimeSpan.FromMilliseconds(200));

    [Fact]
    public void StatoIniziale_Sconosciuto()
    {
        using var service = NewService();
        Assert.Equal(LicenseStatus.Unknown, service.Current.Status);
        Assert.False(service.Current.CanDictate);
    }

    [Fact]
    public async Task Prova_LettaDalloStore()
    {
        _store.License = new StoreLicenseSnapshot(true, true, TimeSpan.FromDays(20));
        using var service = NewService();
        await service.RefreshAsync();
        Assert.Equal(new LicenseState(LicenseStatus.Trial, 20), service.Current);
        Assert.True(service.Current.CanDictate);
    }

    [Fact]
    public async Task ProvaScaduta_DettaturaBloccata()
    {
        _store.License = new StoreLicenseSnapshot(false, true, TimeSpan.Zero);
        using var service = NewService();
        await service.RefreshAsync();
        Assert.Equal(LicenseStatus.TrialExpired, service.Current.Status);
        Assert.False(service.Current.CanDictate);
    }

    [Fact]
    public async Task LicenzaDefinitiva_SalvataENonPiuLetta()
    {
        _store.License = new StoreLicenseSnapshot(true, false, TimeSpan.Zero);
        using (var first = NewService())
        {
            await first.RefreshAsync();
            Assert.Equal(LicenseStatus.Purchased, first.Current.Status);
        }
        Assert.Equal(1, _store.Reads);

        // Avvio successivo: lo Store non viene più interrogato (Spec §8.2).
        _store.ReadError = new InvalidOperationException("non deve essere chiamato");
        using var next = NewService();
        await next.RefreshAsync();
        Assert.Equal(LicenseStatus.Purchased, next.Current.Status);
        Assert.Equal(1, _store.Reads);
    }

    [Fact]
    public async Task StoreNonRaggiungibile_LaProvaContinuaDallUltimaLettura()
    {
        _store.License = new StoreLicenseSnapshot(true, true, TimeSpan.FromDays(20));
        using (var first = NewService())
        {
            await first.RefreshAsync();
        }

        _time.Now = _time.Now.AddDays(5);
        _store.ReadError = new System.Runtime.InteropServices.COMException("Store non raggiungibile");
        using var next = NewService();
        await next.RefreshAsync();
        Assert.Equal(new LicenseState(LicenseStatus.Trial, 15), next.Current);
    }

    [Fact]
    public async Task StoreBloccato_TimeoutEProvaDallUltimaLettura()
    {
        _store.License = new StoreLicenseSnapshot(true, true, TimeSpan.FromDays(3));
        using (var first = NewService())
        {
            await first.RefreshAsync();
        }

        _store.Hang = true;
        using var next = NewService();
        await next.RefreshAsync();
        Assert.Equal(new LicenseState(LicenseStatus.Trial, 3), next.Current);
    }

    [Fact]
    public async Task StoreNonRaggiungibile_SenzaLetturePrecedenti_StatoSconosciuto()
    {
        _store.ReadError = new System.Runtime.InteropServices.COMException("Store non raggiungibile");
        using var service = NewService();
        await service.RefreshAsync();
        Assert.Equal(LicenseStatus.Unknown, service.Current.Status);
    }

    [Fact]
    public async Task StoreNonRaggiungibile_OrologioIndietro_NonRestituisceGiorni()
    {
        _store.License = new StoreLicenseSnapshot(true, true, TimeSpan.FromDays(10));
        using (var first = NewService())
        {
            await first.RefreshAsync();
        }
        _store.ReadError = new System.Runtime.InteropServices.COMException("offline");

        _time.Now = _time.Now.AddDays(4);
        using (var second = NewService())
        {
            await second.RefreshAsync();
            Assert.Equal(new LicenseState(LicenseStatus.Trial, 6), second.Current);
        }

        _time.Now = _time.Now.AddDays(-30);
        using var third = NewService();
        await third.RefreshAsync();
        Assert.Equal(new LicenseState(LicenseStatus.Trial, 6), third.Current);
    }

    [Fact]
    public async Task ProvaScaduta_RestaScadutaSenzaStore()
    {
        _store.License = new StoreLicenseSnapshot(false, true, TimeSpan.Zero);
        using (var first = NewService())
        {
            await first.RefreshAsync();
        }
        _store.ReadError = new System.Runtime.InteropServices.COMException("offline");
        using var next = NewService();
        await next.RefreshAsync();
        Assert.Equal(LicenseStatus.TrialExpired, next.Current.Status);
    }

    [Fact]
    public async Task Acquisto_Riuscito_SalvatoCifrato()
    {
        using var service = NewService();
        await service.RefreshAsync();
        _store.PurchaseResult = StorePurchaseResultKind.Succeeded;

        Assert.Equal(PurchaseOutcome.Purchased, await service.PurchaseAsync(1));
        Assert.Equal(LicenseStatus.Purchased, service.Current.Status);
        Assert.True(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());
    }

    [Fact]
    public async Task Acquisto_RiuscitoMaLicenzaNonAggiornata_SbloccatoMaNonSalvato()
    {
        using var service = NewService();
        await service.RefreshAsync();
        _store.PurchaseResult = StorePurchaseResultKind.Succeeded;
        _store.LicenseAfterPurchase = null; // lo Store restituisce ancora la licenza di prova

        Assert.Equal(PurchaseOutcome.Purchased, await service.PurchaseAsync(1));
        Assert.Equal(LicenseStatus.Purchased, service.Current.Status);
        Assert.False(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());

        // Una rilettura nella stessa sessione non toglie lo sblocco.
        await service.RefreshAsync();
        Assert.Equal(LicenseStatus.Purchased, service.Current.Status);
    }

    [Fact]
    public async Task Acquisto_GiaFatto_Ripristinato()
    {
        using var service = NewService();
        await service.RefreshAsync();
        _store.PurchaseResult = StorePurchaseResultKind.AlreadyPurchased;

        Assert.Equal(PurchaseOutcome.AlreadyOwned, await service.PurchaseAsync(1));
        Assert.True(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());
    }

    [Fact]
    public async Task Acquisto_AnnullatoDallUtente()
    {
        using var service = NewService();
        await service.RefreshAsync();
        _store.PurchaseResult = StorePurchaseResultKind.NotPurchased;
        Assert.Equal(PurchaseOutcome.Cancelled, await service.PurchaseAsync(1));
        Assert.Equal(LicenseStatus.Trial, service.Current.Status);
    }

    [Theory]
    [InlineData(StorePurchaseResultKind.NetworkError)]
    [InlineData(StorePurchaseResultKind.ServerError)]
    [InlineData(StorePurchaseResultKind.Failed)]
    public async Task Acquisto_ErroreDiRete(StorePurchaseResultKind kind)
    {
        using var service = NewService();
        await service.RefreshAsync();
        _store.PurchaseResult = kind;
        Assert.Equal(PurchaseOutcome.Error, await service.PurchaseAsync(1));
        Assert.False(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());
    }

    [Fact]
    public async Task Acquisto_GiaSbloccato_NessunaFinestraDelloStore()
    {
        new ProtectedLicenseCache(_blobs, _protector).TrySavePurchased();
        using var service = NewService();
        await service.RefreshAsync();
        Assert.Equal(PurchaseOutcome.AlreadyOwned, await service.PurchaseAsync(1));
        Assert.Equal(0, _store.Purchases);
    }

    [Fact]
    public async Task EventoStateChanged()
    {
        using var service = NewService();
        var states = new List<LicenseState>();
        service.StateChanged += (_, s) => states.Add(s);
        await service.RefreshAsync();
        await service.RefreshAsync(); // stesso stato: nessun nuovo evento
        Assert.Equal(new[] { new LicenseState(LicenseStatus.Trial, 30) }, states);
    }

    [Fact]
    public async Task LicenzaCambiataNelloStore_Riletta()
    {
        using var service = NewService();
        await service.RefreshAsync();
        var changed = new TaskCompletionSource<LicenseState>(TaskCreationOptions.RunContinuationsAsynchronously);
        service.StateChanged += (_, s) => changed.TrySetResult(s);

        _store.License = new StoreLicenseSnapshot(true, false, TimeSpan.Zero);
        _store.RaiseLicenseChanged();

        LicenseState state = await changed.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(LicenseStatus.Purchased, state.Status);
    }

    [Fact]
    public async Task RefreshAnnullato_NonLanciaEccezioni()
    {
        using var service = NewService();
        using var cts = new CancellationTokenSource();
        cts.Cancel();
        await service.RefreshAsync(cts.Token);
        Assert.Equal(LicenseStatus.Unknown, service.Current.Status);
    }
}
