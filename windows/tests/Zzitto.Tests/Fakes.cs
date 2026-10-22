// Oggetti finti per i test (Spec §12). Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Core.Licensing;
using Zzitto.Platform.Audio;
using Zzitto.Platform.Dictation;
using Zzitto.Platform.Engine;
using Zzitto.Platform.Input;
using Zzitto.Platform.Storage;

namespace Zzitto.Tests;

/// <summary>Finta API nativa: registra le chiamate e rileva chiamate concorrenti (il motore non le ammette).</summary>
internal sealed class FakeNative : IZzNative
{
    private int _inside;

    public uint Api { get; set; } = 1;

    public ZzStatus CreateStatus { get; set; } = ZzStatus.Ok;

    public ZzStatus NextStatus { get; set; } = ZzStatus.Ok;

    public EngineResult Result { get; set; } = new("ehm ciao", "Ciao.", EngineActions.None, false, false, TimeSpan.Zero, TimeSpan.Zero);

    public TimeSpan DictateDelay { get; set; } = TimeSpan.Zero;

    public bool Loaded { get; private set; }

    public bool Destroyed { get; private set; }

    public int CancelCalls;

    public bool ConcurrentCallDetected { get; private set; }

    public DictationOptions? LastOptions { get; private set; }

    public int LastSampleCount { get; private set; }

    public string? LastSelectedText { get; private set; }

    public IReadOnlyList<string>? LastDictionary { get; private set; }

    public IReadOnlyList<KeyValuePair<string, string>>? LastShortcuts { get; private set; }

    public EngineConfig? LastConfig { get; private set; }

    private readonly ManualResetEventSlim _cancelled = new(false);

    public uint ApiVersion() => Api;

    public string VersionString() => "fake";

    public string StatusString(ZzStatus status) => "stato " + status;

    public ZzStatus CreateEngine(EngineConfig config, out IntPtr engine)
    {
        LastConfig = config;
        engine = CreateStatus == ZzStatus.Ok ? new IntPtr(42) : IntPtr.Zero;
        return CreateStatus;
    }

    public void DestroyEngine(IntPtr engine) => Destroyed = true;

    public ZzStatus Load(IntPtr engine) => Enter(() =>
    {
        Loaded = true;
        return ZzStatus.Ok;
    });

    public ZzStatus Unload(IntPtr engine) => Enter(() =>
    {
        Loaded = false;
        return ZzStatus.Ok;
    });

    public bool IsLoaded(IntPtr engine) => Loaded;

    public ZzStatus SetDictionary(IntPtr engine, IReadOnlyList<string> words)
    {
        LastDictionary = words.ToList();
        return ZzStatus.Ok;
    }

    public ZzStatus SetShortcuts(IntPtr engine, IReadOnlyList<KeyValuePair<string, string>> shortcuts)
    {
        LastShortcuts = shortcuts.ToList();
        return ZzStatus.Ok;
    }

    public ZzStatus Dictate(IntPtr engine, ReadOnlySpan<float> pcm16kMono, DictationOptions options,
        Action<EngineStage>? onStage, out EngineResult? result)
    {
        LastSampleCount = pcm16kMono.Length;
        LastOptions = options;
        return Run(onStage, out result);
    }

    public ZzStatus Rewrite(IntPtr engine, string selectedText, ReadOnlySpan<float> pcm16kMono, DictationOptions options,
        Action<EngineStage>? onStage, out EngineResult? result)
    {
        LastSelectedText = selectedText;
        LastSampleCount = pcm16kMono.Length;
        LastOptions = options;
        return Run(onStage, out result);
    }

    public ZzStatus ProcessText(IntPtr engine, string rawText, DictationOptions options, out EngineResult? result)
    {
        LastOptions = options;
        return Run(null, out result);
    }

    public void Cancel(IntPtr engine)
    {
        Interlocked.Increment(ref CancelCalls);
        _cancelled.Set();
    }

    public IntPtr CreateVad(int silenceMs) => new(7);

    public void DestroyVad(IntPtr vad)
    {
    }

    public void ResetVad(IntPtr vad)
    {
    }

    public bool ProcessVad(IntPtr vad, ReadOnlySpan<float> pcm16kMono) => false;

    public bool VadHasSpeech(IntPtr vad) => false;

    public ZzStatus Sha256File(string path, out string? hexDigest)
    {
        hexDigest = new string('0', 64);
        return ZzStatus.Ok;
    }

