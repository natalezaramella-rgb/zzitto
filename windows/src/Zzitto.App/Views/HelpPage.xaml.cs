// Guida all'uso (Spec §5.8). Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.App.Services;

namespace Zzitto.App.Views;

public sealed partial class HelpPage : Page
{
    public HelpPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        // Il testo cita la scorciatoia attuale (configurabile, Spec §2.4).
        StartText.Text = Loc.Format("HelpStartText", PageHelpers.Host.Settings.Current.Hotkey.ToString());
    }
}
