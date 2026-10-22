// Composizione dell'app residente: archivi, licenza, motore, microfono, inserimento, area di notifica,
// icona flottante, scorciatoia globale e finestra delle impostazioni.
// Spec §2.4, §2.5, §2.6, §4.2, §4.3, §5, §8.1, §8.2, §10. Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Dispatching;
using Zzitto.App.Views;
using Zzitto.Core.Licensing;
using Zzitto.Licensing;
using Zzitto.Platform.Audio;
using Zzitto.Platform.Dictation;
using Zzitto.Platform.Engine;
using Zzitto.Platform.Input;
using Zzitto.Platform.Overlay;
using Zzitto.Platform.Shell;
using Zzitto.Platform.Storage;

namespace Zzitto.App.Services;

internal enum SettingsSection
{
    General,
    Dictionary,
    Shortcuts,
    History,
    License,
    OpenSource,
    Help,
    Tour,
}

internal sealed class AppHost : IDisposable
{
    // Comandi dei menu (area di notifica e icona flottante).
    private const int CmdToggle = 1;
    private const int CmdCancel = 2;
    private const int CmdUndo = 3;
    private const int CmdSettings = 4;
    private const int CmdHelp = 5;
    private const int CmdBuy = 6;
    private const int CmdExit = 7;
    private const int CmdToggleOverlay = 8;
    private const int CmdHotkey = 9;            // 22/10/2026 17:19: scorciatoia visibile e modificabile dal menu
    private const int CmdOverlayBase = 300;     // + OverlayMode (22/10/2026 17:19)
    private const int CmdModeBase = 100;      // + CorrectionMode
    private const int CmdTranslateBase = 200; // + TranslateTarget

    private readonly Action _exit;
    private readonly ShellHost _shell = new();
    private ZzEngine? _engine;
    private OverlayCoordinator? _overlay;
    private SettingsWindow? _window;
    private bool _hotkeyRegistered;
    private bool _disposed;

    public AppHost(DispatcherQueue dispatcher, Action exit)
    {
        Dispatcher = dispatcher ?? throw new ArgumentNullException(nameof(dispatcher));
        _exit = exit ?? throw new ArgumentNullException(nameof(exit));

        string folder = DataFolder();
        var protector = new DpapiDataProtector();
        Settings = new SettingsStore(folder);
        Vocabulary = new VocabularyStore(folder, protector);
        History = new HistoryStore(folder, protector, Settings);
        License = CreateLicenseService();

        var native = new ZzNativeLibrary();
        _engine = CreateEngine(native, Settings.Current.UseGpu, out string? engineError);
        EngineError = engineError;

        var clipboard = new ClipboardService(() => _shell.HostWindowHandle);
        Controller = new DictationController(
            _engine,
            new WasapiMicrophone(() => Settings.Current.MicrophoneId), // Spec §5.7: microfono scelto (22/10/2026 17:43)
            new ZzVadFactory(native),
            new WindowsTextTarget(clipboard),
            License,
            Settings,
            History,
            onListeningStarted: () =>
            {
                if (Settings.Current.StartSound)
                {
                    StartSound.Play();
                }
            });
    }

    public DispatcherQueue Dispatcher { get; }

    public SettingsStore Settings { get; }

    public VocabularyStore Vocabulary { get; }

    public HistoryStore History { get; }

    public ILicenseService License { get; }

    public DictationController Controller { get; }

    /// <summary>Motivo per cui il motore non è disponibile (null se tutto è in ordine).</summary>
    public string? EngineError { get; }

    /// <summary>Handle della finestra delle impostazioni (per l'acquisto nello Store, Spec §8.2).</summary>
    public IntPtr SettingsWindowHandle => _window?.WindowHandle ?? IntPtr.Zero;

    /// <summary>true se la scorciatoia globale è registrata (false: combinazione già usata da un'altra app).</summary>
    public bool HotkeyRegistered => _hotkeyRegistered;

    /// <summary>Stato della licenza, notificato sempre sul thread dell'interfaccia (DispatcherQueue).</summary>
    public event EventHandler<LicenseState>? LicenseStateChanged;

