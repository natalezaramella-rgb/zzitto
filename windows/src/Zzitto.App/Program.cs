// Punto d'ingresso: istanza unica dell'app residente (Spec §2.4). Una seconda attivazione (es. dal menu Start)
// viene reindirizzata all'istanza già in esecuzione, che apre le impostazioni (Spec §2.5: solo su richiesta).
// Schema ufficiale Windows App SDK "single-instance". Aggiunto il 22/10/2026 alle 11:32.
using System.Runtime.InteropServices;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace Zzitto.App;

public static class Program
{
    private const string InstanceKey = "Zaramella.Zzitto.Main";

    [STAThread]
    private static int Main(string[] args)
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();
        if (DecideRedirection())
        {
            return 0;
        }

        // Build locale 22/10/2026 14:38: il parametro non si chiama "_", altrimenti "_ = new App()" lo assegnerebbe (CS0029).
        Application.Start(initParams =>
        {
            var context = new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread());
            SynchronizationContext.SetSynchronizationContext(context);
            _ = new App();
        });
        return 0;
    }

    private static bool DecideRedirection()
    {
        AppActivationArguments activation = AppInstance.GetCurrent().GetActivatedEventArgs();
        AppInstance keyInstance = AppInstance.FindOrRegisterForKey(InstanceKey);
        if (keyInstance.IsCurrent)
        {
            keyInstance.Activated += OnActivated;
            return false;
        }

        RedirectActivationTo(activation, keyInstance);
        return true;
    }

    private static void OnActivated(object? sender, AppActivationArguments args)
    {
        // Arriva su un thread di sistema: App lo riporta sul thread dell'interfaccia.
        App.Instance?.OnRedirectedActivation(args);
    }

    private static void RedirectActivationTo(AppActivationArguments args, AppInstance keyInstance)
    {
        // Permette all'istanza principale di portare in primo piano la finestra delle impostazioni.
        AllowSetForegroundWindow(keyInstance.ProcessId);

        using var done = new ManualResetEvent(false);
        _ = Task.Run(() =>
        {
            try
            {
                keyInstance.RedirectActivationToAsync(args).AsTask().Wait();
            }
            finally
            {
                done.Set();
            }
        });

        // Attesa che continua a gestire i messaggi COM del thread STA (schema ufficiale).
        IntPtr[] handles = { done.SafeWaitHandle.DangerousGetHandle() };
        _ = CoWaitForMultipleObjects(0, 0xFFFFFFFF, (uint)handles.Length, handles, out _);
    }

    [DllImport("ole32.dll", ExactSpelling = true)]
    private static extern int CoWaitForMultipleObjects(uint flags, uint timeout, uint count, IntPtr[] handles, out uint index);

    [DllImport("user32.dll", ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AllowSetForegroundWindow(uint processId);
}
