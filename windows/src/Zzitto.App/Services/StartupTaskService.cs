// Avvio automatico con Windows tramite StartupTask del pacchetto MSIX (Spec §2.4).
// Aggiunto il 22/10/2026 alle 11:32.
// Revisione del 22/10/2026 alle 17:46: fuori dal pacchetto (exe di test) si usa la chiave Run dell'utente
// (Zzitto.Platform.Shell.StartupRegistration), con gli stessi stati Enabled/Disabled.
using Windows.ApplicationModel;
using Zzitto.Platform.Shell;

namespace Zzitto.App.Services;

internal static class StartupTaskService
{
    /// <summary>Uguale a TaskId in Package.appxmanifest.</summary>
    public const string TaskId = "ZzittoStartup";

    public static async Task<StartupTaskState?> GetStateAsync()
    {
        if (Unpackaged() is { } registration)
        {
            return registration.IsEnabled ? StartupTaskState.Enabled : StartupTaskState.Disabled;
        }

        try
        {
            StartupTask task = await StartupTask.GetAsync(TaskId);
            return task.State;
        }
        catch (Exception)
        {
            return null; // app non pacchettizzata o attività assente
        }
    }

    /// <summary>Attiva o disattiva; restituisce lo stato risultante (l'utente o i criteri possono impedirlo).</summary>
    public static async Task<StartupTaskState?> SetEnabledAsync(bool enabled)
    {
        if (Unpackaged() is { } registration)
        {
            return registration.SetEnabled(enabled) ? StartupTaskState.Enabled : StartupTaskState.Disabled;
        }

        try
        {
            StartupTask task = await StartupTask.GetAsync(TaskId);
            if (enabled)
            {
                return await task.RequestEnableAsync();
            }

            task.Disable();
            return task.State;
        }
        catch (Exception)
        {
            return null;
        }
    }

    public static bool IsEnabled(StartupTaskState? state) =>
        state is StartupTaskState.Enabled or StartupTaskState.EnabledByPolicy;

    public static bool CanChange(StartupTaskState? state) =>
        state is StartupTaskState.Enabled or StartupTaskState.Disabled;

    /// <summary>Registrazione nel registro se l'app non è pacchettizzata, altrimenti null (si usa StartupTask).</summary>
    private static StartupRegistration? Unpackaged()
    {
        try
        {
            _ = Package.Current.Id;
            return null;
        }
        catch (InvalidOperationException)
        {
            // Nessuna identità di pacchetto.
        }
        catch (System.Runtime.InteropServices.COMException)
        {
            // Nessuna identità di pacchetto (APPMODEL_ERROR_NO_PACKAGE).
        }

        string? exe = Environment.ProcessPath;
        return string.IsNullOrEmpty(exe) ? null : new StartupRegistration(exe);
    }
}
