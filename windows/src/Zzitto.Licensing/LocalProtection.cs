// Spec §8.2, §10. Aggiunto il 22/10/2026 alle 11:28.
using System.Security.Cryptography;
using System.Text;

namespace Zzitto.Licensing;

/// <summary>Cifratura locale dello stato di licenza (Spec §8.2: "stato cifrato con DPAPI").</summary>
public interface ILocalProtector
{
    byte[] Protect(byte[] plain);

    /// <summary>Lancia <see cref="CryptographicException"/> se i dati sono manomessi o di un altro utente.</summary>
    byte[] Unprotect(byte[] protectedData);
}

/// <summary>
/// DPAPI con ambito utente corrente: il file è leggibile solo dallo stesso utente Windows sullo stesso PC;
/// copiato altrove non si decifra. L'entropia aggiuntiva lega i dati a Zzitto.
/// </summary>
public sealed class DpapiProtector : ILocalProtector
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Zaramella.Zzitto.Licensing.v1");

    public byte[] Protect(byte[] plain) =>
        ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);

    public byte[] Unprotect(byte[] protectedData) =>
        ProtectedData.Unprotect(protectedData, Entropy, DataProtectionScope.CurrentUser);
}

/// <summary>Archivio di piccoli blob binari per nome.</summary>
public interface IBlobStore
{
    /// <summary>Contenuto del blob, oppure null se non esiste.</summary>
    byte[]? Read(string name);

    void Write(string name, byte[] data);
}

/// <summary>
/// Blob su file in <c>%LOCALAPPDATA%\Zzitto\Licensing</c> (in un pacchetto MSIX Windows lo reindirizza
/// nella cartella dati del pacchetto, rimossa alla disinstallazione). Scrittura atomica tramite file temporaneo.
/// </summary>
public sealed class FileBlobStore : IBlobStore
{
    private readonly string _directory;

    public FileBlobStore(string directory) => _directory = directory;

    public static FileBlobStore CreateDefault() => new(Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Zzitto",
        "Licensing"));

    public byte[]? Read(string name)
    {
        string path = Path.Combine(_directory, name);
        return File.Exists(path) ? File.ReadAllBytes(path) : null;
    }

    public void Write(string name, byte[] data)
    {
        Directory.CreateDirectory(_directory);
        string path = Path.Combine(_directory, name);
        string temp = path + ".tmp";
        File.WriteAllBytes(temp, data);
        File.Move(temp, path, overwrite: true);
    }
}
