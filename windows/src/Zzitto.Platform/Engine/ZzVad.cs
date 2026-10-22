// Rilevamento voce per lo stop automatico dopo il silenzio (Spec §5.2), basato su zz_vad_* del motore.
// Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Engine;

public interface IVoiceActivityDetector : IDisposable
{
    /// <summary>true quando c'è stato parlato seguito dal silenzio configurato.</summary>
    bool Process(ReadOnlySpan<float> pcm16kMono);

    bool HasSpeech { get; }

    void Reset();
}

public interface IVoiceActivityDetectorFactory
{
    IVoiceActivityDetector Create(int silenceMs);
}

public sealed class ZzVad : IVoiceActivityDetector
{
    private readonly IZzNative _native;
    private IntPtr _vad;

    public ZzVad(IZzNative native, int silenceMs)
    {
        _native = native ?? throw new ArgumentNullException(nameof(native));
        _vad = native.CreateVad(silenceMs);
        if (_vad == IntPtr.Zero)
        {
            throw new ZzEngineException(ZzStatus.OutOfMemory, "Impossibile creare il rilevatore di voce.");
        }
    }

    public bool HasSpeech => _vad != IntPtr.Zero && _native.VadHasSpeech(_vad);

    public bool Process(ReadOnlySpan<float> pcm16kMono)
    {
        ObjectDisposedException.ThrowIf(_vad == IntPtr.Zero, this);
        return _native.ProcessVad(_vad, pcm16kMono);
    }

    public void Reset()
    {
        if (_vad != IntPtr.Zero)
        {
            _native.ResetVad(_vad);
        }
    }

    public void Dispose()
    {
        IntPtr vad = Interlocked.Exchange(ref _vad, IntPtr.Zero);
        if (vad != IntPtr.Zero)
        {
            _native.DestroyVad(vad);
        }
    }
}

public sealed class ZzVadFactory : IVoiceActivityDetectorFactory
{
    private readonly IZzNative _native;

    public ZzVadFactory(IZzNative native)
    {
        _native = native ?? throw new ArgumentNullException(nameof(native));
    }

    public IVoiceActivityDetector Create(int silenceMs) => new ZzVad(_native, silenceMs);
}
