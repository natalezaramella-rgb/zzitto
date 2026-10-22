// Ricampionamento in streaming verso 16 kHz (Spec §4.1, §2.4 "win-audio": WASAPI a 16 kHz mono).
// Aggiunto il 22/10/2026 alle 11:32.
//
// Filtro passa-basso FIR (sinc con finestra di Blackman, taglio a 0,45 × frequenza d'uscita) per evitare
// l'aliasing quando si riduce la frequenza, poi interpolazione lineare a passo frazionario.
// Lo stato è conservato tra un blocco e l'altro, così i blocchi WASAPI di qualsiasi lunghezza
// producono lo stesso risultato di un unico blocco.
namespace Zzitto.Platform.Audio;

public sealed class StreamingResampler
{
    private const int TapCount = 63;

    private readonly double _step;          // campioni d'ingresso per campione d'uscita
    private readonly float[]? _taps;        // null = nessun filtro (sovracampionamento o stessa frequenza)
    private readonly float[] _history;      // ultimi TapCount campioni d'ingresso (anello)
    private int _historyIndex;
    private bool _hasPrevious;
    private float _previous;                // ultimo campione filtrato
    private double _position;               // posizione della prossima uscita, relativa a _previous (0..1)

    public StreamingResampler(int inputRate, int outputRate)
    {
        if (inputRate <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(inputRate));
        }

        if (outputRate <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(outputRate));
        }

        InputRate = inputRate;
        OutputRate = outputRate;
        _step = (double)inputRate / outputRate;
        _history = new float[TapCount];
        if (inputRate > outputRate)
        {
            _taps = CreateLowPass(0.45 * outputRate / inputRate);
        }
    }

    public int InputRate { get; }

    public int OutputRate { get; }

    /// <summary>Numero massimo di campioni d'uscita per <paramref name="inputCount"/> campioni d'ingresso.</summary>
    public int MaxOutputFor(int inputCount) => (int)Math.Ceiling(inputCount / _step) + 2;

    /// <summary>Elabora un blocco; restituisce i campioni scritti in <paramref name="output"/>.</summary>
    public int Process(ReadOnlySpan<float> input, Span<float> output)
    {
        if (output.Length < MaxOutputFor(input.Length))
        {
            throw new ArgumentException("Buffer d'uscita troppo piccolo.", nameof(output));
        }

        int written = 0;
        foreach (float sample in input)
        {
            float current = Filter(sample);
            if (!_hasPrevious)
            {
                _previous = current;
                _hasPrevious = true;
                output[written++] = current;
                _position = _step;
                continue;
            }

            // Le uscite cadono nell'intervallo (previous, current]: posizione in [0, 1].
            while (_position <= 1.0)
            {
                float t = (float)_position;
                output[written++] = _previous + ((current - _previous) * t);
                _position += _step;
            }

            _position -= 1.0;
            _previous = current;
        }

        return written;
    }

    public void Reset()
    {
        Array.Clear(_history);
        _historyIndex = 0;
        _hasPrevious = false;
        _previous = 0f;
        _position = 0d;
    }

    private float Filter(float sample)
    {
        if (_taps is null)
        {
            return sample;
        }

        _history[_historyIndex] = sample;
        double acc = 0d;
        int index = _historyIndex;
        for (int i = 0; i < TapCount; i++)
        {
            acc += _taps[i] * _history[index];
            index = index == 0 ? TapCount - 1 : index - 1;
        }

        _historyIndex = (_historyIndex + 1) % TapCount;
        return (float)acc;
    }

    /// <summary>FIR passa-basso; <paramref name="cutoff"/> è la frequenza di taglio normalizzata (0..0,5).</summary>
    internal static float[] CreateLowPass(double cutoff)
    {
        var taps = new double[TapCount];
        int middle = TapCount / 2;
        double sum = 0d;
        for (int i = 0; i < TapCount; i++)
        {
            int n = i - middle;
            double sinc = n == 0 ? 2 * cutoff : Math.Sin(2 * Math.PI * cutoff * n) / (Math.PI * n);
            double window = 0.42 - (0.5 * Math.Cos(2 * Math.PI * i / (TapCount - 1)))
                + (0.08 * Math.Cos(4 * Math.PI * i / (TapCount - 1)));
            taps[i] = sinc * window;
            sum += taps[i];
        }

        var result = new float[TapCount];
        for (int i = 0; i < TapCount; i++)
        {
            result[i] = (float)(taps[i] / sum); // guadagno unitario in continua
        }

        return result;
    }
}
