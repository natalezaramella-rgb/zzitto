// Icona flottante del microfono: finestra piccola senza bordi, sempre in primo piano e che non prende mai
// il focus (il campo di destinazione resta attivo). Spec §2.4, §2.5, §2.6. Aggiunto il 22/10/2026 alle 11:32.
//
//  - clic: avvia/ferma la dettatura; trascinamento: sposta e ricorda la posizione (Spec §2.6);
//  - pressione lunga o clic destro: menu (annulla, modalità, traduzione, impostazioni), Spec §5.1, §5.12;
//  - stato solo con colore e animazione (Spec §2.5), disegnata con UpdateLayeredWindow (alfa per pixel).
// Va usata solo dal thread dell'interfaccia Win32 (ShellHost).
using Zzitto.Platform.Interop;
using Zzitto.Platform.Shell;

namespace Zzitto.Platform.Overlay;

internal sealed unsafe class OverlayWindow : NativeWindow
{
    public const int BaseSize = 40; // lato in pixel a 96 DPI

    private const nuint AnimationTimer = 1;
    private const nuint LongPressTimer = 2;
    private const nuint NoticeTimer = 3;
    private const int DragThreshold = 4;

    private IconVisual _visual = IconVisual.Idle;
    private IconVisual? _notice;
    private double _phase;
    private int _size = BaseSize;
    private int _x;
    private int _y;
    private bool _visible;

    private bool _pressed;
    private bool _dragging;
    private bool _longPressFired;
    private POINT _pressCursor;
    private int _pressWindowX;
    private int _pressWindowY;

    public OverlayWindow()
    {
        CreateHandle("ZzittoOverlay",
            Win32.WS_EX_LAYERED | Win32.WS_EX_TOPMOST | Win32.WS_EX_TOOLWINDOW | Win32.WS_EX_NOACTIVATE,
            Win32.WS_POPUP, 0, 0, _size, _size, IntPtr.Zero);
    }

    /// <summary>Clic semplice sull'icona.</summary>
    public event EventHandler? Clicked;

    /// <summary>Pressione lunga o clic destro: coordinate schermo per il menu.</summary>
    public event EventHandler<(int X, int Y)>? MenuRequested;

    /// <summary>L'utente ha trascinato l'icona in una nuova posizione (pixel fisici).</summary>
    public event EventHandler<(int X, int Y)>? UserMoved;

    public bool IsVisible => _visible;

    public int Size => _size;

    public (int X, int Y) Position => (_x, _y);

    public bool IsDragging => _dragging;

    public void SetVisual(IconVisual visual)
    {
        _visual = visual;
        UpdateTimer();
        Render();
    }

    /// <summary>Lampo di conferma per circa 700 ms (Spec §5.9: conferma visiva dei comandi).</summary>
    public void Flash(IconVisual notice)
    {
        _notice = notice;
        Win32.SetTimer(Handle, NoticeTimer, 700, IntPtr.Zero);
        Render();
    }

    public void SetScale(double scale)
    {
        int size = Math.Max(24, (int)Math.Round(BaseSize * scale));
        if (size != _size)
        {
            _size = size;
            Render();
        }
    }

    public void MoveTo(int x, int y)
    {
        _x = x;
        _y = y;
        if (_visible)
        {
            Render();
        }
    }

    public void Show()
    {
        if (_visible)
        {
            return;
        }

        _visible = true;
        Render();
        Win32.ShowWindow(Handle, Win32.SW_SHOWNOACTIVATE);
        Win32.SetWindowPos(Handle, Win32.HWND_TOPMOST, 0, 0, 0, 0,
            Win32.SWP_NOMOVE | Win32.SWP_NOSIZE | Win32.SWP_NOACTIVATE | Win32.SWP_SHOWWINDOW);
        UpdateTimer();
    }

    public void Hide()
    {
        if (!_visible)
        {
            return;
        }

        _visible = false;
        Win32.ShowWindow(Handle, Win32.SW_HIDE);
        UpdateTimer();
    }

