// Suono breve all'avvio dell'ascolto (Spec §5.7 "vibrazione/suono all'avvio dell'ascolto"; su Windows solo suono).
// Il WAV è generato in memoria: nessun file audio su disco. Aggiunto il 22/10/2026 alle 11:32.
using System.Buffers.Binary;
using System.Runtime.InteropServices;
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Audio;

public static unsafe class StartSound
{
    private static readonly Lazy<IntPtr> Wave = new(CreateWave, LazyThreadSafetyMode.ExecutionAndPublication);

    /// <summary>Riproduce il suono senza attendere (SND_ASYNC).</summary>
    public static void Play()
    {
        IntPtr wave = Wave.Value;
        if (wave != IntPtr.Zero)
        {
            Win32.PlaySoundW((byte*)wave, IntPtr.Zero, Win32.SND_MEMORY | Win32.SND_ASYNC | Win32.SND_NODEFAULT);
        }
    }

    /// <summary>WAV PCM 16 bit mono 22050 Hz: due toni brevi ascendenti con dissolvenza.</summary>
    internal static byte[] BuildWave()
    {
        const int rate = 22050;
        const int samples = rate * 120 / 1000; // 120 ms
        int dataBytes = samples * 2;
        byte[] wav = new byte[44 + dataBytes];
        Span<byte> s = wav;
        "RIFF"u8.CopyTo(s);
        BinaryPrimitives.WriteInt32LittleEndian(s[4..], 36 + dataBytes);
        "WAVE"u8.CopyTo(s[8..]);
        "fmt "u8.CopyTo(s[12..]);
        BinaryPrimitives.WriteInt32LittleEndian(s[16..], 16);
        BinaryPrimitives.WriteInt16LittleEndian(s[20..], 1);      // PCM
        BinaryPrimitives.WriteInt16LittleEndian(s[22..], 1);      // mono
        BinaryPrimitives.WriteInt32LittleEndian(s[24..], rate);
        BinaryPrimitives.WriteInt32LittleEndian(s[28..], rate * 2);
        BinaryPrimitives.WriteInt16LittleEndian(s[32..], 2);
        BinaryPrimitives.WriteInt16LittleEndian(s[34..], 16);
        "data"u8.CopyTo(s[36..]);
        BinaryPrimitives.WriteInt32LittleEndian(s[40..], dataBytes);

        for (int i = 0; i < samples; i++)
        {
            double t = (double)i / rate;
            double frequency = i < samples / 2 ? 880 : 1320;
            double envelope = Math.Min(1.0, Math.Min(i, samples - i) / (rate * 0.01));
            short value = (short)(Math.Sin(2 * Math.PI * frequency * t) * envelope * 0.25 * short.MaxValue);
            BinaryPrimitives.WriteInt16LittleEndian(s[(44 + (i * 2))..], value);
        }

        return wav;
    }

    private static IntPtr CreateWave()
    {
        // Memoria non gestita mai liberata: PlaySound asincrono la legge dopo il ritorno della chiamata.
        byte[] wav = BuildWave();
        IntPtr memory = Marshal.AllocHGlobal(wav.Length);
        Marshal.Copy(wav, 0, memory, wav.Length);
        return memory;
    }
}
