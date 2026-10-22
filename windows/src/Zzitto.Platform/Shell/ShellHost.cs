// App residente: thread Win32 dedicato con area di notifica, icona flottante, scorciatoia globale
// e rilevamento del cambio di focus. Spec §2.4, §2.5, §2.6, §4.3 ("win-tray", "win-overlay").
// Aggiunto il 22/10/2026 alle 11:32.
//
// Tutte le finestre Win32 vivono su un unico thread STA con il proprio ciclo di messaggi, separato
// dal thread di WinUI: l'icona resta reattiva anche mentre la finestra delle impostazioni lavora.
// I metodi pubblici sono thread-safe (accodano il lavoro sul thread della shell);
// gli eventi vengono sollevati sul thread della shell e i gestori devono essere brevi.
using System.Collections.Concurrent;
using Zzitto.Platform.Input;
using Zzitto.Platform.Interop;
using Zzitto.Platform.Overlay;
using Zzitto.Platform.Storage;

namespace Zzitto.Platform.Shell;

public sealed class ShellHost : IDisposable
{
    private const uint DispatchMessage = Win32.WM_APP + 1;
    private const int DictationHotkeyId = 1;
    private const int CancelHotkeyId = 2;

    private static ShellHost? _current; // per la callback degli eventi di focus

    private readonly ConcurrentQueue<Action> _queue = new();
    private readonly ManualResetEventSlim _ready = new(false);
    private Thread? _thread;
    private HostWindow? _host;
    private TrayIcon? _tray;
    private OverlayWindow? _overlay;
    private IntPtr _focusHook;
    private IntPtr _foregroundHook;
    private IntPtr _caretHook;
    private uint _taskbarCreatedMessage;
    private IconVisual _trayVisual = IconVisual.Idle;
    private string _trayTooltip = "Zzitto";
    private Exception? _startError;

    public event EventHandler? HotkeyPressed;

    public event EventHandler? CancelHotkeyPressed;

    public event EventHandler? TrayClicked;

    public event EventHandler<(int X, int Y)>? TrayMenuRequested;

    public event EventHandler? OverlayClicked;

    public event EventHandler<(int X, int Y)>? OverlayMenuRequested;

    public event EventHandler<(int X, int Y)>? OverlayMovedByUser;

    /// <summary>Il focus della tastiera è cambiato (finestra in primo piano, controllo o cursore).</summary>
    public event EventHandler? FocusChanged;

    /// <summary>Finestra nascosta della shell: proprietaria degli appunti e dei menu.</summary>
    public IntPtr HostWindowHandle => _host?.Handle ?? IntPtr.Zero;

    public bool IsShellThread => Thread.CurrentThread == _thread;

