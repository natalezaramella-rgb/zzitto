// Sorgente audio del microfono: consegna blocchi PCM float32 mono a 16 kHz (Spec §4.1, §4.3 "win-audio").
// Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Audio;

/// <summary>Blocco di campioni a 16 kHz mono; valido solo durante la chiamata.</summary>
public delegate void SamplesAvailableHandler(ReadOnlySpan<float> samples16kMono);

public interface IAudioSource
{
    public const int TargetSampleRate = 16000;

    /// <summary>
    /// Avvia la cattura. Lancia un'eccezione se il microfono non è disponibile o l'accesso è negato.
    /// Le callback arrivano su un thread di cattura dedicato.
    /// </summary>
    void Start(SamplesAvailableHandler onSamples, Action<Exception> onError);

    /// <summary>Ferma la cattura e attende la fine del thread (nessuna callback dopo il ritorno).</summary>
    void Stop();
}

/// <summary>Errore del microfono (dispositivo assente, accesso negato dalle impostazioni privacy...).</summary>
public sealed class MicrophoneException : Exception
{
    public MicrophoneException(string message, int hresult)
        : base(message)
    {
        HResult = hresult;
    }

    public bool AccessDenied => HResult == unchecked((int)0x80070005);
}