    private ZzStatus Run(Action<EngineStage>? onStage, out EngineResult? result)
    {
        result = null;
        EngineResult? produced = null;
        ZzStatus status = Enter(() =>
        {
            _cancelled.Reset();
            onStage?.Invoke(EngineStage.Transcribing);
            if (DictateDelay > TimeSpan.Zero && _cancelled.Wait(DictateDelay))
            {
                return ZzStatus.Cancelled;
            }

            onStage?.Invoke(EngineStage.Correcting);
            onStage?.Invoke(EngineStage.Done);
            if (NextStatus == ZzStatus.Ok)
            {
                produced = Result;
            }

            return NextStatus;
        });
        result = produced;
        return status;
    }

    private ZzStatus Enter(Func<ZzStatus> body)
    {
        if (Interlocked.Increment(ref _inside) > 1)
        {
            ConcurrentCallDetected = true;
        }

        try
        {
            Thread.Sleep(5);
            return body();
        }
        finally
        {
            Interlocked.Decrement(ref _inside);
        }
    }
}

internal sealed class FakeEngine : IDictationEngine
{
    public EngineResult Result { get; set; } = new("ehm prova", "Prova.", EngineActions.None, false, false, TimeSpan.Zero, TimeSpan.Zero);

    public Exception? Error { get; set; }

    public TaskCompletionSource? Gate { get; set; }

    public bool IsLoaded { get; private set; }

    public int DictateCalls { get; private set; }

    public int RewriteCalls { get; private set; }

    public int UnloadCalls { get; private set; }

    public string? LastSelectedText { get; private set; }

    public int LastSampleCount { get; private set; }

    public DictationOptions? LastOptions { get; private set; }

    public Task LoadAsync(CancellationToken cancellationToken = default)
    {
        IsLoaded = true;
        return Task.CompletedTask;
    }

    public Task UnloadAsync(CancellationToken cancellationToken = default)
    {
        IsLoaded = false;
        UnloadCalls++;
        return Task.CompletedTask;
    }

    public Task SetVocabularyAsync(IReadOnlyList<string> dictionary, IReadOnlyList<KeyValuePair<string, string>> shortcuts,
        CancellationToken cancellationToken = default) => Task.CompletedTask;

    public async Task<EngineResult> DictateAsync(float[] pcm16kMono, int sampleCount, DictationOptions options,
        Action<EngineStage>? onStage, CancellationToken cancellationToken = default)
    {
        DictateCalls++;
        return await Produce(sampleCount, options, onStage, cancellationToken);
    }

    public async Task<EngineResult> RewriteAsync(string selectedText, float[] pcm16kMono, int sampleCount, DictationOptions options,
        Action<EngineStage>? onStage, CancellationToken cancellationToken = default)
    {
        RewriteCalls++;
        LastSelectedText = selectedText;
        return await Produce(sampleCount, options, onStage, cancellationToken);
    }

    public Task<EngineResult> ProcessTextAsync(string rawText, DictationOptions options, CancellationToken cancellationToken = default) =>
        Task.FromResult(Result);

    public void Dispose()
    {
    }

    private async Task<EngineResult> Produce(int sampleCount, DictationOptions options, Action<EngineStage>? onStage,
        CancellationToken cancellationToken)
    {
        LastSampleCount = sampleCount;
        LastOptions = options;
        onStage?.Invoke(EngineStage.Transcribing);
        if (Gate is not null)
        {
            await Gate.Task.WaitAsync(cancellationToken);
        }

        cancellationToken.ThrowIfCancellationRequested();
        if (Error is not null)
        {
            throw Error;
        }

        if (!Result.FromShortcut)
        {
            onStage?.Invoke(EngineStage.Correcting);
        }

        onStage?.Invoke(EngineStage.Done);
        return Result;
    }
}

internal sealed class FakeAudio : IAudioSource
{
    private SamplesAvailableHandler? _handler;

    public Exception? StartError { get; set; }

    public bool Running { get; private set; }

    public int StopCalls { get; private set; }

    public void Start(SamplesAvailableHandler onSamples, Action<Exception> onError)
    {
        if (StartError is not null)
        {
            throw StartError;
        }

        _handler = onSamples;
        Running = true;
    }

    public void Stop()
    {
        Running = false;
        StopCalls++;
    }

