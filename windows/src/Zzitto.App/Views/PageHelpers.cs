// Funzioni comuni alle pagine delle impostazioni: accesso all'app e guida contestuale "?" (Spec §5.8).
// Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Zzitto.App.Services;

namespace Zzitto.App.Views;

internal static class PageHelpers
{
    public static AppHost Host => App.Instance?.Host ?? throw new InvalidOperationException("Zzitto non è ancora avviato.");

    /// <summary>Guida contestuale della schermata (Spec §5.8: ogni schermata ha un'icona "?").</summary>
    public static async Task ShowHelpAsync(XamlRoot? root, string helpKey)
    {
        if (root is null)
        {
            return;
        }

        var dialog = new ContentDialog
        {
            XamlRoot = root,
            Title = Loc.Get(helpKey + "_Title"),
            Content = new ScrollViewer
            {
                MaxHeight = 420,
                Content = new TextBlock
                {
                    Text = Loc.Get(helpKey + "_Body"),
                    TextWrapping = TextWrapping.Wrap,
                    IsTextSelectionEnabled = true,
                },
            },
            CloseButtonText = Loc.Get("Dialog_Close"),
            DefaultButton = ContentDialogButton.Close,
        };
        await dialog.ShowAsync();
    }

    public static async Task ShowMessageAsync(XamlRoot? root, string title, string message)
    {
        if (root is null)
        {
            return;
        }

        var dialog = new ContentDialog
        {
            XamlRoot = root,
            Title = title,
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            CloseButtonText = Loc.Get("Dialog_Close"),
        };
        await dialog.ShowAsync();
    }

    /// <summary>Seleziona l'elemento di una ComboBox il cui Tag è uguale a <paramref name="tag"/>.</summary>
    public static void SelectByTag(ComboBox box, string tag)
    {
        foreach (object item in box.Items)
        {
            if (item is ComboBoxItem { Tag: string t } comboItem && t == tag)
            {
                box.SelectedItem = comboItem;
                return;
            }
        }
    }

    public static string? SelectedTag(ComboBox box) => (box.SelectedItem as ComboBoxItem)?.Tag as string;
}
