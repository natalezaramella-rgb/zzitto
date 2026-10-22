// Macchina a stati del ciclo di dettatura: l'unica del progetto lato Windows (Spec §0, §5.2).
// Aggiunto il 22/10/2026 alle 11:32.
//
//   Inattivo → Ascolto → Trascrizione → Correzione → Inserimento → Inattivo
//   Errore raggiungibile da ogni stato; annullamento possibile in ogni stato.
//   Da Trascrizione si va direttamente a Inserimento se il motore non corregge
//   (scorciatoia vocale, Spec §5.11).
namespace Zzitto.Platform.Dictation;

public enum DictationState
{
    Idle,
    Listening,
    Transcribing,
    Correcting,
    Inserting,
    Error,
}

public enum DictationTrigger
{
    Start,
    Stop,
    StageCorrecting,
    ResultReady,
    Inserted,
    Cancel,
    Fail,
    Reset,
}

public sealed class DictationStateMachine
{
    private static readonly Dictionary<(DictationState, DictationTrigger), DictationState> Transitions = new()
    {
        [(DictationState.Idle, DictationTrigger.Start)] = DictationState.Listening,
        [(DictationState.Idle, DictationTrigger.Fail)] = DictationState.Error,

        [(DictationState.Listening, DictationTrigger.Stop)] = DictationState.Transcribing,
        [(DictationState.Listening, DictationTrigger.Cancel)] = DictationState.Idle,
        [(DictationState.Listening, DictationTrigger.Fail)] = DictationState.Error,

        [(DictationState.Transcribing, DictationTrigger.StageCorrecting)] = DictationState.Correcting,
        [(DictationState.Transcribing, DictationTrigger.ResultReady)] = DictationState.Inserting,
        [(DictationState.Transcribing, DictationTrigger.Cancel)] = DictationState.Idle,
        [(DictationState.Transcribing, DictationTrigger.Fail)] = DictationState.Error,

        [(DictationState.Correcting, DictationTrigger.ResultReady)] = DictationState.Inserting,
        [(DictationState.Correcting, DictationTrigger.Cancel)] = DictationState.Idle,
        [(DictationState.Correcting, DictationTrigger.Fail)] = DictationState.Error,

        [(DictationState.Inserting, DictationTrigger.Inserted)] = DictationState.Idle,
        [(DictationState.Inserting, DictationTrigger.Cancel)] = DictationState.Idle,
        [(DictationState.Inserting, DictationTrigger.Fail)] = DictationState.Error,

        [(DictationState.Error, DictationTrigger.Reset)] = DictationState.Idle,
        [(DictationState.Error, DictationTrigger.Cancel)] = DictationState.Idle,
        [(DictationState.Error, DictationTrigger.Start)] = DictationState.Listening,
    };

    private readonly object _lock = new();
    private DictationState _state = DictationState.Idle;

    /// <summary>Notificato dopo ogni transizione valida (stato precedente, nuovo stato).</summary>
    public event EventHandler<DictationStateChangedEventArgs>? StateChanged;

    public DictationState State
    {
        get
        {
            lock (_lock)
            {
                return _state;
            }
        }
    }

    /// <summary>true se lo stato è uno di quelli in cui il motore sta lavorando.</summary>
    public bool IsProcessing => State is DictationState.Transcribing or DictationState.Correcting or DictationState.Inserting;

    public bool CanFire(DictationTrigger trigger)
    {
        lock (_lock)
        {
            return Transitions.ContainsKey((_state, trigger));
        }
    }

    /// <summary>Esegue la transizione se ammessa; altrimenti non cambia nulla e restituisce false.</summary>
    public bool Fire(DictationTrigger trigger)
    {
        DictationState previous;
        DictationState next;
        lock (_lock)
        {
            if (!Transitions.TryGetValue((_state, trigger), out next))
            {
                return false;
            }

            previous = _state;
            _state = next;
        }

        StateChanged?.Invoke(this, new DictationStateChangedEventArgs(previous, next, trigger));
        return true;
    }

    /// <summary>Esegue la transizione solo se lo stato corrente è <paramref name="expected"/>.</summary>
    public bool FireIf(DictationState expected, DictationTrigger trigger)
    {
        DictationState next;
        lock (_lock)
        {
            if (_state != expected || !Transitions.TryGetValue((_state, trigger), out next))
            {
                return false;
            }

            _state = next;
        }

        StateChanged?.Invoke(this, new DictationStateChangedEventArgs(expected, next, trigger));
        return true;
    }
}

public sealed class DictationStateChangedEventArgs : EventArgs
{
    public DictationStateChangedEventArgs(DictationState previous, DictationState current, DictationTrigger trigger)
    {
        Previous = previous;
        Current = current;
        Trigger = trigger;
    }

    public DictationState Previous { get; }

    public DictationState Current { get; }

    public DictationTrigger Trigger { get; }
}
