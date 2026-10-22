// Test della scelta del microfono (Spec §5.7) e dell'avvio con Windows fuori dal pacchetto (Spec §2.4).
// Aggiunto il 22/10/2026 alle 17:47.
using Microsoft.Win32;
using Zzitto.Platform.Audio;
using Zzitto.Platform.Shell;
using Zzitto.Platform.Storage;

namespace Zzitto.Tests;

public class MicrophoneSelectionTests
{
    private static readonly MicrophoneInfo Builtin = new("{0.0.1.00000000}.{aaa}", "Microfono (Realtek)", true);
    private static readonly MicrophoneInfo Headset = new("{0.0.1.00000000}.{bbb}", "Cuffie (Jabra)", false);

    [Fact]
    public void Resolve_UsesChosenMicrophoneWhenConnected()
    {
        Assert.Equal(Headset.Id, MicrophoneSelection.Resolve(Headset.Id, new[] { Builtin, Headset }));
    }

    [Fact]
    public void Resolve_FallsBackToDefaultWhenMissingOrNotChosen()
    {
        Assert.Null(MicrophoneSelection.Resolve(Headset.Id, new[] { Builtin }));
        Assert.Null(MicrophoneSelection.Resolve(null, new[] { Builtin, Headset }));
        Assert.Null(MicrophoneSelection.Resolve("  ", new[] { Builtin }));
    }

    [Fact]
    public void UniqueNames_DefaultFirstAndDuplicatesNumbered()
    {
        var list = MicrophoneSelection.WithUniqueNames(new[]
        {
            new MicrophoneInfo("b", "USB Mic", false),
            new MicrophoneInfo("a", "USB Mic", false),
            new MicrophoneInfo("c", "Interno", true),
            new MicrophoneInfo("d", " ", false),
        });
        Assert.Equal("Interno", list[0].Name);
        Assert.Contains(list, m => m.Name == "USB Mic");
        Assert.Contains(list, m => m.Name == "USB Mic (2)");
        Assert.Contains(list, m => m.Name == "Microfono");
    }

    [Fact]
    public void Settings_EmptyMicrophoneMeansSystemDefault()
    {
        Assert.Null(new ZzittoSettings().MicrophoneId);
        Assert.Null(new ZzittoSettings { MicrophoneId = "" }.Normalize().MicrophoneId);
        Assert.Equal("x", new ZzittoSettings { MicrophoneId = "x" }.Normalize().MicrophoneId);
    }

    [Fact]
    public void ListDevices_NeverThrows()
    {
        // Sulle macchine di build può non esserci alcun microfono: l'elenco può essere vuoto, ma non deve fallire.
        IReadOnlyList<MicrophoneInfo> devices = MicrophoneDevices.List();
        Assert.All(devices, d => Assert.False(string.IsNullOrEmpty(d.Id)));
    }
}

public class StartupRegistrationTests
{
    [Fact]
    public void Command_IsQuotedAndRecognized()
    {
        const string exe = @"C:\dev\Zzitto\_zz\out\zzitto windows\Zzitto.exe";
        string command = StartupRegistration.BuildCommand(exe);
        Assert.Equal("\"" + exe + "\"", command);
        Assert.True(StartupRegistration.PointsTo(command, exe));
        Assert.True(StartupRegistration.PointsTo(command + " --startup", exe));
        Assert.False(StartupRegistration.PointsTo("\"C:\\altro\\Zzitto.exe\"", exe));
        Assert.False(StartupRegistration.PointsTo(null, exe));
    }

    [Fact]
    public void EnableAndDisable_WritesOnlyTheTestKey()
    {
        string keyPath = @"Software\Zzitto.Tests\Run-" + Guid.NewGuid().ToString("N");
        try
        {
            var registration = new StartupRegistration(@"C:\Prova\Zzitto.exe", keyPath);
            Assert.False(registration.IsEnabled);
            Assert.True(registration.SetEnabled(true));
            Assert.True(registration.IsEnabled);
            Assert.False(registration.SetEnabled(false));
            Assert.False(registration.IsEnabled);
        }
        finally
        {
            Registry.CurrentUser.DeleteSubKeyTree(keyPath, throwOnMissingSubKey: false);
        }
    }
}
