// Cronologia locale delle ultime dettature: cifrata, disattivabile, cancellabile con un tocco,
// conservazione configurabile. Spec §5.6, §10. Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Storage;

public sealed record HistoryEntry(DateTimeOffset Timestamp, string RawText, string FinalText, bool IsRewrite);

public sealed class HistoryData
{
    public List<HistoryEntry> Entries { get; set; } = new();
}

public interface IHistoryStore
{
    event EventHandler? Changed;

    IReadOnlyList<HistoryEntry> GetAll();

    void Add(HistoryEntry entry);

    void Clear();
}

public sealed class HistoryStore : IHistoryStore
{
    private readonly EncryptedFileStore<HistoryData> _store;
    private readonly ISettingsProvider _settings;
    private readonly TimeProvider _time;
    private readonly object _lock = new();

    public HistoryStore(string folder, IDataProtector protector, ISettingsProvider settings, TimeProvider? time = null)
    {
        ArgumentException.ThrowIfNullOrEmpty(folder);
        _store = new EncryptedFileStore<HistoryData>(Path.Combine(folder, "history.dat"), protector);
        _settings = settings ?? throw new ArgumentNullException(nameof(settings));
        _time = time ?? TimeProvider.System;

        // Spec §5.6: con la cronologia disattivata non resta nulla su disco.
        _settings.Changed += (_, s) =>
        {
            if (!s.HistoryEnabled)
            {
                Clear();
            }
        };
    }

    public event EventHandler? Changed;

    public IReadOnlyList<HistoryEntry> GetAll()
    {
        lock (_lock)
        {
            HistoryData data = _store.Load();
            List<HistoryEntry> kept = ApplyRetention(data.Entries, _settings.Current, _time.GetUtcNow());
            if (kept.Count != data.Entries.Count)
            {
                _store.Save(new HistoryData { Entries = kept });
            }

            return kept.OrderByDescending(e => e.Timestamp).ToList();
        }
    }

    public void Add(HistoryEntry entry)
    {
        ArgumentNullException.ThrowIfNull(entry);
        ZzittoSettings settings = _settings.Current;
        if (!settings.HistoryEnabled)
        {
            return;
        }

        lock (_lock)
        {
            HistoryData data = _store.Load();
            data.Entries.Add(entry);
            data.Entries = ApplyRetention(data.Entries, settings, _time.GetUtcNow());
            _store.Save(data);
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Clear()
    {
        lock (_lock)
        {
            _store.Delete();
        }

        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Tiene le voci più recenti entro i giorni e il numero massimo configurati.</summary>
    internal static List<HistoryEntry> ApplyRetention(IEnumerable<HistoryEntry> entries, ZzittoSettings settings, DateTimeOffset now)
    {
        DateTimeOffset limit = now.AddDays(-settings.HistoryRetentionDays);
        return entries
            .Where(e => e.Timestamp >= limit)
            .OrderByDescending(e => e.Timestamp)
            .Take(settings.HistoryMaxEntries)
            .OrderBy(e => e.Timestamp)
            .ToList();
    }
}
