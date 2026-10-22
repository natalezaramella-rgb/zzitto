// Schermata "Licenze open source" (Spec §2.2). Il file THIRD_PARTY_LICENSES.md (agente E) è incluso nel
// pacchetto in Licenses\. Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.App.Services;

namespace Zzitto.App.Views;

public sealed partial class OpenSourcePage : Page
{
    public OpenSourcePage()
    {
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        string path = Path.Combine(AppContext.BaseDirectory, "Licenses", "THIRD_PARTY_LICENSES.md");
        try
        {
            LicensesText.Text = File.Exists(path)
                ? await File.ReadAllTextAsync(path)
                : Loc.Get("OpenSource_Missing");
        }
        catch (IOException)
        {
            LicensesText.Text = Loc.Get("OpenSource_Missing");
        }
    }

    private async void OnHelpClick(object sender, RoutedEventArgs e) =>
        await PageHelpers.ShowHelpAsync(XamlRoot, "HelpOpenSource");
}
