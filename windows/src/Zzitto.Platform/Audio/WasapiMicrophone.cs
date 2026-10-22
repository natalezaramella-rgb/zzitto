// Cattura del microfono con WASAPI in modalità condivisa, a eventi, sul dispositivo di registrazione
// predefinito; conversione in mono float e ricampionamento a 16 kHz in streaming.
// Spec §4.3 "win-audio", §10 (audio solo in memoria), §11 (avvio dell'ascolto < 300 ms).
// Aggiunto il 22/10/2026 alle 11:32.
// Revisione del 22/10/2026 alle 16:15 (Spec §11, streaming): ogni pacchetto WASAPI (~10 ms) è consegnato subito, già a
// 16 kHz mono, a VAD e flusso del motore; i buffer di lavoro si azzerano anche quando la cattura termina per errore.
using System.Runtime.InteropServices;
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Audio;

public sealed class WasapiMicrophone : IAudioSource
{
    private readonly object _lock = new();
    private Thread? _thread;
    private volatile bool _stopRequested;

    public void Start(SamplesAvailableHandler onSamples, Action<Exception> onError)
    {
        ArgumentNullException.ThrowIfNull(onSamples);
        ArgumentNullException.ThrowIfNull(onError);

        lock (_lock)
        {
            if (_thread is not null)
            {
                throw new InvalidOperationException("Cattura già avviata.");
            }

            _stopRequested = false;
            var init = new InitSignal();
            var thread = new Thread(() => CaptureLoop(onSamples, onError, init))
            {
                IsBackground = true,
                Name = "Zzitto WASAPI",
                Priority = ThreadPriority.AboveNormal,
            };
            // WASAPI si usa da un thread MTA dedicato.
            thread.SetApartmentState(ApartmentState.MTA);
            thread.Start();

            // L'inizializzazione dura poche decine di millisecondi; un errore si riporta subito al chiamante.
            if (!init.Done.Wait(TimeSpan.FromSeconds(3)))
            {
                _stopRequested = true;
                throw new MicrophoneException("Il microfono non risponde.", unchecked((int)0x800705B4)); // ERROR_TIMEOUT
            }

            if (init.Error is not null)
            {
                thread.Join(TimeSpan.FromSeconds(1));
                throw init.Error as MicrophoneException ?? new MicrophoneException(init.Error.Message, init.Error.HResult);
            }

            _thread = thread;
        }
    }

    public void Stop()
    {
        Thread? thread;
        lock (_lock)
        {
            thread = _thread;
            _thread = null;
            _stopRequested = true;
        }

        if (thread is not null && thread != Thread.CurrentThread)
        {
            thread.Join(TimeSpan.FromSeconds(2));
        }
    }

