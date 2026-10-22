// Prova di 30 giorni e acquisto una tantum tramite ILicenseService (Spec §8.1, §8.2).
// Aggiunto il 22/10/2026 alle 11:32.
//
// L'acquisto si avvia dal thread dell'interfaccia con l'handle della finestra (richiesto da StoreContext
// nelle app desktop); gli aggiornamenti di stato arrivano già sul thread dell'interfaccia da AppHost.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.App.Services;
using Zzitto.Core.Licensing;

namespace Zzitto.App.Views;

public sealed partial class LicensePage : Page
{
    public LicensePage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        PageHelpers.Host.LicenseStateChanged += OnLicenseStateChanged;
        Show(PageHelpers.Host.License.Current);
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        PageHelpers.Host.LicenseStateChanged -= OnLicenseStateChanged;
        base.OnNavigatedFrom(e);
    }

    private void OnLicenseStateChanged(object? sender, LicenseState state) => Show(state);

    private void Show(LicenseState state)
    {
        switch (state.Status)
        {
            case LicenseStatus.Trial:
                StatusText.Text = Loc.Format("License_Trial", state.TrialDaysLeft);
                DetailText.Text = Loc.Get("License_TrialDetail");
                break;
            case LicenseStatus.TrialExpired:
                StatusText.Text = Loc.Get("License_Expired");
                DetailText.Text = Loc.Get("License_ExpiredDetail");
                break;
            case LicenseStatus.Purchased:
                StatusText.Text = Loc.Get("License_Purchased");
                DetailText.Text = Loc.Get("License_PurchasedDetail");
                break;
            default:
                // Store non raggiungibile e nessuna lettura precedente: la dettatura resta bloccata (Spec §8.1, agente D).
                StatusText.Text = Loc.Get("License_Unknown");
                DetailText.Text = Loc.Get("License_UnknownDetail");
                break;
        }

        BuyButton.Visibility = state.Status == LicenseStatus.Purchased ? Visibility.Collapsed : Visibility.Visible;
        RetryButton.Visibility = state.Status == LicenseStatus.Unknown ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void OnBuyClick(object sender, RoutedEventArgs e)
    {
        if (App.Instance?.Host is not { } host)
        {
            return;
        }

        IntPtr hwnd = host.SettingsWindowHandle;
        BuyButton.IsEnabled = false;
        Busy.IsActive = true;
        ResultText.Text = string.Empty;
        try
        {
            // Spec §8.2: chiamata dal thread dell'interfaccia, con la finestra proprietaria.
            PurchaseOutcome outcome = await host.License.PurchaseAsync(hwnd);
            ResultText.Text = outcome switch
            {
                PurchaseOutcome.Purchased => Loc.Get("Purchase_Done"),
                PurchaseOutcome.AlreadyOwned => Loc.Get("Purchase_AlreadyOwned"),
                PurchaseOutcome.Cancelled => Loc.Get("Purchase_Cancelled"),
                _ => Loc.Get("Purchase_Error"),
            };
            Show(host.License.Current);
        }
        finally
        {
            Busy.IsActive = false;
            BuyButton.IsEnabled = true;
        }
    }

    private async void OnRetryClick(object sender, RoutedEventArgs e)
    {
        RetryButton.IsEnabled = false;
        Busy.IsActive = true;
        try
        {
            await PageHelpers.Host.License.RefreshAsync();
            Show(PageHelpers.Host.License.Current);
        }
        finally
        {
            Busy.IsActive = false;
            RetryButton.IsEnabled = true;
        }
    }

    private async void OnHelpClick(object sender, RoutedEventArgs e) =>
        await PageHelpers.ShowHelpAsync(XamlRoot, "HelpLicense");
}