    /// <summary>Simula un blocco audio dal thread di cattura.</summary>
    public void Push(int count, float value = 0.2f)
    {
        float[] samples = Enumerable.Repeat(value, count).ToArray();
        _handler?.Invoke(samples);
    }
}

internal sealed class FakeVad : IVoiceActivityDetector
{
    public bool EndOnNextBlock { get; set; }

    public bool Disposed { get; private set; }

    public bool HasSpeech => true;

    public bool Process(ReadOnlySpan<float> pcm16kMono) => EndOnNextBlock;

    public void Reset()
    {
    }

    public void Dispose() => Disposed = true;
}

internal sealed class FakeVadFactory : IVoiceActivityDetectorFactory
{
    public FakeVad Vad { get; } = new();

    public int LastSilenceMs { get; private set; }

    public IVoiceActivityDetector Create(int silenceMs)
    {
        LastSilenceMs = silenceMs;
        return Vad;
    }
}

internal sealed class FakeTarget : ITextTarget
{
    public List<string> Calls { get; } = new();

    public TargetContext Context { get; set; } = new() { Window = new IntPtr(1) };

    public InsertionOutcome Outcome { get; set; } = new(InsertionMethod.SendInput);

    public List<string> Inserted { get; } = new();

    public Task<TargetContext> CaptureAsync(bool allowClipboardSelection, CancellationToken cancellationToken)
    {
        Calls.Add("capture");
        return Task.FromResult(Context);
    }

    public Task<InsertionOutcome> InsertAsync(TargetContext context, string text, CancellationToken cancellationToken)
    {
        Calls.Add("insert");
        Inserted.Add(text);
        return Task.FromResult(Outcome);
    }

    public Task<bool> UndoLastInsertAsync(CancellationToken cancellationToken)
    {
        Calls.Add("undo");
        return Task.FromResult(true);
    }

    public Task<bool> DeletePreviousSentenceAsync(TargetContext context, CancellationToken cancellationToken)
    {
        Calls.Add("delete-sentence");
        return Task.FromResult(true);
    }

    public Task<bool> PressSendAsync(TargetContext context, CancellationToken cancellationToken)
    {
        Calls.Add("send");
        return Task.FromResult(true);
    }
}

internal sealed class FakeLicense : ILicenseService
{
    public LicenseState Current { get; set; } = new(LicenseStatus.Trial, 30);

    public event EventHandler<LicenseState>? StateChanged;

    public Task RefreshAsync(CancellationToken cancellationToken = default)
    {
        StateChanged?.Invoke(this, Current);
        return Task.CompletedTask;
    }

    public Task<PurchaseOutcome> PurchaseAsync(nint windowHandle, CancellationToken cancellationToken = default) =>
        Task.FromResult(PurchaseOutcome.Purchased);
}

internal sealed class FakeSettings : ISettingsProvider
{
    public ZzittoSettings Current { get; set; } = new();

    public event EventHandler<ZzittoSettings>? Changed;

    public void Update(Func<ZzittoSettings, ZzittoSettings> change)
    {
        Current = change(Current).Normalize();
        Changed?.Invoke(this, Current);
    }
}

internal sealed class FakeHistory : IHistoryStore
{
    public List<HistoryEntry> Entries { get; } = new();

    public event EventHandler? Changed;

    public IReadOnlyList<HistoryEntry> GetAll() => Entries;

    public void Add(HistoryEntry entry)
    {
        Entries.Add(entry);
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Clear() => Entries.Clear();
}

/// <summary>Protezione finta reversibile (XOR), per testare il formato senza DPAPI.</summary>
internal sealed class FakeProtector : IDataProtector
{
    public byte[] Protect(ReadOnlySpan<byte> plaintext)
    {
        byte[] result = plaintext.ToArray();
        for (int i = 0; i < result.Length; i++)
        {
            result[i] ^= 0x5A;
        }

        return result;
    }

    public byte[] Unprotect(ReadOnlySpan<byte> ciphertext) => Protect(ciphertext);
}

internal static class TestWait
{
    /// <summary>Attende che la condizione diventi vera (massimo 5 s).</summary>
    public static async Task UntilAsync(Func<bool> condition, int timeoutMs = 5000)
    {
        var watch = System.Diagnostics.Stopwatch.StartNew();
        while (!condition())
        {
            if (watch.ElapsedMilliseconds > timeoutMs)
            {
                throw new TimeoutException("Condizione non raggiunta entro il tempo massimo.");
            }

            await Task.Delay(10);
        }
    }
}