    public event EventHandler? HotkeyStatusChanged;

    public void Start(bool openSettings)
    {
        _shell.Start(IconVisual.Idle, TrayTooltip());
        _shell.HotkeyPressed += (_, _) => Toggle();
        _shell.CancelHotkeyPressed += (_, _) => Controller.Cancel();
        _shell.TrayClicked += (_, _) => Dispatcher.TryEnqueue(() => OpenSettings(SettingsSection.General));
        _shell.TrayMenuRequested += (s, p) => _ = ShowMenuAsync(tray: true, p.X, p.Y);
        _shell.OverlayClicked += (_, _) => Toggle();
        _shell.OverlayMenuRequested += (s, p) => _ = ShowMenuAsync(tray: false, p.X, p.Y);

        Controller.StateMachine.StateChanged += (_, _) => UpdateIndicators();
        Controller.Notice += (_, notice) => OnNotice(notice);
        Controller.PurchaseRequired += (_, _) => Dispatcher.TryEnqueue(() => OpenSettings(SettingsSection.License));

        // Spec §8.2: StateChanged può arrivare da un thread dello Store: si riporta sul thread dell'interfaccia.
        License.StateChanged += (_, state) => Dispatcher.TryEnqueue(() =>
        {
            UpdateIndicators();
            LicenseStateChanged?.Invoke(this, state);
        });

        Settings.Changed += (_, _) => Dispatcher.TryEnqueue(UpdateIndicators);
        Vocabulary.Changed += (_, _) => _ = SyncVocabularyAsync();

        _overlay = new OverlayCoordinator(_shell, Settings, () => Controller.State == DictationState.Idle);
        _overlay.Refresh();

        _ = ApplyHotkeyAsync(Settings.Current.Hotkey);
        _ = SyncVocabularyAsync();
        _ = RefreshLicenseAsync();
        UpdateIndicators();

        if (EngineError is not null)
        {
            _shell.ShowBalloon("Zzitto", EngineError, warning: true);
        }

        // Spec §5.8: al primo avvio parte il tour; altrimenti la finestra si apre solo su richiesta (Spec §2.5).
        if (!Settings.Current.TourCompleted)
        {
            OpenSettings(SettingsSection.Tour);
        }
        else if (openSettings)
        {
            OpenSettings(SettingsSection.General);
        }
    }

    /// <summary>Apre (o porta in primo piano) la finestra delle impostazioni. Solo dal thread dell'interfaccia.</summary>
    public void OpenSettings(SettingsSection section)
    {
        if (_disposed)
        {
            return;
        }

        if (_window is null)
        {
            _window = new SettingsWindow(this);
            _window.Closed += (_, _) => _window = null;
        }

        _window.Navigate(section);
        _window.Activate();
    }

    /// <summary>Registra la scorciatoia globale (Spec §2.4); false se è già usata da un'altra applicazione.</summary>
    public async Task<bool> ApplyHotkeyAsync(HotkeyBinding binding)
    {
        bool ok = await _shell.SetDictationHotkeyAsync(binding).ConfigureAwait(false);
        _hotkeyRegistered = ok;
        Dispatcher.TryEnqueue(() =>
        {
            UpdateIndicators();
            HotkeyStatusChanged?.Invoke(this, EventArgs.Empty);
        });
        if (!ok)
        {
            _shell.ShowBalloon("Zzitto", Loc.Format("Notice_HotkeyBusy", binding.ToString()), warning: true);
        }

        return ok;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _window?.Close();
        _overlay?.Dispose();
        Controller.Dispose();
        _shell.Dispose();
        _engine?.Dispose();
        _engine = null;
        (License as IDisposable)?.Dispose();
    }

    private static string DataFolder()
    {
        try
        {
            // App pacchettizzata: cartella dati privata del pacchetto, rimossa con la disinstallazione.
            return Windows.Storage.ApplicationData.Current.LocalFolder.Path;
        }
        catch (Exception)
        {
            string fallback = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Zzitto");
            Directory.CreateDirectory(fallback);
            return fallback;
        }
    }

