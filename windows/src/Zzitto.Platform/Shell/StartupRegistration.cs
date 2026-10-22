// Avvio automatico con Windows per l'eseguibile non pacchettizzato (Spec §2.4): chiave "Run" dell'utente corrente.
// Il pacchetto MSIX usa invece StartupTask (Zzitto.App/Services/StartupTaskService.cs), che fuori dal pacchetto non
// funziona: per questo nell'exe di test l'interruttore era disattivato. Aggiunto il 22/10/2026 alle 17:46.
using Microsoft.Win32;

namespace Zzitto.Platform.Shell;

public sealed class StartupRegistration
{
    public const string DefaultKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    public const string ValueName = "Zzitto";

    private readonly string _keyPath;
    private readonly string _exePath;

    /// <param name="exePath">Percorso completo di Zzitto.exe.</param>
    /// <param name="keyPath">Sottochiave di HKEY_CURRENT_USER (i test ne usano una propria).</param>
    public StartupRegistration(string exePath, string keyPath = DefaultKeyPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(exePath);
        ArgumentException.ThrowIfNullOrWhiteSpace(keyPath);
        _exePath = exePath;
        _keyPath = keyPath;
    }

    /// <summary>Riga di comando registrata: percorso tra virgolette (può contenere spazi).</summary>
    public static string BuildCommand(string exePath) => "\"" + exePath.Trim('"') + "\"";

    /// <summary>Vero se il valore registrato avvia proprio questo eseguibile (non una copia spostata altrove).</summary>
    public static bool PointsTo(string? command, string exePath)
    {
        if (string.IsNullOrWhiteSpace(command))
        {
            return false;
        }

        string trimmed = command.Trim();
        string path = trimmed.StartsWith('"')
            ? trimmed[1..Math.Max(1, trimmed.IndexOf('"', 1))]
            : trimmed.Split(' ', 2)[0];
        return string.Equals(path, exePath.Trim('"'), StringComparison.OrdinalIgnoreCase);
    }

    public bool IsEnabled
    {
        get
        {
            using RegistryKey? key = Registry.CurrentUser.OpenSubKey(_keyPath);
            return PointsTo(key?.GetValue(ValueName) as string, _exePath);
        }
    }

    /// <summary>Attiva o disattiva l'avvio con Windows; restituisce lo stato risultante.</summary>
    public bool SetEnabled(bool enabled)
    {
        try
        {
            using RegistryKey key = Registry.CurrentUser.CreateSubKey(_keyPath, writable: true);
            if (enabled)
            {
                key.SetValue(ValueName, BuildCommand(_exePath), RegistryValueKind.String);
            }
            else
            {
                key.DeleteValue(ValueName, throwOnMissingValue: false);
            }
        }
        catch (Exception ex) when (ex is UnauthorizedAccessException or System.Security.SecurityException or IOException)
        {
            // Criteri aziendali che bloccano la chiave: si riporta lo stato reale.
        }

        return IsEnabled;
    }
}
