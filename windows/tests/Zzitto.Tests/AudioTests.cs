// Test di conversione, ricampionamento e buffer dell'audio (Spec §4.1, §5.2, §10, §12). Aggiunto il 22/10/2026 alle 11:32.
using System.Buffers.Binary;
using Zzitto.Platform.Audio;
using Zzitto.Platform.Engine;

namespace Zzitto.Tests;

public class AudioTests
{
    [Fact]
    public void Resampler_48kTo16k_KeepsSpeechBandAndLength()
    {
        var resampler = new StreamingResampler(48000, 16000);
        float[] input = Tone(1000, 48000, 48000, 0.5f);
        float[] output = new float[resampler.MaxOutputFor(input.Length)];

        int produced = resampler.Process(input, output);

        Assert.InRange(produced, 15999, 16001);
        Span<float> steady = output.AsSpan(1000, 14000);
        Assert.InRange(MaxAbs(steady), 0.47f, 0.52f);
        Assert.InRange(ZeroCrossingsPerSecond(steady, 16000), 1990, 2010); // 1 kHz = 2000 passaggi per zero al secondo
    }

    [Fact]
    public void Resampler_RemovesFrequenciesAboveNyquist()
    {
        // Senza filtro un tono a 12 kHz si ripiegherebbe a 4 kHz, nella banda della voce.
        var resampler = new StreamingResampler(48000, 16000);
        float[] input = Tone(12000, 48000, 48000, 0.5f);
        float[] output = new float[resampler.MaxOutputFor(input.Length)];
        int produced = resampler.Process(input, output);
        Assert.True(MaxAbs(output.AsSpan(1000, produced - 1000)) < 0.01f);
    }

    [Fact]
    public void Resampler_IsIndependentOfBlockSize()
    {
        float[] input = Tone(700, 44100, 22050, 0.3f);
        var whole = new StreamingResampler(44100, 16000);
        float[] a = new float[whole.MaxOutputFor(input.Length)];
        int countA = whole.Process(input, a);

        var chunked = new StreamingResampler(44100, 16000);
        var b = new List<float>();
        float[] buffer = new float[chunked.MaxOutputFor(441)];
        for (int offset = 0; offset < input.Length; offset += 441)
        {
            int n = chunked.Process(input.AsSpan(offset, Math.Min(441, input.Length - offset)), buffer);
            b.AddRange(buffer.AsSpan(0, n).ToArray());
        }

        Assert.Equal(countA, b.Count);
        for (int i = 0; i < countA; i++)
        {
            Assert.Equal(a[i], b[i], 5);
        }
    }

    [Fact]
    public void Resampler_SameRate_IsIdentity_AndDcGainIsOne()
    {
        var same = new StreamingResampler(16000, 16000);
        float[] output = new float[same.MaxOutputFor(3)];
        Assert.Equal(3, same.Process(new[] { 0.1f, 0.2f, 0.3f }, output));
        Assert.Equal(0.1f, output[0], 6);
        Assert.Equal(0.2f, output[1], 6);
        Assert.Equal(0.3f, output[2], 6);

        var down = new StreamingResampler(48000, 16000);
        float[] dc = Enumerable.Repeat(1f, 4800).ToArray();
        float[] outDc = new float[down.MaxOutputFor(dc.Length)];
        int n = down.Process(dc, outDc);
        Assert.Equal(1f, outDc[n - 1], 3);
    }

    [Fact]
    public void Converter_StereoFloat_IsAveraged()
    {
        var format = new AudioFormat(48000, 2, SampleEncoding.Float32);
        byte[] data = new byte[16];
        BinaryPrimitives.WriteSingleLittleEndian(data.AsSpan(0), 0.5f);
        BinaryPrimitives.WriteSingleLittleEndian(data.AsSpan(4), -0.5f);
        BinaryPrimitives.WriteSingleLittleEndian(data.AsSpan(8), 1f);
        BinaryPrimitives.WriteSingleLittleEndian(data.AsSpan(12), 0f);
        float[] mono = new float[2];

        Assert.Equal(2, SampleConverter.ToMono(data, format, mono));
        Assert.Equal(0f, mono[0], 6);
        Assert.Equal(0.5f, mono[1], 6);
    }