    private static ILicenseService CreateLicenseService()
    {
#if DEBUG
        // Avviata da Visual Studio l'app non è installata dallo Store: la licenza reale risulterebbe non attiva.
        // In Debug si simula la prova (Zzitto.Licensing, agente D). In Release si usa sempre lo Store (Spec §8.2).
        return new StoreLicenseService(
            new DevelopmentStoreLicenseSource(),
            new ProtectedLicenseCache(FileBlobStore.CreateDefault(), new DpapiProtector()),
            TimeProvider.System);
#else
        return StoreLicenseService.CreateDefault();
#endif
    }

    private static ZzEngine? CreateEngine(IZzNative native, bool useGpu, out string? error)
    {
        error = null;
        try
        {
            ZzEngine engine = ZzEngine.Create(native, ModelManifest.CreateConfig(AppContext.BaseDirectory, useGpu));
            if (!ModelManifest.CanUseEngine(engine.Version))
            {
                // Spec §7: modelli non verificati (hash non impostati) → il motore reale non li carica.
                engine.Dispose();
                error = Loc.Get("Error_ModelsNotVerified");
                return null;
            }

            return engine;
        }
        catch (Exception ex) when (ex is DllNotFoundException or BadImageFormatException or EntryPointNotFoundException or ZzEngineException)
        {
            error = Loc.Get("Error_EngineUnavailable");
            return null;
        }
    }

    private async Task RefreshLicenseAsync()
    {
        try
        {
            // Spec §8.1: durante la prova la licenza si legge a ogni avvio; senza rete la prova continua.
            await License.RefreshAsync().ConfigureAwait(false);
        }
        catch (Exception)
        {
            // RefreshAsync non dovrebbe lanciare (contratto ILicenseService); per sicurezza si ignora.
        }

        Dispatcher.TryEnqueue(() =>
        {
            UpdateIndicators();
            LicenseStateChanged?.Invoke(this, License.Current);
        });
    }

    private async Task SyncVocabularyAsync()
    {
        if (_engine is null)
        {
            return;
        }

        try
        {
            VocabularyData data = Vocabulary.Load();
            await _engine.SetVocabularyAsync(
                data.Words,
                data.Shortcuts.Select(s => new KeyValuePair<string, string>(s.Keyword, s.Expansion)).ToList()).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // Il dizionario è un aiuto alla trascrizione: un errore non blocca la dettatura.
        }
    }

    private void Toggle()
    {
        // Mai sul thread della shell: l'avvio del microfono può richiedere qualche decina di millisecondi.
        _ = Task.Run(Controller.ToggleAsync);
    }

    private void UpdateIndicators()
    {
        DictationState state = Controller.State;
        bool blocked = !License.Current.CanDictate;
        IconVisual visual = state switch
        {
            DictationState.Listening => IconVisual.Listening,
            DictationState.Transcribing => IconVisual.Transcribing,
            DictationState.Correcting => IconVisual.Correcting,
            DictationState.Inserting => IconVisual.Inserting,
            DictationState.Error => IconVisual.Error,
            _ => blocked ? IconVisual.Blocked : IconVisual.Idle,
        };
        _shell.SetOverlayVisual(visual);
        _shell.SetTrayState(visual, TrayTooltip());

        // Spec §5.2: Esc annulla, registrato solo mentre la dettatura è in corso.
        _shell.SetCancelHotkeyEnabled(state is DictationState.Listening or DictationState.Transcribing or DictationState.Correcting);
        if (state == DictationState.Idle)
        {
            _overlay?.Refresh();
        }
    }

    private string TrayTooltip()
    {
        string hotkey = Settings.Current.Hotkey.ToString();
        LicenseState license = License.Current;
        string status = license.Status switch
        {
            LicenseStatus.Trial => Loc.Format("Tray_Trial", license.TrialDaysLeft),
            LicenseStatus.TrialExpired => Loc.Get("Tray_TrialExpired"),
            LicenseStatus.Unknown => Loc.Get("Tray_LicenseUnknown"),
            _ => string.Empty,
        };
        string text = Loc.Format("Tray_Tooltip", hotkey);
        return string.IsNullOrEmpty(status) ? text : text + "\n" + status;
    }