    private void CaptureLoop(SamplesAvailableHandler onSamples, Action<Exception> onError, InitSignal init)
    {
        IMMDeviceEnumerator? enumerator = null;
        IMMDevice? device = null;
        IAudioClient? client = null;
        IAudioCaptureClient? capture = null;
        IntPtr mixFormat = IntPtr.Zero;
        bool clientStarted = false;
        float[]? mono = null;
        float[]? resampled = null;
        using var dataReady = new AutoResetEvent(false);

        try
        {
            enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            Check(enumerator.GetDefaultAudioEndpoint(EDataFlow.Capture, ERole.Console, out device), "Nessun microfono disponibile.");

            Guid iidAudioClient = WasapiConstants.IidIAudioClient;
            Check(device.Activate(ref iidAudioClient, WasapiConstants.ClsctxAll, IntPtr.Zero, out object clientObject),
                "Accesso al microfono non riuscito.");
            client = (IAudioClient)clientObject;

            Check(client.GetMixFormat(out mixFormat), "Formato del microfono non leggibile.");
            AudioFormat format = ReadFormat(mixFormat);

            // Buffer di 100 ms, modalità condivisa a eventi.
            Check(client.Initialize(WasapiConstants.AudclntShareModeShared, WasapiConstants.AudclntStreamFlagsEventCallback,
                1_000_000, 0, mixFormat, IntPtr.Zero), "Inizializzazione del microfono non riuscita.");
            Check(client.SetEventHandle(dataReady.SafeWaitHandle.DangerousGetHandle()), "Inizializzazione del microfono non riuscita.");

            Guid iidCapture = WasapiConstants.IidIAudioCaptureClient;
            Check(client.GetService(ref iidCapture, out object captureObject), "Inizializzazione del microfono non riuscita.");
            capture = (IAudioCaptureClient)captureObject;

            Check(client.Start(), "Avvio del microfono non riuscito.");
            clientStarted = true;
            init.Complete(null);

            var resampler = new StreamingResampler(format.SampleRate, IAudioSource.TargetSampleRate);
            mono = new float[format.SampleRate]; // 1 s, ampliato se serve
            resampled = new float[resampler.MaxOutputFor(mono.Length)];

            while (!_stopRequested)
            {
                if (!dataReady.WaitOne(200))
                {
                    continue;
                }

                while (!_stopRequested)
                {
                    Check(capture.GetNextPacketSize(out uint packetFrames), "Lettura del microfono non riuscita.");
                    if (packetFrames == 0)
                    {
                        break;
                    }

                    int hr = capture.GetBuffer(out IntPtr data, out uint frames, out uint flags, out _, out _);
                    if (hr == WasapiConstants.AudclntSBufferEmpty)
                    {
                        break;
                    }

                    Check(hr, "Lettura del microfono non riuscita.");
                    try
                    {
                        int frameCount = checked((int)frames);
                        if (mono.Length < frameCount)
                        {
                            // Spec §10: i buffer sostituiti si azzerano prima di abbandonarli.
                            Array.Clear(mono);
                            Array.Clear(resampled);
                            mono = new float[frameCount];
                            resampled = new float[resampler.MaxOutputFor(frameCount)];
                        }

                        Span<float> monoSpan = mono.AsSpan(0, frameCount);
                        if ((flags & WasapiConstants.AudclntBufferFlagsSilent) != 0 || data == IntPtr.Zero)
                        {
                            monoSpan.Clear();
                        }
                        else
                        {
                            ReadOnlySpan<byte> bytes;
                            unsafe
                            {
                                bytes = new ReadOnlySpan<byte>((void*)data, frameCount * format.BlockAlign);
                            }

                            SampleConverter.ToMono(bytes, format, monoSpan);
                        }

                        int produced = resampler.Process(monoSpan, resampled);
                        if (produced > 0)
                        {
                            // Spec §11: consegna immediata del blocco (VAD + zz_stream_feed, che copia e ritorna subito).
                            onSamples(new ReadOnlySpan<float>(resampled, 0, produced));
                        }
                    }
                    finally
                    {
                        capture.ReleaseBuffer(frames);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            if (!init.Complete(ex))
            {
                onError(ex);
            }
        }
        finally
        {
            // Spec §10: nessun residuo dell'audio nei buffer di lavoro, anche se la cattura termina per errore.
            if (mono is not null)
            {
                Array.Clear(mono);
            }

            if (resampled is not null)
            {
                Array.Clear(resampled);
            }

            if (clientStarted)
            {
                client?.Stop();
            }

            if (mixFormat != IntPtr.Zero)
            {
                Marshal.FreeCoTaskMem(mixFormat);
            }

            Release(capture);
            Release(client);
            Release(device);
            Release(enumerator);
        }
    }

    /// <summary>Esito dell'inizializzazione del thread di cattura.</summary>
    private sealed class InitSignal
    {
        private int _completed;

        public ManualResetEventSlim Done { get; } = new(false);

        public Exception? Error { get; private set; }

        /// <summary>Restituisce false se l'inizializzazione era già stata segnalata.</summary>
        public bool Complete(Exception? error)
        {
            if (Interlocked.Exchange(ref _completed, 1) != 0)
            {
                return false;
            }

            Error = error;
            Done.Set();
            return true;
        }
    }

    private static AudioFormat ReadFormat(IntPtr waveFormat)
    {
        WaveFormatEx header = Marshal.PtrToStructure<WaveFormatEx>(waveFormat);
        Guid subFormat = Guid.Empty;
        if (header.FormatTag == WasapiConstants.WaveFormatExtensibleTag && header.Size >= 22)
        {
            subFormat = Marshal.PtrToStructure<WaveFormatExtensible>(waveFormat).SubFormat;
        }

        return AudioFormat.FromWaveFormat(header.FormatTag, header.Channels, (int)header.SamplesPerSec,
            header.BitsPerSample, header.BlockAlign, subFormat);
    }

    private static void Check(int hresult, string message)
    {
        if (hresult < 0)
        {
            throw new MicrophoneException(message, hresult);
        }
    }

    private static void Release(object? comObject)
    {
        if (comObject is not null && Marshal.IsComObject(comObject))
        {
            Marshal.ReleaseComObject(comObject);
        }
    }
}
