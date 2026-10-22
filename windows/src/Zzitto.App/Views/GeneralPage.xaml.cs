// Impostazioni generali (Spec §5.7, §2.4, §2.6, §4.2). Aggiunto il 22/10/2026 alle 11:32.
using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Zzitto.App.Services;
using Zzitto.Platform.Audio;
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
            FillMicrophones(s.MicrophoneId);
            PageHelpers.SelectByTag(ModeBox, s.Mode.ToString());
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

        string? language = PageHelpers.SelectedTag(LanguageBox);
        string? microphone = PageHelpers.SelectedTag(MicrophoneBox);
        bool hasMode = Enum.TryParse(PageHelpers.SelectedTag(ModeBox), out CorrectionMode mode);
        bool hasOverlay = Enum.TryParse(PageHelpers.SelectedTag(OverlayModeBox), out OverlayMode overlay);
        double silence = SilenceBox.Value;
        double maxDuration = MaxDurationBox.Value;
        double unload = UnloadBox.Value;
        bool voiceCommands = VoiceCommandsSwitch.IsOn;
        bool startSound = StartSoundSwitch.IsOn;
        bool clipboardSelection = ClipboardSelectionSwitch.IsOn;
        bool useGpu = GpuSwitch.IsOn;

        PageHelpers.Host.Settings.Update(s => s with
        {
            Language = language ?? s.Language,
            MicrophoneId = microphone is null ? s.MicrophoneId : (microphone.Length == 0 ? null : microphone),
            Mode = hasMode ? mode : s.Mode,
            OverlayMode = hasOverlay ? overlay : s.OverlayMode,
            SilenceTimeoutMs = double.IsNaN(silence) ? s.SilenceTimeoutMs : (int)Math.Round(silence * 1000),
            MaxDurationSeconds = double.IsNaN(maxDuration) ? s.MaxDurationSeconds : (int)Math.Round(maxDuration * 60),
            ModelUnloadMinutes = double.IsNaN(unload) ? s.ModelUnloadMinutes : (int)Math.Round(unload),
            VoiceCommandsEnabled = voiceCommands,
            StartSound = startSound,
            ClipboardSelectionFallback = clipboardSelection,
            UseGpu = useGpu,
        });
    }

    /// <summary>
    /// Spec §5.7 (22/10/2026 17:43): "Predefinito di sistema" più i microfoni attivi; un microfono scelto ma
    /// scollegato resta visibile, così la scelta non si perde quando lo si ricollega.
    /// </summary>
    private void FillMicrophones(string? selectedId)
    {
        MicrophoneBox.Items.Clear();
        IReadOnlyList<MicrophoneInfo> devices = MicrophoneDevices.List();
        MicrophoneInfo? systemDefault = devices.FirstOrDefault(d => d.IsSystemDefault);
        MicrophoneBox.Items.Add(new ComboBoxItem
        {
            Tag = string.Empty,
            Content = systemDefault is null ? Loc.Get("Microphone_Default") : Loc.Format("Microphone_DefaultWithName", systemDefault.Name),
        });
        foreach (MicrophoneInfo device in devices)
        {
            MicrophoneBox.Items.Add(new ComboBoxItem { Tag = device.Id, Content = device.Name });
        }

        if (selectedId is not null && MicrophoneSelection.Resolve(selectedId, devices) is null)
        {
            MicrophoneBox.Items.Add(new ComboBoxItem { Tag = selectedId, Content = Loc.Get("Microphone_Missing") });
        }

        PageHelpers.SelectByTag(MicrophoneBox, selectedId ?? string.Empty);
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
