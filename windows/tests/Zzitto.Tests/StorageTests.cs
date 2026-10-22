// Test dell'archiviazione: file cifrati DPAPI, cronologia, vocabolario, impostazioni (Spec §5.5–§5.7, §5.11, §10, §12).
// Aggiunto il 22/10/2026 alle 11:32.
using System.Text;
using Zzitto.Platform.Engine;
using Zzitto.Platform.Storage;

namespace Zzitto.Tests;

public sealed class StorageTests : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "zzitto-tests-" + Guid.NewGuid().ToString("N"));

    public StorageTests()
    {
        Directory.CreateDirectory(_folder);
    }

    public void Dispose()
    {
        Directory.Delete(_folder, recursive: true);
    }

    [Fact]
    public void Dpapi_RoundTrip_AndCiphertextDiffers()
    {
        var protector = new DpapiDataProtector();
        byte[] plain = Encoding.UTF8.GetBytes("testo dettato riservato");
        byte[] cipher = protector.Protect(plain);
        Assert.NotEqual(plain, cipher);
        Assert.Equal(plain, protector.Unprotect(cipher));
    }

    [Fact]
    public void EncryptedFile_DoesNotContainPlaintext()
    {
        // Spec §10: cronologia e dizionario cifrati su disco.
        var store = new EncryptedFileStore<VocabularyData>(Path.Combine(_folder, "v.dat"), new DpapiDataProtector());
        store.Save(new VocabularyData { Words = { "Zaramella", "Zzitto" } });

        byte[] raw = File.ReadAllBytes(store.FilePath);
        Assert.DoesNotContain("Zaramella", Encoding.UTF8.GetString(raw), StringComparison.Ordinal);
        Assert.Equal((byte)'Z', raw[0]);

        VocabularyData loaded = store.Load();
        Assert.Equal(new[] { "Zaramella", "Zzitto" }, loaded.Words);
        Assert.False(store.LastLoadFailed);
    }

    [Fact]
    public void EncryptedFile_TamperedOrForeign_LoadsEmpty()
    {
        string path = Path.Combine(_folder, "h.dat");
        var store = new EncryptedFileStore<HistoryData>(path, new DpapiDataProtector());
        store.Save(new HistoryData { Entries = { new HistoryEntry(DateTimeOffset.UtcNow, "a", "A.", false) } });

        byte[] raw = File.ReadAllBytes(path);
        raw[^5] ^= 0xFF;
        File.WriteAllBytes(path, raw);

        HistoryData loaded = store.Load();
        Assert.Empty(loaded.Entries);
        Assert.True(store.LastLoadFailed);

        File.WriteAllText(path, "non cifrato");
        Assert.Empty(store.Load().Entries);
        Assert.True(store.LastLoadFailed);
    }

    [Fact]
    public void EncryptedFile_MissingFile_IsEmpty()
    {
        var store = new EncryptedFileStore<VocabularyData>(Path.Combine(_folder, "nessuno.dat"), new FakeProtector());
        Assert.Empty(store.Load().Words);
        Assert.False(store.LastLoadFailed);
    }

    [Fact]
    public void History_RetentionAndMaximum()
    {
        var now = new DateTimeOffset(2026, 10, 22, 10, 0, 0, TimeSpan.Zero);
        var settings = new ZzittoSettings { HistoryRetentionDays = 7, HistoryMaxEntries = 10 };
        var entries = Enumerable.Range(0, 20)
            .Select(i => new HistoryEntry(now.AddHours(-i), "r" + i, "f" + i, false))
            .Append(new HistoryEntry(now.AddDays(-8), "vecchia", "vecchia", false))
            .ToList();

        List<HistoryEntry> kept = HistoryStore.ApplyRetention(entries, settings, now);

        Assert.Equal(10, kept.Count);
        Assert.DoesNotContain(kept, e => e.RawText == "vecchia");
        Assert.Equal("f0", kept[^1].FinalText); // ordine cronologico, la più recente in fondo
    }

    [Fact]
    public void History_DisabledStoresNothing_AndClearDeletesFile()
    {
        var settings = new FakeSettings();
        var history = new HistoryStore(_folder, new FakeProtector(), settings);
        history.Add(new HistoryEntry(DateTimeOffset.UtcNow, "r", "f", false));
        Assert.Single(history.GetAll());

        // Spec §5.6: disattivandola si cancella; cancellazione totale in un tocco.
        settings.Update(s => s with { HistoryEnabled = false });
        Assert.Empty(history.GetAll());
        history.Add(new HistoryEntry(DateTimeOffset.UtcNow, "r", "f", false));
        Assert.Empty(history.GetAll());
        Assert.False(File.Exists(Path.Combine(_folder, "history.dat")));
    }

    [Fact]
    public void Vocabulary_IsNormalized()
    {
        var data = new VocabularyData
        {
            Words = { " Zaramella ", "zaramella", "", "Zzitto" },
            Shortcuts = { new VoiceShortcut("firma", "Natale"), new VoiceShortcut(" Firma ", "Natale Zaramella"), new VoiceShortcut("vuota", "") },
        };

        VocabularyData normalized = VocabularyStore.Normalize(data);

        Assert.Equal(new[] { "Zaramella", "Zzitto" }, normalized.Words);
        VoiceShortcut shortcut = Assert.Single(normalized.Shortcuts);
        Assert.Equal("Natale Zaramella", shortcut.Expansion);
    }

    [Fact]
    public void Settings_PersistAndNormalize()
    {
        var store = new SettingsStore(_folder);
        int changes = 0;
        store.Changed += (_, _) => changes++;

        store.Update(s => s with { Mode = CorrectionMode.Formal, SilenceTimeoutMs = 50, Hotkey = new HotkeyBinding(HotkeyModifiers.Shift, 0x41) });

        var reloaded = new SettingsStore(_folder);
        Assert.Equal(CorrectionMode.Formal, reloaded.Current.Mode);
        Assert.Equal(500, reloaded.Current.SilenceTimeoutMs); // limite minimo
        Assert.Equal(HotkeyBinding.Default, reloaded.Current.Hotkey); // solo Maiusc non è valida
        Assert.Equal(1, changes);

        store.Update(s => s); // nessuna modifica, nessun evento
        Assert.Equal(1, changes);
    }

    [Fact]
    public void Settings_CorruptFile_UsesDefaults()
    {
        File.WriteAllText(Path.Combine(_folder, "settings.json"), "{ non è json");
        var store = new SettingsStore(_folder);
        Assert.Equal(new ZzittoSettings(), store.Current);
    }

    [Fact]
    public void Settings_DefaultsMatchSpecification()
    {
        var s = new ZzittoSettings();
        Assert.Equal(2000, s.SilenceTimeoutMs);      // Spec §5.2
        Assert.Equal(300, s.MaxDurationSeconds);     // Spec §5.2
        Assert.Equal(CorrectionMode.Clean, s.Mode);  // Spec §5.3
        Assert.Equal("it", s.Language);              // Spec §2.1
        DictationOptions options = s.ToDictationOptions();
        Assert.Equal(TranslateTarget.None, options.Translate);
    }

    [Fact]
    public void Hotkey_FormatAndValidity()
    {
        Assert.Equal("Ctrl+Alt+Spazio", HotkeyBinding.Default.ToString());
        Assert.True(HotkeyBinding.Default.IsValid);
        Assert.False(new HotkeyBinding(HotkeyModifiers.None, 0x41).IsValid);
        Assert.False(new HotkeyBinding(HotkeyModifiers.Control, 0x11).IsValid);
        Assert.Equal("Ctrl+Maiusc+F5", new HotkeyBinding(HotkeyModifiers.Control | HotkeyModifiers.Shift, 0x74).ToString());
    }
}
