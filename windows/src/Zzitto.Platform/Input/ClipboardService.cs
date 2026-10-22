// Appunti di Windows: fallback finale dell'inserimento (Spec §2.6) e copia temporanea per leggere
// la selezione con ripristino del contenuto precedente (Spec §5.10). Aggiunto il 22/10/2026 alle 11:32.
//
// Spec §10: i testi messi negli appunti da Zzitto sono esclusi dalla cronologia degli appunti di Windows
// e dalla sincronizzazione cloud (formati "CanIncludeInClipboardHistory" e "CanUploadToCloudClipboard" = 0).
using System.Runtime.InteropServices;
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Input;

public interface IClipboardService
{
    bool SetText(string text);

    string? GetText();

    uint SequenceNumber { get; }

    ClipboardSnapshot Snapshot();

    void Restore(ClipboardSnapshot snapshot);
}

/// <summary>Copia in memoria dei formati presenti negli appunti (solo formati basati su HGLOBAL).</summary>
public sealed class ClipboardSnapshot
{
    internal ClipboardSnapshot(IReadOnlyList<(uint Format, byte[] Data)> items)
    {
        Items = items;
    }

    internal IReadOnlyList<(uint Format, byte[] Data)> Items { get; }

    public bool IsEmpty => Items.Count == 0;

    /// <summary>Azzera le copie in memoria (Spec §10).</summary>
    public void Wipe()
    {
        foreach ((uint _, byte[] data) in Items)
        {
            Array.Clear(data);
        }
    }
}

public sealed unsafe class ClipboardService : IClipboardService
{
    private const long MaxSnapshotBytes = 64L * 1024 * 1024;

    // Formati con handle GDI o speciali: non sono blocchi di memoria copiabili.
    private static readonly HashSet<uint> NonGlobalFormats = new()
    {
        2,    // CF_BITMAP
        3,    // CF_METAFILEPICT
        9,    // CF_PALETTE
        14,   // CF_ENHMETAFILE
        0x80, // CF_OWNERDISPLAY
        0x82, // CF_DSPBITMAP
        0x83, // CF_DSPMETAFILEPICT
        0x8E, // CF_DSPENHMETAFILE
    };

    private readonly Func<IntPtr> _ownerWindow;
    private readonly uint _historyFormat;
    private readonly uint _cloudFormat;

    /// <param name="ownerWindow">Finestra proprietaria degli appunti (serve a SetClipboardData).</param>
    public ClipboardService(Func<IntPtr> ownerWindow)
    {
        _ownerWindow = ownerWindow ?? throw new ArgumentNullException(nameof(ownerWindow));
        _historyFormat = Win32.RegisterClipboardFormatW("CanIncludeInClipboardHistory");
        _cloudFormat = Win32.RegisterClipboardFormatW("CanUploadToCloudClipboard");
    }

    public uint SequenceNumber => Win32.GetClipboardSequenceNumber();

    public bool SetText(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        if (!Open())
        {
            return false;
        }

        try
        {
            if (!Win32.EmptyClipboard())
            {
                return false;
            }

            byte[] bytes = new byte[(text.Length + 1) * 2];
            MemoryMarshal.AsBytes(text.AsSpan()).CopyTo(bytes);
            bool ok = SetData(Win32.CF_UNICODETEXT, bytes);
            Array.Clear(bytes);

            // Esclusione da cronologia e cloud (DWORD 0).
            byte[] zero = new byte[4];
            if (_historyFormat != 0)
            {
                SetData(_historyFormat, zero);
            }

            if (_cloudFormat != 0)
            {
                SetData(_cloudFormat, zero);
            }

            return ok;
        }
        finally
        {
            Win32.CloseClipboard();
        }
    }

    public string? GetText()
    {
        if (!Open())
        {
            return null;
        }

        try
        {
            IntPtr handle = Win32.GetClipboardData(Win32.CF_UNICODETEXT);
            if (handle == IntPtr.Zero)
            {
                return null;
            }

            IntPtr pointer = Win32.GlobalLock(handle);
            if (pointer == IntPtr.Zero)
            {
                return null;
            }

            try
            {
                int maxChars = (int)Math.Min((long)(Win32.GlobalSize(handle) / 2), int.MaxValue);
                var span = new ReadOnlySpan<char>((void*)pointer, maxChars);
                int end = span.IndexOf('\0');
                return new string(end >= 0 ? span[..end] : span);
            }
            finally
            {
                Win32.GlobalUnlock(handle);
            }
        }
        finally
        {
            Win32.CloseClipboard();
        }
    }

    public ClipboardSnapshot Snapshot()
    {
        var items = new List<(uint, byte[])>();
        if (!Open())
        {
            return new ClipboardSnapshot(items);
        }

        try
        {
            long total = 0;
            uint format = 0;
            while ((format = Win32.EnumClipboardFormats(format)) != 0)
            {
                if (NonGlobalFormats.Contains(format))
                {
                    continue;
                }

                IntPtr handle = Win32.GetClipboardData(format);
                if (handle == IntPtr.Zero)
                {
                    continue;
                }

                long size = (long)Win32.GlobalSize(handle);
                if (size <= 0 || total + size > MaxSnapshotBytes)
                {
                    continue;
                }

                IntPtr pointer = Win32.GlobalLock(handle);
                if (pointer == IntPtr.Zero)
                {
                    continue;
                }

                try
                {
                    byte[] data = new byte[size];
                    Marshal.Copy(pointer, data, 0, data.Length);
                    items.Add((format, data));
                    total += size;
                }
                finally
                {
                    Win32.GlobalUnlock(handle);
                }
            }
        }
        finally
        {
            Win32.CloseClipboard();
        }

        return new ClipboardSnapshot(items);
    }

    public void Restore(ClipboardSnapshot snapshot)
    {
        ArgumentNullException.ThrowIfNull(snapshot);
        if (!Open())
        {
            return;
        }

        try
        {
            Win32.EmptyClipboard();
            foreach ((uint format, byte[] data) in snapshot.Items)
            {
                SetData(format, data);
            }
        }
        finally
        {
            Win32.CloseClipboard();
        }
    }

    private bool Open()
    {
        // Un'altra app può tenere aperti gli appunti per qualche millisecondo.
        for (int attempt = 0; attempt < 10; attempt++)
        {
            if (Win32.OpenClipboard(_ownerWindow()))
            {
                return true;
            }

            Thread.Sleep(20);
        }

        return false;
    }

    private static bool SetData(uint format, byte[] data)
    {
        IntPtr memory = Win32.GlobalAlloc(Win32.GMEM_MOVEABLE, (nuint)Math.Max(data.Length, 1));
        if (memory == IntPtr.Zero)
        {
            return false;
        }

        IntPtr pointer = Win32.GlobalLock(memory);
        if (pointer == IntPtr.Zero)
        {
            Win32.GlobalFree(memory);
            return false;
        }

        Marshal.Copy(data, 0, pointer, data.Length);
        Win32.GlobalUnlock(memory);
        if (Win32.SetClipboardData(format, memory) == IntPtr.Zero)
        {
            Win32.GlobalFree(memory); // in caso di successo la memoria appartiene al sistema
            return false;
        }

        return true;
    }
}
