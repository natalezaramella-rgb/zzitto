// Icona nell'area di notifica: sempre disponibile (Spec §2.4, §2.6). Colore secondo lo stato (Spec §2.5),
// fumetto per gli avvisi brevi (es. "premi Ctrl+V", Spec §2.6). Aggiunto il 22/10/2026 alle 11:32.
// Va usata solo dal thread dell'interfaccia Win32 (ShellHost).
using System.Runtime.InteropServices;
using Zzitto.Platform.Interop;
using Zzitto.Platform.Overlay;

namespace Zzitto.Platform.Shell;

internal sealed unsafe class TrayIcon : IDisposable
{
    public const uint CallbackMessage = Win32.WM_APP + 10;
    private const uint IconId = 1;

    private readonly IntPtr _owner;
    private IntPtr _icon;
    private string _tooltip = "Zzitto";
    private bool _added;

    public TrayIcon(IntPtr owner)
    {
        _owner = owner;
    }

    public void Add(IconVisual visual, string tooltip)
    {
        _tooltip = tooltip;
        ReplaceIcon(visual);
        NOTIFYICONDATAW data = CreateData(Win32.NIF_MESSAGE | Win32.NIF_ICON | Win32.NIF_TIP | Win32.NIF_SHOWTIP);
        _added = Win32.Shell_NotifyIconW(Win32.NIM_ADD, ref data);
        data.UTimeoutOrVersion = Win32.NOTIFYICON_VERSION_4;
        Win32.Shell_NotifyIconW(Win32.NIM_SETVERSION, ref data);
    }

    /// <summary>Da chiamare quando Explorer viene riavviato (messaggio "TaskbarCreated").</summary>
    public void Readd(IconVisual visual)
    {
        _added = false;
        Add(visual, _tooltip);
    }

    public void Update(IconVisual visual, string tooltip)
    {
        _tooltip = tooltip;
        ReplaceIcon(visual);
        if (!_added)
        {
            return;
        }

        NOTIFYICONDATAW data = CreateData(Win32.NIF_ICON | Win32.NIF_TIP | Win32.NIF_SHOWTIP);
        Win32.Shell_NotifyIconW(Win32.NIM_MODIFY, ref data);
    }

    public void ShowBalloon(string title, string text, bool warning)
    {
        if (!_added)
        {
            return;
        }

        NOTIFYICONDATAW data = CreateData(Win32.NIF_INFO);
        data.SzInfoTitle = Truncate(title, 63);
        data.SzInfo = Truncate(text, 255);
        data.DwInfoFlags = warning ? Win32.NIIF_WARNING : Win32.NIIF_INFO;
        Win32.Shell_NotifyIconW(Win32.NIM_MODIFY, ref data);
    }

    public void Dispose()
    {
        if (_added)
        {
            NOTIFYICONDATAW data = CreateData(0);
            Win32.Shell_NotifyIconW(Win32.NIM_DELETE, ref data);
            _added = false;
        }

        if (_icon != IntPtr.Zero)
        {
            Win32.DestroyIcon(_icon);
            _icon = IntPtr.Zero;
        }
    }

    private NOTIFYICONDATAW CreateData(uint flags) => new()
    {
        CbSize = (uint)Marshal.SizeOf<NOTIFYICONDATAW>(),
        HWnd = _owner,
        UId = IconId,
        UFlags = flags,
        UCallbackMessage = CallbackMessage,
        HIcon = _icon,
        SzTip = Truncate(_tooltip, 127),
        SzInfo = string.Empty,
        SzInfoTitle = string.Empty,
    };

    private void ReplaceIcon(IconVisual visual)
    {
        IntPtr old = _icon;
        _icon = CreateIcon(visual, Math.Max(16, Win32.GetSystemMetrics(Win32.SM_CXSMICON)));
        if (old != IntPtr.Zero)
        {
            Win32.DestroyIcon(old);
        }
    }

    /// <summary>Crea un HICON a 32 bit con alfa dal disegno del microfono.</summary>
    internal static IntPtr CreateIcon(IconVisual visual, int size)
    {
        byte[] pixels = MicIconRenderer.Render(size, visual, 0.5, premultiplied: false);
        var header = new BITMAPINFOHEADER
        {
            BiSize = (uint)sizeof(BITMAPINFOHEADER),
            BiWidth = size,
            BiHeight = -size,
            BiPlanes = 1,
            BiBitCount = 32,
            BiCompression = Win32.BI_RGB,
        };
        IntPtr screenDc = Win32.GetDC(IntPtr.Zero);
        IntPtr color = Win32.CreateDIBSection(screenDc, ref header, Win32.DIB_RGB_COLORS, out IntPtr bits, IntPtr.Zero, 0);
        Win32.ReleaseDC(IntPtr.Zero, screenDc);
        if (color == IntPtr.Zero)
        {
            return IntPtr.Zero;
        }

        IntPtr mask = Win32.CreateBitmap(size, size, 1, 1, IntPtr.Zero);
        try
        {
            Marshal.Copy(pixels, 0, bits, pixels.Length);
            var info = new ICONINFO { FIcon = 1, HbmMask = mask, HbmColor = color };
            return Win32.CreateIconIndirect(ref info);
        }
        finally
        {
            Win32.DeleteObject(color);
            if (mask != IntPtr.Zero)
            {
                Win32.DeleteObject(mask);
            }
        }
    }

    private static string Truncate(string text, int max) => text.Length <= max ? text : text[..max];
}
