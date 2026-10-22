// Finestra Win32 minimale con procedura gestita, per l'icona flottante e la finestra nascosta dell'area di
// notifica. Spec §2.4, §2.5. Aggiunto il 22/10/2026 alle 11:32.
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Shell;

internal abstract unsafe class NativeWindow : IDisposable
{
    private static readonly ConcurrentDictionary<IntPtr, NativeWindow> Windows = new();
    private static readonly ConcurrentDictionary<string, bool> RegisteredClasses = new();

    [ThreadStatic]
    private static NativeWindow? _creating;

    public IntPtr Handle { get; private set; }

    protected void CreateHandle(string className, uint exStyle, uint style, int x, int y, int width, int height, IntPtr parent)
    {
        EnsureClassRegistered(className);
        _creating = this;
        try
        {
            IntPtr hwnd = Win32.CreateWindowExW(exStyle, className, "Zzitto", style, x, y, width, height, parent,
                IntPtr.Zero, Win32.GetModuleHandleW(null), IntPtr.Zero);
            if (hwnd == IntPtr.Zero)
            {
                throw new InvalidOperationException("CreateWindowEx non riuscita (errore " + Marshal.GetLastPInvokeError() + ").");
            }

            Handle = hwnd;
            Windows[hwnd] = this;
        }
        finally
        {
            _creating = null;
        }
    }

    protected abstract IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam);

    protected IntPtr DefaultProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam) =>
        Win32.DefWindowProcW(hwnd, msg, wParam, lParam);

    public virtual void Dispose()
    {
        IntPtr hwnd = Handle;
        if (hwnd != IntPtr.Zero)
        {
            Handle = IntPtr.Zero;
            Win32.DestroyWindow(hwnd);
            Windows.TryRemove(hwnd, out _);
        }
    }

    private static void EnsureClassRegistered(string className)
    {
        if (RegisteredClasses.ContainsKey(className))
        {
            return;
        }

        fixed (char* name = className)
        {
            var wc = new WNDCLASSEXW
            {
                CbSize = (uint)sizeof(WNDCLASSEXW),
                LpfnWndProc = (IntPtr)(void*)(delegate* unmanaged<IntPtr, uint, IntPtr, IntPtr, IntPtr>)&StaticWndProc,
                HInstance = Win32.GetModuleHandleW(null),
                HCursor = Win32.LoadCursorW(IntPtr.Zero, new IntPtr(Win32.IDC_ARROW)),
                LpszClassName = name,
            };
            if (Win32.RegisterClassExW(ref wc) == 0)
            {
                int error = Marshal.GetLastPInvokeError();
                const int ErrorClassAlreadyExists = 1410;
                if (error != ErrorClassAlreadyExists)
                {
                    throw new InvalidOperationException("RegisterClassEx non riuscita (errore " + error + ").");
                }
            }
        }

        RegisteredClasses[className] = true;
    }

    [UnmanagedCallersOnly]
    private static IntPtr StaticWndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        try
        {
            if (!Windows.TryGetValue(hwnd, out NativeWindow? window))
            {
                window = _creating;
                if (window is not null)
                {
                    // Messaggi inviati durante CreateWindowEx (WM_NCCREATE, WM_CREATE...).
                    window.Handle = hwnd;
                    Windows[hwnd] = window;
                }
            }

            if (window is not null)
            {
                IntPtr result = window.WndProc(hwnd, msg, wParam, lParam);
                if (msg == Win32.WM_DESTROY)
                {
                    Windows.TryRemove(hwnd, out _);
                }

                return result;
            }
        }
        catch (Exception)
        {
            // Nessuna eccezione deve attraversare il confine nativo.
        }

        return Win32.DefWindowProcW(hwnd, msg, wParam, lParam);
    }
}
