// Formato audio del dispositivo e conversione in mono float32 (Spec §4.1: il motore vuole PCM float32
// mono a 16 kHz in [-1, 1]). Aggiunto il 22/10/2026 alle 11:32.
using System.Buffers.Binary;

namespace Zzitto.Platform.Audio;

public enum SampleEncoding
{
    Float32,
    Pcm16,
    Pcm24,
    Pcm32,
}

/// <summary>Descrizione del formato di cattura (ricavata da WAVEFORMATEX/WAVEFORMATEXTENSIBLE).</summary>
public sealed record AudioFormat(int SampleRate, int Channels, SampleEncoding Encoding)
{
    public int BytesPerSample => Encoding switch
    {
        SampleEncoding.Float32 => 4,
        SampleEncoding.Pcm16 => 2,
        SampleEncoding.Pcm24 => 3,
        SampleEncoding.Pcm32 => 4,
        _ => throw new InvalidOperationException(),
    };

    public int BlockAlign => BytesPerSample * Channels;

    /// <summary>
    /// Interpreta i campi di WAVEFORMATEX (più quelli di WAVEFORMATEXTENSIBLE se formatTag = 0xFFFE).
    /// </summary>
    public static AudioFormat FromWaveFormat(ushort formatTag, int channels, int sampleRate, int bitsPerSample,
        int blockAlign, Guid subFormat)
    {
        const ushort WaveFormatPcm = 1;
        const ushort WaveFormatIeeeFloat = 3;
        const ushort WaveFormatExtensible = 0xFFFE;

        bool isFloat;
        if (formatTag == WaveFormatExtensible)
        {
            if (subFormat == KsDataFormatSubtypeIeeeFloat)
            {
                isFloat = true;
            }
            else if (subFormat == KsDataFormatSubtypePcm)
            {
                isFloat = false;
            }
            else
            {
                throw new NotSupportedException("Formato audio del microfono non supportato: " + subFormat);
            }
        }
        else if (formatTag == WaveFormatIeeeFloat)
        {
            isFloat = true;
        }
        else if (formatTag == WaveFormatPcm)
        {
            isFloat = false;
        }
        else
        {
            throw new NotSupportedException("Formato audio del microfono non supportato: tag " + formatTag);
        }

        if (channels <= 0 || sampleRate <= 0)
        {
            throw new NotSupportedException("Formato audio del microfono non valido.");
        }

        // Il contenitore effettivo per campione si ricava da blockAlign (es. 24 bit validi in 32 bit).
        int containerBits = blockAlign > 0 ? blockAlign * 8 / channels : bitsPerSample;
        SampleEncoding encoding = (isFloat, containerBits) switch
        {
            (true, 32) => SampleEncoding.Float32,
            (false, 16) => SampleEncoding.Pcm16,
            (false, 24) => SampleEncoding.Pcm24,
            (false, 32) => SampleEncoding.Pcm32,
            _ => throw new NotSupportedException($"Profondità audio non supportata: {containerBits} bit."),
        };
        return new AudioFormat(sampleRate, channels, encoding);
    }

    public static readonly Guid KsDataFormatSubtypePcm = new("00000001-0000-0010-8000-00aa00389b71");

    public static readonly Guid KsDataFormatSubtypeIeeeFloat = new("00000003-0000-0010-8000-00aa00389b71");
}

public static class SampleConverter
{
    /// <summary>
    /// Converte i frame interleaved del dispositivo in mono float (media dei canali).
    /// Restituisce il numero di campioni scritti in <paramref name="destination"/>.
    /// </summary>
    public static int ToMono(ReadOnlySpan<byte> source, AudioFormat format, Span<float> destination)
    {
        ArgumentNullException.ThrowIfNull(format);
        int frames = source.Length / format.BlockAlign;
        if (destination.Length < frames)
        {
            throw new ArgumentException("Buffer di destinazione troppo piccolo.", nameof(destination));
        }

        int bytesPerSample = format.BytesPerSample;
        int channels = format.Channels;
        float scale = 1f / channels;
        for (int f = 0; f < frames; f++)
        {
            float sum = 0f;
            int frameOffset = f * format.BlockAlign;
            for (int c = 0; c < channels; c++)
            {
                sum += ReadSample(source.Slice(frameOffset + (c * bytesPerSample), bytesPerSample), format.Encoding);
            }

            destination[f] = sum * scale;
        }

        return frames;
    }

    internal static float ReadSample(ReadOnlySpan<byte> bytes, SampleEncoding encoding)
    {
        switch (encoding)
        {
            case SampleEncoding.Float32:
                float v = BinaryPrimitives.ReadSingleLittleEndian(bytes);
                return float.IsFinite(v) ? Math.Clamp(v, -1f, 1f) : 0f;
            case SampleEncoding.Pcm16:
                return BinaryPrimitives.ReadInt16LittleEndian(bytes) / 32768f;
            case SampleEncoding.Pcm24:
                int s24 = bytes[0] | (bytes[1] << 8) | ((sbyte)bytes[2] << 16);
                return s24 / 8388608f;
            case SampleEncoding.Pcm32:
                return BinaryPrimitives.ReadInt32LittleEndian(bytes) / 2147483648f;
            default:
                throw new ArgumentOutOfRangeException(nameof(encoding));
        }
    }
}
