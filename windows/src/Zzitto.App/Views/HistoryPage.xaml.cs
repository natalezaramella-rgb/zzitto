// Cronologia delle ultime dettature, testo grezzo e corretto (Spec §5.6). Aggiunto il 22/10/2026 alle 11:32.
using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;
using Zzitto.Platform.Storage;

namespace Zzitto.App.Views;

public sealed partial class HistoryPage : Page
{
    private readonly List<HistoryEntry> _entries = new();
    private bool _loading;

    public HistoryPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _loading = true;
        try
        {
            ZzittoSettings s = PageHelpers.Host.Settings.Current;
            HistorySwitch.IsOn = s.HistoryEnabled;
            RetentionBox.Value = s.HistoryRetentionDays;
        }
        finally
        {
            _loading = false;
        }

        Refresh();
    }

    private void Refresh()
    {
        _entries.Clear();
        _entries.AddRange(PageHelpers.Host.History.GetAll());
        EntriesList.ItemsSource = _entries
            .Select(entry => entry.Timestamp.ToLocalTime().ToString("g", CultureInfo.CurrentCulture) + "  —  " + OneLine(entry.FinalText))
            .ToList();
        RawText.Text = string.Empty;
    }

    private void OnSettingsChanged(object sender, RoutedEventArgs e) => Save();

    private void OnRetentionChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) => Save();

    private void Save()
    {
        if (_loading)
        {
            return;
        }

        PageHelpers.Host.Settings.Update(s => s with
        {
            HistoryEnabled = HistorySwitch.IsOn,
            HistoryRetentionDays = double.IsNaN(RetentionBox.Value) ? s.HistoryRetentionDays : (int)Math.Round(RetentionBox.Value),
        });
        Refresh();
    }

    private void OnClearClick(object sender, RoutedEventArgs e)
    {
        // Spec §5.6: cancellazione totale con un tocco.
        PageHelpers.Host.History.Clear();
        Refresh();
    }

    private void OnEntrySelected(object sender, SelectionChangedEventArgs e)
    {
        int index = EntriesList.SelectedIndex;
        RawText.Text = index >= 0 && index < _entries.Count ? _entries[index].RawText : string.Empty;
    }

    private void OnCopyClick(object sender, RoutedEventArgs e)
    {
        int index = EntriesList.SelectedIndex;
        if (index < 0 || index >= _entries.Count)
        {
            return;
        }

        var package = new DataPackage();
        package.SetText(_entries[index].FinalText);
        Clipboard.SetContent(package);
    }

    private async void OnHelpClick(object sender, RoutedEventArgs e) =>
        await PageHelpers.ShowHelpAsync(XamlRoot, "HelpHistory");

    private static string OneLine(string text)
    {
        string line = text.Replace('\n', ' ').Replace('\r', ' ');
        return line.Length > 120 ? line[..120] + "…" : line;
    }
}
