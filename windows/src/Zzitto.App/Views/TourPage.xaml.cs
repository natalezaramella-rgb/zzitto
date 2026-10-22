// Tour guidato (Spec §5.8): spiega privacy, permesso del microfono, icona e scorciatoia, prima dettatura.
// Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.App.Services;

namespace Zzitto.App.Views;

public sealed partial class TourPage : Page
{
    private int _step;

    public TourPage()
    {
        InitializeComponent();
    }

    private StackPanel[] Steps => new[] { Step0, Step1, Step2, Step3, Step4 };

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        string hotkey = PageHelpers.Host.Settings.Current.Hotkey.ToString();
        IconText.Text = Loc.Format("TourIconText", hotkey);
        TryText.Text = Loc.Format("TourTryText", hotkey);
        ShowStep(0);
    }

    private void ShowStep(int step)
    {
        StackPanel[] steps = Steps;
        _step = Math.Clamp(step, 0, steps.Length - 1);
        for (int i = 0; i < steps.Length; i++)
        {
            steps[i].Visibility = i == _step ? Visibility.Visible : Visibility.Collapsed;
        }

        StepCounter.Text = Loc.Format("TourStepCounter", _step + 1, steps.Length);
        BackButton.IsEnabled = _step > 0;
        bool last = _step == steps.Length - 1;
        NextButton.Visibility = last ? Visibility.Collapsed : Visibility.Visible;
        FinishButton.Visibility = last ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnBackClick(object sender, RoutedEventArgs e) => ShowStep(_step - 1);

    private void OnNextClick(object sender, RoutedEventArgs e) => ShowStep(_step + 1);

    private void OnFinishClick(object sender, RoutedEventArgs e)
    {
        PageHelpers.Host.Settings.Update(s => s with { TourCompleted = true });
        PageHelpers.Host.OpenSettings(SettingsSection.General);
    }

    private async void OnMicClick(object sender, RoutedEventArgs e)
    {
        // Spec §5.8, §10: unico permesso richiesto, il microfono.
        bool granted = await MicrophonePermission.RequestAsync();
        MicResult.Text = Loc.Get(granted ? "TourMicGranted" : "TourMicDenied");
        PrivacyButton.Visibility = granted ? Visibility.Collapsed : Visibility.Visible;
    }

    private async void OnPrivacyClick(object sender, RoutedEventArgs e) =>
        await MicrophonePermission.OpenPrivacySettingsAsync();
}
