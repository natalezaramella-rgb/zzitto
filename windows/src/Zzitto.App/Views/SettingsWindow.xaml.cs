// Finestra delle impostazioni: navigazione tra le sezioni (Spec §2.5, §5.6–§5.8, §2.2, §8.2).
// Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;
using Zzitto.App.Services;

namespace Zzitto.App.Views;

public sealed partial class SettingsWindow : Window
{
    private static readonly Dictionary<SettingsSection, Type> Pages = new()
    {
        [SettingsSection.General] = typeof(GeneralPage),
        [SettingsSection.Dictionary] = typeof(DictionaryPage),
        [SettingsSection.Shortcuts] = typeof(ShortcutsPage),
        [SettingsSection.History] = typeof(HistoryPage),
        [SettingsSection.License] = typeof(LicensePage),
        [SettingsSection.OpenSource] = typeof(OpenSourcePage),
        [SettingsSection.Help] = typeof(HelpPage),
        [SettingsSection.Tour] = typeof(TourPage),
    };

    private bool _navigating;

    internal SettingsWindow(AppHost host)
    {
        Host = host;
        InitializeComponent();
        Title = Loc.Get("Settings_WindowTitle");
        AppWindow.Resize(new SizeInt32(1000, 760));
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "Zzitto.ico"));
        if (MicaController.IsSupported())
        {
            SystemBackdrop = new MicaBackdrop();
        }
    }

    internal AppHost Host { get; }

    /// <summary>Handle della finestra, richiesto per l'acquisto nello Store (Spec §8.2).</summary>
    internal IntPtr WindowHandle => WinRT.Interop.WindowNative.GetWindowHandle(this);

    internal void Navigate(SettingsSection section)
    {
        _navigating = true;
        try
        {
            string tag = section.ToString();
            NavigationViewItem? item = Nav.MenuItems.Concat(Nav.FooterMenuItems)
                .OfType<NavigationViewItem>()
                .FirstOrDefault(i => (i.Tag as string) == tag);
            if (item is not null)
            {
                Nav.SelectedItem = item;
            }

            ContentFrame.Navigate(Pages[section]);
        }
        finally
        {
            _navigating = false;
        }
    }

    private void OnSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (_navigating)
        {
            return;
        }

        if (args.SelectedItemContainer?.Tag is string tag && Enum.TryParse(tag, out SettingsSection section))
        {
            ContentFrame.Navigate(Pages[section]);
        }
    }
}
