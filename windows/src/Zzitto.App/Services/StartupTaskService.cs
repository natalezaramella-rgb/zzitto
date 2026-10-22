// Avvio automatico con Windows tramite StartupTask del pacchetto MSIX (Spec §2.4).
// Aggiunto il 22/10/2026 alle 11:32.
using Windows.ApplicationModel;

namespace Zzitto.App.Services;

internal static class StartupTaskService
{
    /// <summary>Uguale a TaskId in Package.appxmanifest.</summary>
    public const string TaskId = "ZzittoStartup";

    public static async Task<StartupTaskState?> GetStateAsync()
    {
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
}
