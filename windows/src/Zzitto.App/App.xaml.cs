// Applicazione residente: nessuna finestra all'avvio, solo area di notifica e icona flottante (Spec §2.4, §2.5).
// La finestra delle impostazioni si apre solo su richiesta o al primo avvio per il tour (Spec §5.8).
// Aggiunto il 22/10/2026 alle 11:32.
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;
using Zzitto.App.Services;

namespace Zzitto.App;

public partial class App : Application
{
    private AppHost? _host;

    public App()
    {
        Instance = this;
        InitializeComponent();
        UnhandledException += OnUnhandledException;
    }

    internal static App? Instance { get; private set; }

    internal AppHost? Host => _host;

    /// <summary>Attivazione reindirizzata da una seconda istanza (es. clic sul riquadro nel menu Start).</summary>
    internal void OnRedirectedActivation(AppActivationArguments args)
    {
        _host?.Dispatcher.TryEnqueue(() => _host?.OpenSettings(SettingsSection.General));
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // L'app vive senza finestre: il ciclo dei messaggi non deve chiudersi con l'ultima finestra.
        DispatcherShutdownMode = DispatcherShutdownMode.OnExplicitShutdown;

        AppActivationArguments activation = AppInstance.GetCurrent().GetActivatedEventArgs();
        bool fromStartup = activation.Kind == ExtendedActivationKind.StartupTask;

        _host = new AppHost(DispatcherQueue.GetForCurrentThread(), ExitApplication);
        _host.Start(openSettings: !fromStartup);
    }

    private void ExitApplication()
    {
        _host?.Dispose();
        _host = null;
        Exit();
    }

    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        // Spec §10: nessun testo dettato nei log; si registra solo il tipo di errore per il debug.
        System.Diagnostics.Debug.WriteLine("Zzitto: eccezione non gestita " + e.Exception.GetType().FullName);
        e.Handled = true;
    }
}