    protected override IntPtr WndProc(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam)
    {
        switch (msg)
        {
            case Win32.WM_MOUSEACTIVATE:
                // Spec §2.4: il clic sull'icona non toglie il focus al campo di testo.
                return new IntPtr(Win32.MA_NOACTIVATE);

            case Win32.WM_LBUTTONDOWN:
                _pressed = true;
                _dragging = false;
                _longPressFired = false;
                Win32.GetCursorPos(out _pressCursor);
                _pressWindowX = _x;
                _pressWindowY = _y;
                Win32.SetCapture(hwnd);
                Win32.SetTimer(hwnd, LongPressTimer, 600, IntPtr.Zero);
                return IntPtr.Zero;

            case Win32.WM_MOUSEMOVE:
                if (_pressed && Win32.GetCursorPos(out POINT cursor))
                {
                    int dx = cursor.X - _pressCursor.X;
                    int dy = cursor.Y - _pressCursor.Y;
                    if (!_dragging && (Math.Abs(dx) > DragThreshold || Math.Abs(dy) > DragThreshold))
                    {
                        _dragging = true;
                        Win32.KillTimer(hwnd, LongPressTimer);
                    }

                    if (_dragging)
                    {
                        MoveTo(_pressWindowX + dx, _pressWindowY + dy);
                    }
                }

                return IntPtr.Zero;

            case Win32.WM_LBUTTONUP:
                {
                    bool wasPressed = _pressed;
                    bool wasDragging = _dragging;
                    _pressed = false;
                    _dragging = false;
                    Win32.KillTimer(hwnd, LongPressTimer);
                    Win32.ReleaseCapture();
                    if (wasDragging)
                    {
                        UserMoved?.Invoke(this, (_x, _y));
                    }
                    else if (wasPressed && !_longPressFired)
                    {
                        Clicked?.Invoke(this, EventArgs.Empty);
                    }

                    return IntPtr.Zero;
                }

            case Win32.WM_CAPTURECHANGED:
                if (_pressed && lParam != hwnd)
                {
                    _pressed = false;
                    _dragging = false;
                    Win32.KillTimer(hwnd, LongPressTimer);
                }

                return IntPtr.Zero;

            case Win32.WM_RBUTTONUP:
                RaiseMenu();
                return IntPtr.Zero;

            case Win32.WM_TIMER:
                if (wParam == (IntPtr)AnimationTimer)
                {
                    _phase = (_phase + 0.04) % 1.0;
                    Render();
                }
                else if (wParam == (IntPtr)LongPressTimer)
                {
                    Win32.KillTimer(hwnd, LongPressTimer);
                    if (_pressed && !_dragging)
                    {
                        _longPressFired = true;
                        _pressed = false;
                        Win32.ReleaseCapture();
                        RaiseMenu();
                    }
                }
                else if (wParam == (IntPtr)NoticeTimer)
                {
                    Win32.KillTimer(hwnd, NoticeTimer);
                    _notice = null;
                    Render();
                }

                return IntPtr.Zero;
        }

        return DefaultProc(hwnd, msg, wParam, lParam);
    }

    private void RaiseMenu()
    {
        MenuRequested?.Invoke(this, (_x + (_size / 2), _y + (_size / 2)));
    }

    private void UpdateTimer()
    {
        if (_visible && MicIconRenderer.IsAnimated(_visual))
        {
            Win32.SetTimer(Handle, AnimationTimer, 40, IntPtr.Zero);
        }
        else
        {
            Win32.KillTimer(Handle, AnimationTimer);
            _phase = 0;
        }
    }

    private void Render()
    {
        if (Handle == IntPtr.Zero)
        {
            return;
        }

        int size = _size;
        byte[] pixels = MicIconRenderer.Render(size, _notice ?? _visual, _phase, premultiplied: true);

        IntPtr screenDc = Win32.GetDC(IntPtr.Zero);
        IntPtr memoryDc = Win32.CreateCompatibleDC(screenDc);
        var header = new BITMAPINFOHEADER
        {
            BiSize = (uint)sizeof(BITMAPINFOHEADER),
            BiWidth = size,
            BiHeight = -size, // dall'alto in basso
            BiPlanes = 1,
            BiBitCount = 32,
            BiCompression = Win32.BI_RGB,
        };
        IntPtr bitmap = Win32.CreateDIBSection(screenDc, ref header, Win32.DIB_RGB_COLORS, out IntPtr bits, IntPtr.Zero, 0);
        try
        {
            if (bitmap == IntPtr.Zero || bits == IntPtr.Zero)
            {
                return;
            }

            System.Runtime.InteropServices.Marshal.Copy(pixels, 0, bits, pixels.Length);
            IntPtr previous = Win32.SelectObject(memoryDc, bitmap);
            var destination = new POINT { X = _x, Y = _y };
            var sizeStruct = new SIZE { Cx = size, Cy = size };
            var source = new POINT { X = 0, Y = 0 };
            var blend = new BLENDFUNCTION
            {
                BlendOp = Win32.AC_SRC_OVER,
                BlendFlags = 0,
                SourceConstantAlpha = 255,
                AlphaFormat = Win32.AC_SRC_ALPHA,
            };
            Win32.UpdateLayeredWindow(Handle, screenDc, ref destination, ref sizeStruct, memoryDc, ref source, 0, ref blend,
                Win32.ULW_ALPHA);
            Win32.SelectObject(memoryDc, previous);
        }
        finally
        {
            if (bitmap != IntPtr.Zero)
            {
                Win32.DeleteObject(bitmap);
            }

            Win32.DeleteDC(memoryDc);
            Win32.ReleaseDC(IntPtr.Zero, screenDc);
        }
    }

    public override void Dispose()
    {
        if (Handle != IntPtr.Zero)
        {
            Win32.KillTimer(Handle, AnimationTimer);
            Win32.KillTimer(Handle, LongPressTimer);
            Win32.KillTimer(Handle, NoticeTimer);
        }

        base.Dispose();
    }
}
