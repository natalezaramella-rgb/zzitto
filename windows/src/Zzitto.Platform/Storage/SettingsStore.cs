// Archivio delle impostazioni (JSON in chiaro, nessun dato personale). Spec §5.7. Aggiunto il 22/10/2026 alle 11:32.
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Zzitto.Platform.Storage;

public interface ISettingsProvider
{
    ZzittoSettings Current { get; }

    event EventHandler<ZzittoSettings>? Changed;

    void Update(Func<ZzittoSettings, ZzittoSettings> change);
}

public sealed class SettingsStore : ISettingsProvider
{
    internal static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.General)
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() },
    };

    private readonly string _path;
    private readonly object _lock = new();
    private ZzittoSettings _current;

    public SettingsStore(string folder)
    {
        ArgumentException.ThrowIfNullOrEmpty(folder);
        Directory.CreateDirectory(folder);
        _path = Path.Combine(folder, "settings.json");
        _current = Read(_path);
    }

    public event EventHandler<ZzittoSettings>? Changed;

    public ZzittoSettings Current
    {
        get
        {
            lock (_lock)
            {
                return _current;
            }
        }
    }

    public void Update(Func<ZzittoSettings, ZzittoSettings> change)
    {
        ArgumentNullException.ThrowIfNull(change);
        ZzittoSettings updated;
        lock (_lock)
        {
            updated = change(_current).Normalize();
            if (updated == _current)
            {
                return;
            }

            _current = updated;
            Write(_path, updated);
        }

        Changed?.Invoke(this, updated);
    }

    private static ZzittoSettings Read(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                string json = File.ReadAllText(path);
                ZzittoSettings? loaded = JsonSerializer.Deserialize<ZzittoSettings>(json, JsonOptions);
                if (loaded is not null)
                {
                    return loaded.Normalize();
                }
            }
        }
        catch (Exception ex) when (ex is JsonException or IOException or UnauthorizedAccessException or NotSupportedException)
        {
            // Impostazioni illeggibili: si riparte dai valori predefiniti.
        }

        return new ZzittoSettings();
    }

    private static void Write(string path, ZzittoSettings settings)
    {
        string temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(settings, JsonOptions));
        File.Move(temp, path, overwrite: true);
    }
}