    public void Start(IconVisual initialVisual, string tooltip)
    {
        if (_thread is not null)
        {
            return;
        }

        _trayVisual = initialVisual;
        _trayTooltip = tooltip;
        _thread = new Thread(Run) { IsBackground = true, Name = "Zzitto Shell" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
        _ready.Wait(TimeSpan.FromSeconds(10));
        if (_startError is not null)
        {
            throw new InvalidOperationException("Avvio dell'area di notifica non riuscito.", _startError);
        }
    }

    /// <summary>Esegue un'azione sul thread della shell.</summary>
    public void Post(Action action)
    {
        ArgumentNullException.ThrowIfNull(action);
        if (IsShellThread)
        {
            action();
            return;
        }

        _queue.Enqueue(action);
        IntPtr hwnd = HostWindowHandle;
        if (hwnd != IntPtr.Zero)
        {
            Win32.PostMessageW(hwnd, DispatchMessage, IntPtr.Zero, IntPtr.Zero);
        }
    }

    public Task<T> InvokeAsync<T>(Func<T> function)
    {
        ArgumentNullException.ThrowIfNull(function);
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        Post(() =>
        {
            try
            {
                tcs.SetResult(function());
            }
            catch (Exception ex)
            {
                tcs.SetException(ex);
            }
        });
        return tcs.Task;
    }

    // ---- Scorciatoie globali (Spec §2.4) ----

    /// <summary>Registra la scorciatoia della dettatura; false se è già usata da un'altra app.</summary>
    public Task<bool> SetDictationHotkeyAsync(HotkeyBinding binding)
    {
        ArgumentNullException.ThrowIfNull(binding);
        return InvokeAsync(() =>
        {
            IntPtr hwnd = HostWindowHandle;
            Win32.UnregisterHotKey(hwnd, DictationHotkeyId);
            return binding.IsValid
                && Win32.RegisterHotKey(hwnd, DictationHotkeyId, (uint)binding.Modifiers | Win32.MOD_NOREPEAT, binding.VirtualKey);
        });
    }

    /// <summary>Esc annulla la dettatura in corso (Spec §5.2); registrato solo durante la dettatura.</summary>
    public void SetCancelHotkeyEnabled(bool enabled) => Post(() =>
    {
        IntPtr hwnd = HostWindowHandle;
        Win32.UnregisterHotKey(hwnd, CancelHotkeyId);
        if (enabled)
        {
            Win32.RegisterHotKey(hwnd, CancelHotkeyId, Win32.MOD_NOREPEAT, Win32.VK_ESCAPE);
        }
    });

    // ---- Area di notifica ----

    public void SetTrayState(IconVisual visual, string tooltip) => Post(() =>
    {
        _trayVisual = visual;
        _trayTooltip = tooltip;
        _tray?.Update(visual, tooltip);
    });

    public void ShowBalloon(string title, string text, bool warning) => Post(() => _tray?.ShowBalloon(title, text, warning));

    // ---- Icona flottante (Spec §2.4, §2.6) ----

    public void SetOverlayVisual(IconVisual visual) => Post(() => _overlay?.SetVisual(visual));

    public void FlashOverlay(IconVisual notice) => Post(() => _overlay?.Flash(notice));

    public void ShowOverlayAt(int x, int y, double scale) => Post(() =>
    {
        if (_overlay is null || _overlay.IsDragging)
        {
            return;
        }

        _overlay.SetScale(scale);
        _overlay.MoveTo(x, y);
        _overlay.Show();
    });

    public void HideOverlay() => Post(() => _overlay?.Hide());

    /// <summary>Lato dell'icona in pixel fisici per la scala indicata.</summary>
    public static int OverlaySizeFor(double scale) => Math.Max(24, (int)Math.Round(OverlayWindow.BaseSize * scale));

    // ---- Menu ----

    /// <summary>
    /// Mostra un menu contestuale e restituisce l'Id scelto (null se annullato). Il menu deve avere una finestra
    /// in primo piano: al termine si restituisce il primo piano alla finestra dell'utente.
    /// </summary>
    public Task<int?> ShowMenuAsync(IReadOnlyList<MenuItemSpec> items, int x, int y)
    {
        ArgumentNullException.ThrowIfNull(items);
        return InvokeAsync<int?>(() =>
        {
            IntPtr hwnd = HostWindowHandle;
            IntPtr previous = Win32.GetForegroundWindow();
            var menus = new List<IntPtr>();
            try
            {
                IntPtr menu = BuildMenu(items, menus);
                Win32.SetForegroundWindow(hwnd);
                int command = Win32.TrackPopupMenuEx(menu,
                    Win32.TPM_RETURNCMD | Win32.TPM_NONOTIFY | Win32.TPM_RIGHTBUTTON | Win32.TPM_BOTTOMALIGN, x, y, hwnd, IntPtr.Zero);
                Win32.PostMessageW(hwnd, Win32.WM_NULL, IntPtr.Zero, IntPtr.Zero);
                return command > 0 ? command : null;
            }
            finally
            {
                // Solo il menu principale va distrutto: distrugge anche i sottomenu.
                if (menus.Count > 0)
                {
                    Win32.DestroyMenu(menus[0]);
                }

                if (previous != IntPtr.Zero && previous != hwnd)
                {
                    Win32.SetForegroundWindow(previous);
                }
            }
        });
    }

    // ---- Monitor (per il posizionamento, Spec §2.6) ----

    /// <summary>Area di lavoro (senza barra delle applicazioni) del monitor più vicino al rettangolo.</summary>
    public static ScreenRect WorkAreaFor(ScreenRect rect)
    {
        var r = new RECT { Left = rect.Left, Top = rect.Top, Right = rect.Right, Bottom = rect.Bottom };
        IntPtr monitor = Win32.MonitorFromRect(ref r, Win32.MONITOR_DEFAULTTONEAREST);
        var info = new MONITORINFO { CbSize = (uint)System.Runtime.InteropServices.Marshal.SizeOf<MONITORINFO>() };
        if (monitor == IntPtr.Zero || !Win32.GetMonitorInfoW(monitor, ref info))
        {
            return new ScreenRect(0, 0, 1920, 1080);
        }

        return ScreenRect.FromLtrb(info.RcWork.Left, info.RcWork.Top, info.RcWork.Right, info.RcWork.Bottom);
    }

    /// <summary>Scala DPI (1,0 = 96 DPI) del monitor che contiene il rettangolo.</summary>
    public static double ScaleFor(ScreenRect rect)
    {
        var r = new RECT { Left = rect.Left, Top = rect.Top, Right = rect.Right, Bottom = rect.Bottom };
        IntPtr monitor = Win32.MonitorFromRect(ref r, Win32.MONITOR_DEFAULTTONEAREST);
        if (monitor != IntPtr.Zero && Win32.GetDpiForMonitor(monitor, Win32.MDT_EFFECTIVE_DPI, out uint dpiX, out _) >= 0 && dpiX > 0)
        {
            return dpiX / 96.0;
        }

        return 1.0;
    }

    /// <summary>
    /// true se un'app è a schermo intero o in modalità presentazione: l'icona flottante si nasconde
    /// per non disturbare (giochi, presentazioni).
    /// </summary>
    public static bool IsFullScreenAppActive()
    {
        const int QunsBusy = 2;
        const int QunsRunningD3DFullScreen = 3;
        const int QunsPresentationMode = 4;
        return Win32.SHQueryUserNotificationState(out int state) >= 0
            && state is QunsBusy or QunsRunningD3DFullScreen or QunsPresentationMode;
    }

    public void Dispose()
    {
        Thread? thread = _thread;
        if (thread is null)
        {
            return;
        }

        Post(() =>
        {
            Cleanup();
            Win32.PostQuitMessage(0);
        });
        if (thread != Thread.CurrentThread)
        {
            thread.Join(TimeSpan.FromSeconds(3));
        }

        _thread = null;
    }

    private unsafe void Run()
    {
        try
        {
            _host = new HostWindow(this);
            _taskbarCreatedMessage = Win32.RegisterWindowMessageW("TaskbarCreated");
            _tray = new TrayIcon(_host.Handle);
            _tray.Add(_trayVisual, _trayTooltip);
            _overlay = new OverlayWindow();
            _overlay.Clicked += (_, _) => OverlayClicked?.Invoke(this, EventArgs.Empty);
            _overlay.MenuRequested += (_, p) => OverlayMenuRequested?.Invoke(this, p);
            _overlay.UserMoved += (_, p) => OverlayMovedByUser?.Invoke(this, p);

            // Spec §2.6: eventi di focus/primo piano/cursore per riposizionare l'icona.
            _current = this;
            delegate* unmanaged<IntPtr, uint, IntPtr, int, int, uint, uint, void> callback = &WinEventCallback;
            uint flags = Win32.WINEVENT_OUTOFCONTEXT | Win32.WINEVENT_SKIPOWNPROCESS;
            _foregroundHook = Win32.SetWinEventHook(Win32.EVENT_SYSTEM_FOREGROUND, Win32.EVENT_SYSTEM_FOREGROUND, IntPtr.Zero, callback, 0, 0, flags);
            _focusHook = Win32.SetWinEventHook(Win32.EVENT_OBJECT_FOCUS, Win32.EVENT_OBJECT_FOCUS, IntPtr.Zero, callback, 0, 0, flags);
            _caretHook = Win32.SetWinEventHook(Win32.EVENT_OBJECT_LOCATIONCHANGE, Win32.EVENT_OBJECT_LOCATIONCHANGE, IntPtr.Zero, callback, 0, 0, flags);
        }
        catch (Exception ex)
        {
            _startError = ex;
            _ready.Set();
            return;
        }

        _ready.Set();
        DrainQueue();

        while (Win32.GetMessageW(out MSG msg, IntPtr.Zero, 0, 0) > 0)
        {
            Win32.TranslateMessage(ref msg);
            Win32.DispatchMessageW(ref msg);
        }

        Cleanup();
    }

    private void Cleanup()
    {
        if (_foregroundHook != IntPtr.Zero)
        {
            Win32.UnhookWinEvent(_foregroundHook);
            _foregroundHook = IntPtr.Zero;
        }

        if (_focusHook != IntPtr.Zero)
        {
            Win32.UnhookWinEvent(_focusHook);
            _focusHook = IntPtr.Zero;
        }

        if (_caretHook != IntPtr.Zero)
        {
            Win32.UnhookWinEvent(_caretHook);
            _caretHook = IntPtr.Zero;
        }

        _tray?.Dispose();
        _tray = null;
        _overlay?.Dispose();
        _overlay = null;
        if (_host is not null)
        {
            Win32.UnregisterHotKey(_host.Handle, DictationHotkeyId);
            Win32.UnregisterHotKey(_host.Handle, CancelHotkeyId);
            _host.Dispose();
            _host = null;
        }
    }

    private void DrainQueue()
    {
        while (_queue.TryDequeue(out Action? action))
        {
            try
            {
                action();
            }
            catch (Exception)
            {
                // Un errore in un'azione accodata non deve fermare la shell.
            }
        }
    }

    private static IntPtr BuildMenu(IReadOnlyList<MenuItemSpec> items, List<IntPtr> created)
    {
        IntPtr menu = Win32.CreatePopupMenu();
        created.Add(menu);
        foreach (MenuItemSpec item in items)
        {
            if (item.IsSeparator)
            {
                Win32.AppendMenuW(menu, Win32.MF_SEPARATOR, 0, null);
                continue;
            }

            uint flags = Win32.MF_STRING;
            if (item.Checked)
            {
                flags |= Win32.MF_CHECKED;
            }

            if (!item.Enabled)
            {
                flags |= Win32.MF_GRAYED;
            }

            if (item.Children is { Count: > 0 } children)
            {
                IntPtr sub = BuildMenu(children, created);
                Win32.AppendMenuW(menu, flags | Win32.MF_POPUP, (nuint)(nint)sub, item.Text);
            }
            else
            {
                Win32.AppendMenuW(menu, flags, (nuint)item.Id, item.Text);
            }
        }

        return menu;
    }

    [System.Runtime.InteropServices.UnmanagedCallersOnly]
    private static void WinEventCallback(IntPtr hook, uint eventType, IntPtr hwnd, int idObject, int idChild, uint thread, uint time)
    {
        try
        {
            ShellHost? host = _current;
            if (host is null)
            {
                return;
            }

            // Per i cambi di posizione interessa solo il cursore di testo.
            if (eventType == Win32.EVENT_OBJECT_LOCATIONCHANGE && idObject != Win32.OBJID_CARET)
            {
                return;
            }

            host.FocusChanged?.Invoke(host, EventArgs.Empty);
        }
        catch (Exception)
        {
            // Nessuna eccezione attraverso il confine nativo.
        }
    }

    private void OnHostMessage(uint msg, IntPtr wParam, IntPtr lParam)
    {
        if (msg == DispatchMessage)
        {
            DrainQueue();
        }
        else if (msg == Win32.WM_HOTKEY)
        {
            int id = (int)(long)wParam;
            if (id == DictationHotkeyId)
            {
                HotkeyPressed?.Invoke(this, EventArgs.Empty);
            }
            else if (id == CancelHotkeyId)
            {
                CancelHotkeyPressed?.Invoke(this, EventArgs.Empty);
            }
        }
        else if (msg == TrayIcon.CallbackMessage)
        {
            // NOTIFYICON_VERSION_4: LOWORD(lParam) = evento, wParam = coordinate dell'ancora.
            uint trayEvent = (uint)Win32.LoWord(lParam);
            int x = Win32.LoWord(wParam);
            int y = Win32.HiWord(wParam);
            if (trayEvent is Win32.NIN_SELECT or Win32.NIN_KEYSELECT)
            {
                TrayClicked?.Invoke(this, EventArgs.Empty);
            }
            else if (trayEvent == Win32.WM_CONTEXTMENU)
            {
                TrayMenuRequested?.Invoke(this, (x, y));
            }
        }
        else if (msg == _taskbarCreatedMessage && _taskbarCreatedMessage != 0)
        {
            _tray?.Readd(_trayVisual);
        }
    }

    /// <summary>Finestra nascosta che riceve i messaggi della shell.</summary>
    private sealed class HostWindow : NativeWindow
    {
        private readonly ShellHost _owner;

        public HostWindow(ShellHost owner)
        {
            _owner = owner;
            // Finestra di primo livello mai mostrata (non solo-messaggi: serve a TrackPopupMenu e al TaskbarCreated).
            CreateHandle("ZzittoShellHost", Win32.WS_EX_TOOLWINDOW, Win32.WS_POPUP, 0, 0, 0, 0, IntPtr.Zero);
        }

        protected override IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
        {
            if (msg == DispatchMessage || msg == Win32.WM_HOTKEY || msg == TrayIcon.CallbackMessage
                || (msg == _owner._taskbarCreatedMessage && msg != 0))
            {
                _owner.OnHostMessage(msg, wParam, lParam);
                return IntPtr.Zero;
            }

            return DefaultProc(hwnd, msg, wParam, lParam);
        }
    }
}
