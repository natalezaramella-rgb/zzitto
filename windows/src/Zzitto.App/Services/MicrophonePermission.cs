// Permesso del microfono per l'app pacchettizzata (Spec §5.8: il tour spiega come concederlo; Spec §10).
// Inizializzare MediaCapture in sola modalità audio fa comparire la richiesta di consenso di Windows.
// Aggiunto il 22/10/2026 alle 11:32.
using Windows.Media.Capture;
using Windows.System;

namespace Zzitto.App.Services;

internal static class MicrophonePermission
{
    /// <summary>true se l'accesso al microfono è consentito (chiede il consenso la prima volta).</summary>
    public static async Task<bool> RequestAsync()
    {
        try
        {
            using var capture = new MediaCapture();
            await capture.InitializeAsync(new MediaCaptureInitializationSettings
            {
                StreamingCaptureMode = StreamingCaptureMode.Audio,
                MediaCategory = MediaCategory.Speech,
            });
            return true;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
        catch (Exception)
        {
            // Nessun microfono collegato: il permesso non è il problema.
            return false;
        }
    }

    /// <summary>Apre Impostazioni → Privacy → Microfono.</summary>
    public static async Task OpenPrivacySettingsAsync()
    {
        await Launcher.LaunchUriAsync(new Uri("ms-settings:privacy-microphone"));
    }
}