    private void OnNotice(DictationNotice notice)
    {
        switch (notice)
        {
            case DictationNotice.ClipboardFallback:
                // Spec §2.6: piccolo avviso che invita a incollare con Ctrl+V.
                _shell.FlashOverlay(IconVisual.Notice);
                _shell.ShowBalloon("Zzitto", Loc.Get("Notice_ClipboardFallback"), warning: false);
                break;
            case DictationNotice.CorrectionFallback:
                _shell.ShowBalloon("Zzitto", Loc.Get("Notice_CorrectionFallback"), warning: false);
                break;
            case DictationNotice.MaxDurationReached:
                _shell.ShowBalloon("Zzitto", Loc.Get("Notice_MaxDuration"), warning: false);
                break;
            case DictationNotice.MicrophoneAccessDenied:
                _shell.ShowBalloon("Zzitto", Loc.Get("Notice_MicrophoneDenied"), warning: true);
                break;
            case DictationNotice.MicrophoneError:
                _shell.ShowBalloon("Zzitto", Loc.Get("Notice_MicrophoneError"), warning: true);
                break;
            case DictationNotice.EngineError:
                _shell.ShowBalloon("Zzitto", EngineError ?? Loc.Get("Notice_EngineError"), warning: true);
                break;
            case DictationNotice.ModelIntegrityError:
                _shell.ShowBalloon("Zzitto", Loc.Get("Notice_ModelIntegrity"), warning: true);
                break;
            case DictationNotice.InsertionFailed:
                _shell.ShowBalloon("Zzitto", Loc.Get("Notice_InsertionFailed"), warning: true);
                break;
            case DictationNotice.PurchaseRequired:
                _shell.ShowBalloon("Zzitto", License.Current.Status == LicenseStatus.Unknown
                    ? Loc.Get("Notice_LicenseUnknown")
                    : Loc.Get("Notice_TrialExpired"), warning: true);
                break;
            case DictationNotice.CommandExecuted:
            case DictationNotice.Undone:
            case DictationNotice.NoSpeech:
            case DictationNotice.NothingToUndo:
                // Spec §5.9: conferma visiva sull'icona, senza finestre.
                _shell.FlashOverlay(IconVisual.Notice);
                break;
        }
    }

