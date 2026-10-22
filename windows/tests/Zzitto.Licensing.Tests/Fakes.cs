// Doppi di test di Zzitto.Licensing. Spec §12. Aggiunto il 22/10/2026 alle 11:28.
using System.Security.Cryptography;

namespace Zzitto.Licensing.Tests;

internal sealed class FakeTimeProvider : TimeProvider
{
    public DateTimeOffset Now { get; set; } = new(2026, 10, 22, 10, 0, 0, TimeSpan.Zero);

    public override DateTimeOffset GetUtcNow() => Now;
}

internal sealed class FakeStore : IStoreLicenseSource
{
    public StoreLicenseSnapshot License { get; set; } = new(IsActive: true, IsTrial: true, TimeSpan.FromDays(30));

    /// <summary>Se valorizzata, la lettura della licenza lancia questa eccezione (Store non raggiungibile).</summary>
    public Exception? ReadError { get; set; }

    /// <summary>Se true, la lettura non risponde mai (rispetta solo l'annullamento).</summary>
    public bool Hang { get; set; }

    public StorePurchaseResultKind PurchaseResult { get; set; } = StorePurchaseResultKind.NotPurchased;

    /// <summary>Licenza che lo Store restituisce dopo un acquisto riuscito.</summary>
    public StoreLicenseSnapshot? LicenseAfterPurchase { get; set; } = new(true, false, TimeSpan.Zero);

    public int Reads { get; private set; }

    public int Purchases { get; private set; }

    public event EventHandler? LicenseChanged;

    public void RaiseLicenseChanged() => LicenseChanged?.Invoke(this, EventArgs.Empty);

    public async Task<StoreLicenseSnapshot> GetAppLicenseAsync(CancellationToken cancellationToken)
    {
        Reads++;
        if (Hang)
        {
            await Task.Delay(Timeout.Infinite, cancellationToken);
        }
        if (ReadError is not null)
        {
            throw ReadError;
        }
        return License;
    }

    public Task<StorePurchaseResultKind> RequestPurchaseAsync(nint windowHandle, CancellationToken cancellationToken)
    {
        Purchases++;
        if (PurchaseResult is StorePurchaseResultKind.Succeeded or StorePurchaseResultKind.AlreadyPurchased
            && LicenseAfterPurchase is not null)
        {
            License = LicenseAfterPurchase;
        }
        return Task.FromResult(PurchaseResult);
    }
}

internal sealed class InMemoryBlobStore : IBlobStore
{
    public Dictionary<string, byte[]> Blobs { get; } = new();

    public bool FailWrites { get; set; }

    public byte[]? Read(string name) => Blobs.TryGetValue(name, out byte[]? data) ? (byte[])data.Clone() : null;

    public void Write(string name, byte[] data)
    {
        if (FailWrites)
        {
            throw new IOException("Disco pieno");
        }
        Blobs[name] = (byte[])data.Clone();
    }
}

/// <summary>
/// Protezione finta ma verificabile: XOR con una chiave e HMAC-SHA256 di controllo, come DPAPI rileva
/// manomissioni e chiavi diverse (altro utente o altro PC).
/// </summary>
internal sealed class FakeProtector : ILocalProtector
{
    private readonly byte[] _key;

    public FakeProtector(byte key = 0x5A) => _key = Enumerable.Repeat(key, 32).ToArray();

    public byte[] Protect(byte[] plain)
    {
        byte[] body = plain.Select(b => (byte)(b ^ _key[0])).ToArray();
        byte[] mac = HMACSHA256.HashData(_key, body);
        return mac.Concat(body).ToArray();
    }

    public byte[] Unprotect(byte[] protectedData)
    {
        if (protectedData.Length < 32)
        {
            throw new CryptographicException("Dati troppo corti");
        }
        byte[] mac = protectedData[..32];
        byte[] body = protectedData[32..];
        if (!CryptographicOperations.FixedTimeEquals(mac, HMACSHA256.HashData(_key, body)))
        {
            throw new CryptographicException("Dati manomessi");
        }
        return body.Select(b => (byte)(b ^ _key[0])).ToArray();
    }
}
