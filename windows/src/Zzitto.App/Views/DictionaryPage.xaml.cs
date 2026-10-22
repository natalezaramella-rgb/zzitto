// Dizionario personale: parole e nomi propri passati a Whisper e al modello di correzione (Spec §5.5).
// Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.Platform.Storage;

namespace Zzitto.App.Views;

public sealed partial class DictionaryPage : Page
{
    public DictionaryPage()
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
        WordsList.ItemsSource = PageHelpers.Host.Vocabulary.Load().Words;
    }

    private void OnAddClick(object sender, RoutedEventArgs e) => AddWord();

    private void OnWordKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == Windows.System.VirtualKey.Enter)
        {
            e.Handled = true;
            AddWord();
        }
    }

    private void AddWord()
    {
        string word = WordBox.Text.Trim();
        if (word.Length == 0)
        {
            return;
        }

        VocabularyData data = PageHelpers.Host.Vocabulary.Load();
        data.Words.Add(word);
        PageHelpers.Host.Vocabulary.Save(data);
        WordBox.Text = string.Empty;
        Refresh();
    }

    private void OnRemoveClick(object sender, RoutedEventArgs e)
    {
        var selected = WordsList.SelectedItems.OfType<string>().ToHashSet(StringComparer.Ordinal);
        if (selected.Count == 0)
        {
            return;
        }

        VocabularyData data = PageHelpers.Host.Vocabulary.Load();
        data.Words.RemoveAll(selected.Contains);
        PageHelpers.Host.Vocabulary.Save(data);
        Refresh();
    }

    private async void OnHelpClick(object sender, RoutedEventArgs e) =>
        await PageHelpers.ShowHelpAsync(XamlRoot, "HelpDictionary");
}
