// Informazioni sulla finestra e sul processo di destinazione. Spec §2.6: l'app non chiede privilegi di
// amministratore; con finestre elevate (UIPI blocca SendInput e UI Automation) vale il fallback degli appunti.
// Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Input;

internal static class ProcessInfo
{
    /// <summary>
    /// true se il processo è elevato o non interrogabile dal nostro livello di integrità
    /// (in entrambi i casi l'input simulato non arriverebbe).
    /// </summary>
    public static bool IsElevatedOrInaccessible(uint processId)
    {
        if (processId == 0)
        {
            return true;
        }

        if (processId == (uint)Environment.ProcessId)
        {
            return false;
        }

        IntPtr process = Win32.OpenProcess(Win32.PROCESS_QUERY_LIMITED_INFORMATION, false, processId);
        if (process == IntPtr.Zero)
        {
            return true;
        }

        try
        {
            if (!Win32.OpenProcessToken(process, Win32.TOKEN_QUERY, out IntPtr token))
            {
                return true;
            }

            try
            {
                if (!Win32.GetTokenInformation(token, Win32.TokenElevation, out int elevated, sizeof(int), out _))
                {
                    return true;
                }

                // Se il processo è elevato e noi no, UIPI blocca l'input.
                return elevated != 0 && !IsCurrentProcessElevated.Value;
            }
            finally
            {
                Win32.CloseHandle(token);
            }
        }
        finally
        {
            Win32.CloseHandle(process);
        }
    }

    private static readonly Lazy<bool> IsCurrentProcessElevated = new(() =>
    {
        using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
        return new System.Security.Principal.WindowsPrincipal(identity)
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
    });

    public static (IntPtr Window, uint ThreadId, uint ProcessId) Foreground()
    {
        IntPtr hwnd = Win32.GetForegroundWindow();
        if (hwnd == IntPtr.Zero)
        {
            return (IntPtr.Zero, 0, 0);
        }

        uint threadId = Win32.GetWindowThreadProcessId(hwnd, out uint processId);
        return (hwnd, threadId, processId);
    }
}
