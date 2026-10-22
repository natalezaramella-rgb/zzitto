// File JSON cifrato (DPAPI) con scrittura atomica. Spec §5.6, §5.11, §10. Aggiunto il 22/10/2026 alle 11:32.
//
// Formato: "ZZE1" + blob DPAPI di JSON UTF-8. Un file illeggibile o alterato non blocca l'app:
// si riparte da un contenuto vuoto e il file danneggiato viene sostituito al salvataggio successivo.
using System.Security.Cryptography;
using System.Text.Json;

namespace Zzitto.Platform.Storage;

public sealed class EncryptedFileStore<T>
    where T : class, new()
{
    private static readonly byte[] Magic = "ZZE1"u8.ToArray();

    private readonly string _path;
    private readonly IDataProtector _protector;
    private readonly JsonSerializerOptions _jsonOptions;
    private readonly object _lock = new();

    public EncryptedFileStore(string path, IDataProtector protector, JsonSerializerOptions? jsonOptions = null)
    {
        ArgumentException.ThrowIfNullOrEmpty(path);
        _path = path;
        _protector = protector ?? throw new ArgumentNullException(nameof(protector));
        _jsonOptions = jsonOptions ?? new JsonSerializerOptions(JsonSerializerDefaults.General);
    }

    public string FilePath => _path;

    /// <summary>true se l'ultimo <see cref="Load"/> ha trovato un file illeggibile.</summary>
    public bool LastLoadFailed { get; private set; }

    public T Load()
    {
        lock (_lock)
        {
            LastLoadFailed = false;
            if (!File.Exists(_path))
            {
                return new T();
            }

            byte[]? plaintext = null;
            try
            {
                byte[] content = File.ReadAllBytes(_path);
                if (content.Length < Magic.Length || !content.AsSpan(0, Magic.Length).SequenceEqual(Magic))
                {
                    throw new CryptographicException("Intestazione del file non valida.");
                }

                plaintext = _protector.Unprotect(content.AsSpan(Magic.Length));
                return JsonSerializer.Deserialize<T>(plaintext, _jsonOptions) ?? new T();
            }
            catch (Exception ex) when (ex is CryptographicException or JsonException or IOException or UnauthorizedAccessException)
            {
                LastLoadFailed = true;
                return new T();
            }
            finally
            {
                if (plaintext is not null)
                {
                    CryptographicOperations.ZeroMemory(plaintext);
                }
            }
        }
    }

    public void Save(T value)
    {
        ArgumentNullException.ThrowIfNull(value);
        lock (_lock)
        {
            byte[] plaintext = JsonSerializer.SerializeToUtf8Bytes(value, _jsonOptions);
            try
            {
                byte[] protectedBytes = _protector.Protect(plaintext);
                string? directory = Path.GetDirectoryName(_path);
                if (!string.IsNullOrEmpty(directory))
                {
                    Directory.CreateDirectory(directory);
                }

                string temp = _path + ".tmp";
                using (var stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None))
                {
                    stream.Write(Magic);
                    stream.Write(protectedBytes);
                    stream.Flush(flushToDisk: true);
                }

                File.Move(temp, _path, overwrite: true);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(plaintext);
            }
        }
    }

    public void Delete()
    {
        lock (_lock)
        {
            if (File.Exists(_path))
            {
                File.Delete(_path);
            }
        }
    }
}
