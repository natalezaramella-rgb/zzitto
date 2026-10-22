// Dizionario personale (Spec §5.5) e scorciatoie vocali parola chiave → testo (Spec §5.11), cifrati (§10).
// Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Storage;

public sealed record VoiceShortcut(string Keyword, string Expansion);

public sealed class VocabularyData
{
    public List<string> Words { get; set; } = new();

    public List<VoiceShortcut> Shortcuts { get; set; } = new();
}

public interface IVocabularyStore
{
    event EventHandler? Changed;

    VocabularyData Load();

    void Save(VocabularyData data);
}

public sealed class VocabularyStore : IVocabularyStore
{
    private readonly EncryptedFileStore<VocabularyData> _store;

    public VocabularyStore(string folder, IDataProtector protector)
    {
        ArgumentException.ThrowIfNullOrEmpty(folder);
        _store = new EncryptedFileStore<VocabularyData>(Path.Combine(folder, "vocabulary.dat"), protector);
    }

    public event EventHandler? Changed;

    public VocabularyData Load() => _store.Load();

    public void Save(VocabularyData data)
    {
        ArgumentNullException.ThrowIfNull(data);
        _store.Save(Normalize(data));
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Toglie spazi, vuoti e doppioni (senza distinguere maiuscole) mantenendo l'ordine.</summary>
    public static VocabularyData Normalize(VocabularyData data)
    {
        ArgumentNullException.ThrowIfNull(data);
        var words = data.Words
            .Select(w => (w ?? string.Empty).Trim())
            .Where(w => w.Length > 0)
            .Distinct(StringComparer.CurrentCultureIgnoreCase)
            .ToList();
        var shortcuts = data.Shortcuts
            .Where(s => s is not null)
            .Select(s => new VoiceShortcut((s.Keyword ?? string.Empty).Trim(), s.Expansion ?? string.Empty))
            .Where(s => s.Keyword.Length > 0 && s.Expansion.Length > 0)
            .GroupBy(s => s.Keyword, StringComparer.CurrentCultureIgnoreCase)
            .Select(g => g.Last())
            .ToList();
        return new VocabularyData { Words = words, Shortcuts = shortcuts };
    }
}