    [Fact]
    public void Converter_Pcm16And24()
    {
        byte[] pcm16 = new byte[4];
        BinaryPrimitives.WriteInt16LittleEndian(pcm16, short.MinValue);
        BinaryPrimitives.WriteInt16LittleEndian(pcm16.AsSpan(2), 16384);
        float[] mono = new float[2];
        SampleConverter.ToMono(pcm16, new AudioFormat(16000, 1, SampleEncoding.Pcm16), mono);
        Assert.Equal(-1f, mono[0], 6);
        Assert.Equal(0.5f, mono[1], 6);

        byte[] pcm24 = { 0x00, 0x00, 0xC0 }; // -0,5 in 24 bit
        float[] one = new float[1];
        SampleConverter.ToMono(pcm24, new AudioFormat(16000, 1, SampleEncoding.Pcm24), one);
        Assert.Equal(-0.5f, one[0], 6);
    }

    [Fact]
    public void Format_ParsesExtensibleFloatAndPcm()
    {
        AudioFormat f = AudioFormat.FromWaveFormat(0xFFFE, 2, 48000, 32, 8, AudioFormat.KsDataFormatSubtypeIeeeFloat);
        Assert.Equal(new AudioFormat(48000, 2, SampleEncoding.Float32), f);

        AudioFormat p = AudioFormat.FromWaveFormat(0xFFFE, 2, 44100, 32, 8, AudioFormat.KsDataFormatSubtypePcm);
        Assert.Equal(SampleEncoding.Pcm32, p.Encoding);

        AudioFormat p24 = AudioFormat.FromWaveFormat(1, 1, 16000, 24, 3, Guid.Empty);
        Assert.Equal(SampleEncoding.Pcm24, p24.Encoding);

        Assert.Throws<NotSupportedException>(() => AudioFormat.FromWaveFormat(0x55, 2, 44100, 16, 4, Guid.Empty));
    }

    [Fact]
    public void PcmBuffer_RespectsLimit_AndClearWipes()
    {
        var buffer = new PcmBuffer(maxSamples: 10, initialCapacity: 2);
        Assert.True(buffer.Append(new[] { 1f, 2f, 3f }));
        Assert.False(buffer.Append(new float[20]));
        Assert.Equal(10, buffer.Count);
        Assert.True(buffer.IsFull);

        (float[] samples, int count) = buffer.Snapshot();
        Assert.Equal(10, count);
        Assert.Equal(3f, samples[2]);

        buffer.Clear(); // Spec §10: audio distrutto dopo la trascrizione
        Assert.Equal(0, buffer.Count);
        Assert.All(samples.Take(10), s => Assert.Equal(0f, s));
    }

    [Fact]
    public void ModelManifest_PathsAndPlaceholders()
    {
        string root = Path.Combine("C:", "Zzitto");
        Assert.Equal(Path.Combine(root, "Models", "whisper-medium-q5_0.bin"), ModelManifest.WhisperPath(root));
        Assert.Equal(Path.Combine(root, "Models", "phi-4-mini-instruct-q4_k_m.gguf"), ModelManifest.LlmPath(root));
        Assert.Null(ModelManifest.NormalizeHash(ModelManifest.Placeholder));
        Assert.Equal(new string('a', 64), ModelManifest.NormalizeHash(new string('A', 64)));
        Assert.True(ModelManifest.CanUseEngine("zzitto-engine MOCK api=1"));
        EngineConfig config = ModelManifest.CreateConfig(root, useGpu: true);
        Assert.True(config.SequentialLoading);
        Assert.Equal(ModelManifest.HashesConfigured, config.WhisperSha256 is not null);
    }

    private static float[] Tone(double frequency, int rate, int count, float amplitude) =>
        Enumerable.Range(0, count).Select(i => amplitude * (float)Math.Sin(2 * Math.PI * frequency * i / rate)).ToArray();

    private static float MaxAbs(ReadOnlySpan<float> data)
    {
        float max = 0f;
        foreach (float v in data)
        {
            max = Math.Max(max, Math.Abs(v));
        }

        return max;
    }

    private static double ZeroCrossingsPerSecond(ReadOnlySpan<float> data, int rate)
    {
        int crossings = 0;
        for (int i = 1; i < data.Length; i++)
        {
            if ((data[i - 1] < 0) != (data[i] < 0))
            {
                crossings++;
            }
        }

        return crossings / (data.Length / (double)rate);
    }
}
