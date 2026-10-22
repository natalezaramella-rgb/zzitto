// Posizione dell'icona flottante vicino al campo attivo, con fallback sull'ultima posizione scelta.
// Spec §2.4, §2.6. Logica pura, testata (Spec §12). Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Input;

namespace Zzitto.Platform.Overlay;

public enum PlacementSource
{
    Caret,
    Element,
    UserPosition,
    Default,
}

public readonly record struct OverlayPosition(int X, int Y, PlacementSource Source);

public static class OverlayPlacement
{
    /// <summary>Distanza tra il cursore (o il campo) e l'icona, in pixel a 96 DPI.</summary>
    public const int Gap = 8;

    /// <summary>
    /// Calcola la posizione (angolo in alto a sinistra) dell'icona di lato <paramref name="size"/>.
    /// Priorità: cursore di testo → controllo → ultima posizione scelta dall'utente → angolo in basso a destra.
    /// </summary>
    /// <param name="workAreaFor">Area di lavoro del monitor che contiene un rettangolo.</param>
    public static OverlayPosition Compute(
        ScreenRect? caret,
        ScreenRect? element,
        (int X, int Y)? userPosition,
        int size,
        double scale,
        Func<ScreenRect, ScreenRect> workAreaFor)
    {
        ArgumentNullException.ThrowIfNull(workAreaFor);
        if (size <= 0)
        {
            throw new ArgumentOutOfRangeException(nameof(size));
        }

        int gap = (int)Math.Round(Gap * Math.Max(scale, 0.5));

        if (caret is { IsEmpty: false } c)
        {
            // A destra del cursore, centrata in verticale sulla riga.
            int x = c.Right + gap;
            int y = c.Top + (c.Height / 2) - (size / 2);
            ScreenRect work = workAreaFor(c);
            if (x + size > work.Right)
            {
                x = c.Left - gap - size; // non c'è spazio a destra: a sinistra del cursore
            }

            return Clamp(new OverlayPosition(x, y, PlacementSource.Caret), size, work);
        }

        if (element is { IsEmpty: false } e)
        {
            ScreenRect work = workAreaFor(e);
            int x;
            int y;
            if (e.Height >= size + (2 * gap) && e.Width >= (size * 3))
            {
                // Dentro il campo, nell'angolo in basso a destra (non copre l'inizio del testo).
                x = e.Right - size - gap;
                y = e.Bottom - size - gap;
            }
            else
            {
                // Campo basso (una riga): subito a destra, centrata in verticale.
                x = e.Right + gap;
                y = e.Top + (e.Height / 2) - (size / 2);
                if (x + size > work.Right)
                {
                    x = e.Right - size - gap;
                    y = e.Top - size - gap;
                }
            }

            return Clamp(new OverlayPosition(x, y, PlacementSource.Element), size, work);
        }

        if (userPosition is { } p)
        {
            ScreenRect work = workAreaFor(new ScreenRect(p.X, p.Y, size, size));
            return Clamp(new OverlayPosition(p.X, p.Y, PlacementSource.UserPosition), size, work);
        }

        ScreenRect primary = workAreaFor(new ScreenRect(0, 0, 1, 1));
        return new OverlayPosition(primary.Right - size - (gap * 3), primary.Bottom - size - (gap * 3), PlacementSource.Default);
    }

    /// <summary>Mantiene l'icona interamente dentro l'area di lavoro.</summary>
    public static OverlayPosition Clamp(OverlayPosition position, int size, ScreenRect work)
    {
        int maxX = Math.Max(work.Left, work.Right - size);
        int maxY = Math.Max(work.Top, work.Bottom - size);
        return position with
        {
            X = Math.Clamp(position.X, work.Left, maxX),
            Y = Math.Clamp(position.Y, work.Top, maxY),
        };
    }
}
