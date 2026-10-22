// Scorciatoie vocali: pronunciando solo la parola chiave si inserisce il testo associato (Spec §5.11).
// Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.Platform.Storage;

namespace Zzitto.App.Views;

public sealed partial class ShortcutsPage : Page
{
    // Testo mostrato → parola chiave (le parole chiave sono uniche, quindi anche i testi).
    private readonly Dictionary<string, string> _rows = new(StringComparer.Ordinal);

    public ShortcutsPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        Refresh();
    }

    private void Refresh()
    {
        _rows.Clear();
        foreach (VoiceShortcut shortcut in PageHelpers.Host.Vocabulary.Load().Shortcuts)
        {
            _rows[Display(shortcut)] = shortcut.Keyword;
        }

        // Elenco di stringhe: nessun binding né riflessione XAML.
        ShortcutsList.ItemsSource = _rows.Keys.ToList();
    }

    private void OnAddClick(object sender, RoutedEventArgs e)
    {
        string keyword = KeywordBox.Text.Trim();
        string expansion = ExpansionBox.Text.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n');
        if (keyword.Length == 0 || expansion.Trim().Length == 0)
        {
            return;
        }

        VocabularyData data = PageHelpers.Host.Vocabulary.Load();
        data.Shortcuts.Add(new VoiceShortcut(keyword, expansion));
        PageHelpers.Host.Vocabulary.Save(data); // una parola chiave già presente viene sostituita
        KeywordBox.Text = string.Empty;
        ExpansionBox.Text = string.Empty;
        Refresh();
    }

    private void OnRemoveClick(object sender, RoutedEventArgs e)
    {
        var keywords = ShortcutsList.SelectedItems.OfType<string>()
            .Where(_rows.ContainsKey)
            .Select(row => _rows[row])
            .ToHashSet(StringComparer.CurrentCultureIgnoreCase);
        if (keywords.Count == 0)
        {
            return;
        }

        VocabularyData data = PageHelpers.Host.Vocabulary.Load();
        data.Shortcuts.RemoveAll(s => keywords.Contains(s.Keyword));
        PageHelpers.Host.Vocabulary.Save(data);
        Refresh();
    }

    private async void OnHelpClick(object sender, RoutedEventArgs e) =>
        await PageHelpers.ShowHelpAsync(XamlRoot, "HelpShortcuts");

    private static string Display(VoiceShortcut shortcut)
    {
        string preview = shortcut.Expansion.Replace('\n', ' ');
        if (preview.Length > 80)
        {
            preview = preview[..80] + "…";
        }

        return "«" + shortcut.Keyword + "» → " + preview;
    }
}