    private async Task ShowMenuAsync(bool tray, int x, int y)
    {
        ZzittoSettings settings = Settings.Current;
        DictationState state = Controller.State;
        bool active = state != DictationState.Idle && state != DictationState.Error;
        var items = new List<MenuItemSpec>();

        if (tray)
        {
            items.Add(new MenuItemSpec(CmdToggle, Loc.Format(state == DictationState.Listening ? "Menu_Stop" : "Menu_Start", settings.Hotkey.ToString()),
                Enabled: state is DictationState.Idle or DictationState.Error or DictationState.Listening));
        }

        // Spec §5.1: annulla dal menu (pressione lunga sull'icona); fuori dalla dettatura annulla l'ultimo inserimento (§5.4).
        items.Add(active ? new MenuItemSpec(CmdCancel, Loc.Get("Menu_Cancel")) : new MenuItemSpec(CmdUndo, Loc.Get("Menu_Undo")));
        items.Add(MenuItemSpec.Separator);
        items.Add(new MenuItemSpec(0, Loc.Get("Menu_Mode"), Children: new[]
        {
            new MenuItemSpec(CmdModeBase + (int)CorrectionMode.Faithful, Loc.Get("Mode_Faithful"), settings.Mode == CorrectionMode.Faithful),
            new MenuItemSpec(CmdModeBase + (int)CorrectionMode.Clean, Loc.Get("Mode_Clean"), settings.Mode == CorrectionMode.Clean),
            new MenuItemSpec(CmdModeBase + (int)CorrectionMode.Formal, Loc.Get("Mode_Formal"), settings.Mode == CorrectionMode.Formal),
        }));
        // Spec §5.12: selettore della traduzione.
        items.Add(new MenuItemSpec(0, Loc.Get("Menu_Translate"), Children: new[]
        {
            new MenuItemSpec(CmdTranslateBase + (int)TranslateTarget.None, Loc.Get("Translate_None"), settings.Translate == TranslateTarget.None),
            new MenuItemSpec(CmdTranslateBase + (int)TranslateTarget.English, Loc.Get("Translate_English"), settings.Translate == TranslateTarget.English),
            new MenuItemSpec(CmdTranslateBase + (int)TranslateTarget.Spanish, Loc.Get("Translate_Spanish"), settings.Translate == TranslateTarget.Spanish),
        }));
        // Spec §2.4, §2.6 (22/10/2026 17:19, richiesta del titolare): posizione dell'icona e scorciatoia
        // raggiungibili direttamente dal menu dell'area di notifica.
        items.Add(new MenuItemSpec(0, Loc.Get("Menu_Overlay"), Children: new[]
        {
            new MenuItemSpec(CmdOverlayBase + (int)OverlayMode.FixedPosition, Loc.Get("Overlay_Fixed"), settings.OverlayMode == OverlayMode.FixedPosition),
            new MenuItemSpec(CmdOverlayBase + (int)OverlayMode.FollowField, Loc.Get("Overlay_Follow"), settings.OverlayMode == OverlayMode.FollowField),
            new MenuItemSpec(CmdOverlayBase + (int)OverlayMode.Hidden, Loc.Get("Overlay_Hidden"), settings.OverlayMode == OverlayMode.Hidden),
        }));
        items.Add(new MenuItemSpec(CmdHotkey, Loc.Format("Menu_Hotkey", settings.Hotkey.ToString())));
        items.Add(MenuItemSpec.Separator);
        items.Add(new MenuItemSpec(CmdSettings, Loc.Get("Menu_Settings")));
        items.Add(new MenuItemSpec(CmdHelp, Loc.Get("Menu_Help")));
        if (License.Current.Status != LicenseStatus.Purchased)
        {
            items.Add(new MenuItemSpec(CmdBuy, Loc.Get("Menu_Buy")));
        }

        if (tray)
        {
            items.Add(MenuItemSpec.Separator);
            items.Add(new MenuItemSpec(CmdExit, Loc.Get("Menu_Exit")));
        }

        int? command = await _shell.ShowMenuAsync(items, x, y).ConfigureAwait(false);
        if (command is int id)
        {
            ExecuteCommand(id);
        }
    }

    private void ExecuteCommand(int id)
    {
        switch (id)
        {
            case CmdToggle:
                Toggle();
                break;
            case CmdCancel:
                Controller.Cancel();
                break;
            case CmdUndo:
                _ = Task.Run(Controller.UndoLastInsertAsync);
                break;
            case CmdSettings:
                Dispatcher.TryEnqueue(() => OpenSettings(SettingsSection.General));
                break;
            case CmdHelp:
                Dispatcher.TryEnqueue(() => OpenSettings(SettingsSection.Help));
                break;
            case CmdBuy:
                Dispatcher.TryEnqueue(() => OpenSettings(SettingsSection.License));
                break;
            case CmdExit:
                Dispatcher.TryEnqueue(() => _exit());
                break;
            case CmdToggleOverlay:
                Settings.Update(s => s with
                {
                    OverlayMode = s.OverlayMode == OverlayMode.Hidden ? OverlayMode.FixedPosition : OverlayMode.Hidden,
                });
                break;
            case CmdHotkey:
                Dispatcher.TryEnqueue(() => OpenSettings(SettingsSection.General));
                break;
            case >= CmdOverlayBase and < CmdOverlayBase + 3:
                Settings.Update(s => s with { OverlayMode = (OverlayMode)(id - CmdOverlayBase) });
                break;
            case >= CmdModeBase and < CmdModeBase + 3:
                Settings.Update(s => s with { Mode = (CorrectionMode)(id - CmdModeBase) });
                break;
            case >= CmdTranslateBase and < CmdTranslateBase + 3:
                Settings.Update(s => s with { Translate = (TranslateTarget)(id - CmdTranslateBase) });
                break;
        }
    }
}
