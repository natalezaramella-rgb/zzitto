// Test della macchina a stati del ciclo di dettatura (Spec §5.2, §12). Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Dictation;

namespace Zzitto.Tests;

public class DictationStateMachineTests
{
    [Fact]
    public void FullCycle_GoesThroughAllStates()
    {
        var sm = new DictationStateMachine();
        var seen = new List<DictationState>();
        sm.StateChanged += (_, e) => seen.Add(e.Current);

        Assert.True(sm.Fire(DictationTrigger.Start));
        Assert.True(sm.Fire(DictationTrigger.Stop));
        Assert.True(sm.Fire(DictationTrigger.StageCorrecting));
        Assert.True(sm.Fire(DictationTrigger.ResultReady));
        Assert.True(sm.Fire(DictationTrigger.Inserted));

        Assert.Equal(
            new[] { DictationState.Listening, DictationState.Transcribing, DictationState.Correcting, DictationState.Inserting, DictationState.Idle },
            seen);
    }

    [Fact]
    public void Shortcut_SkipsCorrection()
    {
        // Spec §5.11: la scorciatoia vocale non passa dalla correzione.
        var sm = new DictationStateMachine();
        sm.Fire(DictationTrigger.Start);
        sm.Fire(DictationTrigger.Stop);
        Assert.True(sm.Fire(DictationTrigger.ResultReady));
        Assert.Equal(DictationState.Inserting, sm.State);
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    public void Cancel_FromAnyActiveState_ReturnsToIdle(int steps)
    {
        var sm = Advance(steps);
        Assert.True(sm.Fire(DictationTrigger.Cancel));
        Assert.Equal(DictationState.Idle, sm.State);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(4)]
    public void Fail_FromAnyState_GoesToError(int steps)
    {
        var sm = Advance(steps);
        Assert.True(sm.Fire(DictationTrigger.Fail));
        Assert.Equal(DictationState.Error, sm.State);
        Assert.True(sm.Fire(DictationTrigger.Reset));
        Assert.Equal(DictationState.Idle, sm.State);
    }

    [Fact]
    public void InvalidTrigger_IsIgnored()
    {
        var sm = new DictationStateMachine();
        int events = 0;
        sm.StateChanged += (_, _) => events++;

        Assert.False(sm.Fire(DictationTrigger.Stop));
        Assert.False(sm.Fire(DictationTrigger.Inserted));
        Assert.Equal(DictationState.Idle, sm.State);
        Assert.Equal(0, events);
    }

    [Fact]
    public void FireIf_RequiresExpectedState()
    {
        var sm = new DictationStateMachine();
        Assert.False(sm.FireIf(DictationState.Listening, DictationTrigger.Stop));
        sm.Fire(DictationTrigger.Start);
        Assert.True(sm.FireIf(DictationState.Listening, DictationTrigger.Stop));
        Assert.Equal(DictationState.Transcribing, sm.State);
        Assert.True(sm.IsProcessing);
    }

    [Fact]
    public void Error_AllowsNewStart()
    {
        var sm = new DictationStateMachine();
        sm.Fire(DictationTrigger.Fail);
        Assert.True(sm.Fire(DictationTrigger.Start));
        Assert.Equal(DictationState.Listening, sm.State);
    }

    private static DictationStateMachine Advance(int steps)
    {
        var sm = new DictationStateMachine();
        DictationTrigger[] path = { DictationTrigger.Start, DictationTrigger.Stop, DictationTrigger.StageCorrecting, DictationTrigger.ResultReady };
        for (int i = 0; i < steps; i++)
        {
            Assert.True(sm.Fire(path[i]));
        }

        return sm;
    }
}
