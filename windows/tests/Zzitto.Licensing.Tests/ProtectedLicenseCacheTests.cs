// Spec §8.2, §10, §12: persistenza cifrata. Aggiunto il 22/10/2026 alle 11:28.
namespace Zzitto.Licensing.Tests;

public class ProtectedLicenseCacheTests
{
    private readonly InMemoryBlobStore _blobs = new();
    private readonly FakeProtector _protector = new();

    [Fact]
    public void SenzaDati_NonAcquistato() =>
        Assert.False(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());

    [Fact]
    public void Acquisto_PersisteTraIstanze()
    {
        Assert.True(new ProtectedLicenseCache(_blobs, _protector).TrySavePurchased());
        Assert.True(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());
    }

    [Fact]
    public void Acquisto_ManomessoNonVale()
    {
        new ProtectedLicenseCache(_blobs, _protector).TrySavePurchased();
        byte[] data = _blobs.Blobs[ProtectedLicenseCache.PurchasedBlob];
        data[^1] ^= 0x01;
        Assert.False(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());
    }

    [Fact]
    public void Acquisto_AltroUtenteNonVale()
    {
        new ProtectedLicenseCache(_blobs, new FakeProtector(0x11)).TrySavePurchased();
        Assert.False(new ProtectedLicenseCache(_blobs, _protector).IsPurchased());
    }

    [Fact]
    public void ErroreDiScrittura_NonLanciaEccezioni()
    {
        _blobs.FailWrites = true;
        Assert.False(new ProtectedLicenseCache(_blobs, _protector).TrySavePurchased());
    }

    [Fact]
    public void Prova_LetturaScrittura()
    {
        var cache = new TrialCache(
            new DateTimeOffset(2026, 11, 21, 10, 0, 0, TimeSpan.Zero),
            new DateTimeOffset(2026, 10, 22, 10, 0, 0, TimeSpan.Zero));
        new ProtectedLicenseCache(_blobs, _protector).WriteTrial(cache);
        Assert.Equal(cache, new ProtectedLicenseCache(_blobs, _protector).ReadTrial());
    }

    [Fact]
    public void Prova_DatiCorrotti()
    {
        _blobs.Blobs[ProtectedLicenseCache.TrialBlob] = _protector.Protect("zzitto-trial-v1|abc|1"u8.ToArray());
        Assert.Null(new ProtectedLicenseCache(_blobs, _protector).ReadTrial());
        _blobs.Blobs[ProtectedLicenseCache.TrialBlob] = [1, 2, 3];
        Assert.Null(new ProtectedLicenseCache(_blobs, _protector).ReadTrial());
    }

    [Fact]
    public void Dpapi_EFile_FunzionanoInsieme()
    {
        string dir = Path.Combine(Path.GetTempPath(), "zzitto-licensing-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            var store = new FileBlobStore(dir);
            Assert.True(new ProtectedLicenseCache(store, new DpapiProtector()).TrySavePurchased());
            Assert.True(new ProtectedLicenseCache(new FileBlobStore(dir), new DpapiProtector()).IsPurchased());
            // Il file su disco non contiene il testo in chiaro.
            string raw = System.Text.Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(dir, ProtectedLicenseCache.PurchasedBlob)));
            Assert.DoesNotContain("zzitto-purchased", raw);
        }
        finally
        {
            if (Directory.Exists(dir))
            {
                Directory.Delete(dir, recursive: true);
            }
        }
    }
}
