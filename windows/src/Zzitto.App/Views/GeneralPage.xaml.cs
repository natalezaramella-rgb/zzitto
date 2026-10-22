// Impostazioni generali (Spec §5.7, §2.4, §2.6, §4.2). Aggiunto il 22/10/2026 alle 11:32.
using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.App.Services;
using Zzitto.Platform.Engine;
using Zzitto.Platform.Storage;

namespace Zzitto.App.Views;

public sealed partial class GeneralPage : Page
{
    private bool _loading;
    private bool _capturingHotkey;

    public GeneralPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        PageHelpers.Host.HotkeyStatusChanged += OnHotkeyStatusChanged;
        Load();
        _ = LoadStartupStateAsync();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        PageHelpers.Host.HotkeyStatusChanged -= OnHotkeyStatusChanged;
        base.OnNavigatedFrom(e);
    }

    private void Load()
    {
        _loading = true;
        try
        {
            ZzittoSettings s = PageHelpers.Host.Settings.Current;
            PageHelpers.SelectByTag(LanguageBox, s.Language);
            PageHelpers.SelectByTag(ModeBox, s.Mode.ToString());
            PageHelpers.SelectByTag(TranslateBox, s.Translate.ToString());
            PageHelpers.SelectByTag(OverlayModeBox, s.OverlayMode.ToString());
            SilenceBox.Value = s.SilenceTimeoutMs / 1000.0;
            MaxDurationBox.Value = s.MaxDurationSeconds / 60.0;
            UnloadBox.Value = s.ModelUnloadMinutes;
            VoiceCommandsSwitch.IsOn = s.VoiceCommandsEnabled;
            StartSoundSwitch.IsOn = s.StartSound;
            ClipboardSelectionSwitch.IsOn = s.ClipboardSelectionFallback;
            GpuSwitch.IsOn = s.UseGpu;
            HotkeyButton.Content = s.Hotkey.ToString();
            UpdateHotkeyStatus();
            EngineStatus.Text = PageHelpers.Host.EngineError ?? string.Empty;
        }
        finally
        {
            _loading = false;
        }
    }

    private void Save()
    {
        if (_loading)
        {
            return;
        }

        PageHelpers.Host.Settings.Update(s => s with
        {
            Language = PageHelpers.SelectedTag(LanguageBox) ?? s.Language,
            Mode = Enum.TryParse(PageHelpers.SelectedTag(ModeBox), out CorrectionMode mode) ? mode : s.Mode,
            Translate = Enum.TryParse(PageHelpers.SelectedTag(TranslateBox), out TranslateTarget translate) ? translate : s.Translate,
            OverlayMode = Enum.TryParse(PageHelpers.SelectedTag(OverlayModeBox), out OverlayMode overlay) ? overlay : s.OverlayMode,
            SilenceTimeoutMs = double.IsNaN(SilenceBox.Value) ? s.SilenceTimeoutMs : (int)Math.Round(SilenceBox.Value * 1000),
            MaxDurationSeconds = double.IsNaN(MaxDurationBox.Value) ? s.MaxDurationSeconds : (int)Math.Round(MaxDurationBox.Value * 60),
            ModelUnloadMinutes = double.IsNaN(UnloadBox.Value) ? s.ModelUnloadMinutes : (int)Math.Round(UnloadBox.Value),
            VoiceCommandsEnabled = VoiceCommandsSwitch.IsOn,
            StartSound = StartSoundSwitch.IsOn,
            ClipboardSelectionFallback = ClipboardSelectionSwitch.IsOn,
            UseGpu = GpuSwitch.IsOn,
        });
    }

    private void OnSettingChanged(object sender, SelectionChangedEventArgs e) => Save();

    private void OnNumberChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) => Save();

    private void OnToggled(object sender, RoutedEventArgs e) => Save();

    private async void OnHelpClick(object sender, RoutedEventArgs e) =>
        await PageHelpers.ShowHelpAsync(XamlRoot, "HelpGeneral");

    private void OnRepeatTourClick(object sender, RoutedEventArgs e) =>
        PageHelpers.Host.OpenSettings(SettingsSection.Tour);

    // ---- Avvio automatico (Spec §2.4) ----

    private async Task LoadStartupStateAsync()
    {
        Windows.ApplicationModel.StartupTaskState? state = await StartupTaskService.GetStateAsync();
        _loading = true;
        try
        {
            StartupSwitch.IsOn = StartupTaskService.IsEnabled(state);
            StartupSwitch.IsEnabled = StartupTaskService.CanChange(state) || state == Windows.ApplicationModel.StartupTaskState.DisabledByUser;
        }
        finally
        {
            _loading = false;
        }
    }

    private async void OnStartupToggled(object sender, RoutedEventArgs e)
    {
        if (_loading)
        {
            return;
        }

        Windows.ApplicationModel.StartupTaskState? state = await StartupTaskService.SetEnabledAsync(StartupSwitch.IsOn);
        if (StartupSwitch.IsOn && state == Windows.ApplicationModel.StartupTaskState.DisabledByUser)
        {
            // Disattivato dall'utente in Gestione attività: solo lì si può riattivare.
            await PageHelpers.ShowMessageAsync(XamlRoot, "Zzitto", Loc.Get("Startup_DisabledByUser"));
        }

        await LoadStartupStateAsync();
    }

    // ---- Scorciatoia globale (Spec §2.4) ----

    private void OnHotkeyClick(object sender, RoutedEventArgs e)
    {
        _capturingHotkey = true;
        HotkeyButton.Content = Loc.Get("Hotkey_PressKeys");
    }

    private void OnHotkeyLostFocus(object sender, RoutedEventArgs e)
    {
        if (_capturingHotkey)
        {
            _capturingHotkey = false;
            HotkeyButton.Content = PageHelpers.Host.Settings.Current.Hotkey.ToString();
        }
    }

    private async void OnHotkeyKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (!_capturingHotkey)
        {
            return;
        }

        e.Handled = true;
        uint vk = (uint)e.Key;
        if (vk == 0x1B)
        {
            OnHotkeyLostFocus(sender, e);
            return;
        }

        if (HotkeyBinding.IsModifierKey(vk))
        {
            return; // si attende il tasto principale
        }

        var binding = new HotkeyBinding(CurrentModifiers(), vk);
        if (!binding.IsValid)
        {
            HotkeyStatus.Text = Loc.Get("Hotkey_Invalid");
            HotkeyStatus.Visibility = Visibility.Visible;
            return;
        }

        _capturingHotkey = false;
        HotkeyButton.Content = binding.ToString();
        bool ok = await PageHelpers.Host.ApplyHotkeyAsync(binding);
        if (ok)
        {
            PageHelpers.Host.Settings.Update(s => s with { Hotkey = binding });
        }
        else
        {
            // Combinazione già usata da un'altra app: si ripristina la precedente.
            await PageHelpers.Host.ApplyHotkeyAsync(PageHelpers.Host.Settings.Current.Hotkey);
            HotkeyButton.Content = PageHelpers.Host.Settings.Current.Hotkey.ToString();
        }

        UpdateHotkeyStatus(ok ? null : Loc.Format("Notice_HotkeyBusy", binding.ToString()));
    }

    private void OnHotkeyStatusChanged(object? sender, EventArgs e) => UpdateHotkeyStatus();

    private void UpdateHotkeyStatus(string? message = null)
    {
        if (message is null && !PageHelpers.Host.HotkeyRegistered)
        {
            message = Loc.Format("Notice_HotkeyBusy", PageHelpers.Host.Settings.Current.Hotkey.ToString());
        }

        HotkeyStatus.Text = message ?? string.Empty;
        HotkeyStatus.Visibility = message is null ? Visibility.Collapsed : Visibility.Visible;
    }

    private static HotkeyModifiers CurrentModifiers()
    {
        HotkeyModifiers modifiers = HotkeyModifiers.None;
        if (IsDown(0x11))
        {
            modifiers |= HotkeyModifiers.Control;
        }

        if (IsDown(0x12))
        {
            modifiers |= HotkeyModifiers.Alt;
        }

        if (IsDown(0x10))
        {
            modifiers |= HotkeyModifiers.Shift;
        }

        if (IsDown(0x5B) || IsDown(0x5C))
        {
            modifiers |= HotkeyModifiers.Win;
        }

        return modifiers;
    }

    private static bool IsDown(int vk) => (GetKeyState(vk) & 0x8000) != 0;

    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern short GetKeyState(int vk);
}
