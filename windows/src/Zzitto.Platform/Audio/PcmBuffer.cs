// Buffer dell'audio dettato: solo in memoria, con limite di durata, azzerato dopo l'uso.
// Spec §5.2 (durata massima configurabile), §10 (audio mai su disco, distrutto dopo la trascrizione).
// Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Audio;

public sealed class PcmBuffer
{
    private readonly object _lock = new();
    private float[] _data;
    private int _count;

    public PcmBuffer(int maxSamples, int initialCapacity = 16000 * 30)
    {
        if (maxSamples <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(maxSamples));
        }

        MaxSamples = maxSamples;
        _data = new float[Math.Min(Math.Max(initialCapacity, 1), maxSamples)];
    }

    public int MaxSamples { get; }

    public int Count
    {
        get
        {
            lock (_lock)
            {
                return _count;
            }
        }
    }

    public bool IsFull => Count >= MaxSamples;

    /// <summary>Aggiunge campioni; restituisce false se il limite è stato raggiunto (il resto è scartato).</summary>
    public bool Append(ReadOnlySpan<float> samples)
    {
        lock (_lock)
        {
            int room = MaxSamples - _count;
            int take = Math.Min(room, samples.Length);
            if (take > 0)
            {
                EnsureCapacity(_count + take);
                samples[..take].CopyTo(_data.AsSpan(_count));
                _count += take;
            }

            return take == samples.Length && _count < MaxSamples;
        }
    }

    /// <summary>
    /// Consegna l'array interno (valido fino a <see cref="Clear"/>) senza copiare l'audio.
    /// </summary>
    public (float[] Samples, int Count) Snapshot()
    {
        lock (_lock)
        {
            return (_data, _count);
        }
    }

    /// <summary>Azzera la memoria dell'audio (Spec §10) e svuota il buffer.</summary>
    public void Clear()
    {
        lock (_lock)
        {
            Array.Clear(_data);
            _count = 0;
        }
    }

    private void EnsureCapacity(int required)
    {
        if (required <= _data.Length)
        {
            return;
        }

        int newSize = (int)Math.Min((long)MaxSamples, Math.Max((long)_data.Length * 2, required));
        var bigger = new float[newSize];
        _data.AsSpan(0, _count).CopyTo(bigger);
        Array.Clear(_data); // nessuna copia dell'audio resta in memoria
        _data = bigger;
    }
}
