// Disegno dell'icona del microfono: lo stato si comunica solo con colore e animazione (Spec §2.5).
// Rendering software antialiasato in BGRA, usato sia dall'icona flottante (finestra a livelli) sia
// dall'icona nell'area di notifica. Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Overlay;

public enum IconVisual
{
    Idle,
    Listening,
    Transcribing,
    Correcting,
    Inserting,
    Error,
    /// <summary>Prova scaduta: la dettatura è bloccata e l'icona porta all'acquisto (Spec §8.1).</summary>
    Blocked,
    /// <summary>Lampo breve di conferma (comando vocale riconosciuto, testo negli appunti...). Spec §5.9.</summary>
    Notice,
}

public static class MicIconRenderer
{
    /// <summary>Colore di sfondo per stato (ARGB).</summary>
    public static uint BackgroundColor(IconVisual visual) => visual switch
    {
        IconVisual.Idle => 0xFF2F3B4C,
        IconVisual.Listening => 0xFFE53935,
        IconVisual.Transcribing => 0xFFFB8C00,
        IconVisual.Correcting => 0xFF8E24AA,
        IconVisual.Inserting => 0xFF43A047,
        IconVisual.Error => 0xFFB71C1C,
        IconVisual.Blocked => 0xFF757575,
        IconVisual.Notice => 0xFF00ACC1,
        _ => 0xFF2F3B4C,
    };

    /// <summary>Stati animati: l'icona pulsa (ascolto) o "respira" (elaborazione).</summary>
    public static bool IsAnimated(IconVisual visual) =>
        visual is IconVisual.Listening or IconVisual.Transcribing or IconVisual.Correcting or IconVisual.Error;

    /// <summary>
    /// Disegna l'icona quadrata di lato <paramref name="size"/>. <paramref name="phase"/> in [0, 1) è la fase
    /// dell'animazione. Restituisce pixel BGRA dall'alto in basso; con <paramref name="premultiplied"/> i colori
    /// sono moltiplicati per l'alfa (richiesto da UpdateLayeredWindow), altrimenti alfa separato (icone).
    /// </summary>
    public static byte[] Render(int size, IconVisual visual, double phase, bool premultiplied)
    {
        if (size < 8)
        {
            throw new ArgumentOutOfRangeException(nameof(size));
        }

        var pixels = new byte[size * size * 4];
        uint argb = BackgroundColor(visual);
        double bgR = (argb >> 16) & 0xFF;
        double bgG = (argb >> 8) & 0xFF;
        double bgB = argb & 0xFF;

        double center = size / 2.0;
        double wave = 0.5 - (0.5 * Math.Cos(phase * 2 * Math.PI)); // 0..1..0
        bool animated = IsAnimated(visual);

        // Disco principale, leggermente più piccolo per lasciare spazio all'anello animato.
        double ringWidth = Math.Max(1.5, size * 0.07);
        double outer = center - 0.5;
        double discRadius = outer - ringWidth - 1;
        double ringAlpha = animated ? (visual == IconVisual.Listening ? 0.25 + (0.75 * wave) : 0.15 + (0.45 * wave)) : 0;
        double discScale = visual == IconVisual.Listening ? 0.94 + (0.06 * wave) : 1.0;
        double radius = discRadius * discScale;

        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                double px = x + 0.5;
                double py = y + 0.5;
                double dx = px - center;
                double dy = py - center;
                double dist = Math.Sqrt((dx * dx) + (dy * dy));

                double discCoverage = Coverage(radius - dist);
                double ringCoverage = ringAlpha > 0 ? Coverage(Math.Min(outer - dist, dist - (outer - ringWidth))) * ringAlpha : 0;
                double glyph = discCoverage > 0 ? MicGlyphCoverage(px / size, py / size, size) : 0;

                // Composizione: anello (colore di sfondo) sotto, disco sopra, glifo bianco sul disco.
                double alpha = discCoverage + (ringCoverage * (1 - discCoverage));
                if (alpha <= 0)
                {
                    continue;
                }

                double r = bgR + ((255 - bgR) * glyph);
                double g = bgG + ((255 - bgG) * glyph);
                double b = bgB + ((255 - bgB) * glyph);

                int i = ((y * size) + x) * 4;
                double colorFactor = premultiplied ? alpha : 1.0;
                pixels[i] = ToByte(b * colorFactor);
                pixels[i + 1] = ToByte(g * colorFactor);
                pixels[i + 2] = ToByte(r * colorFactor);
                pixels[i + 3] = ToByte(alpha * 255);
            }
        }

        return pixels;
    }

    /// <summary>Copertura (0..1) del glifo del microfono nel punto normalizzato (u, v) ∈ [0, 1].</summary>
    internal static double MicGlyphCoverage(double u, double v, int size)
    {
        double pixel = 1.0 / size; // larghezza di un pixel in coordinate normalizzate

        // Capsula: centro x = 0.5, da y = 0.27 a 0.50, raggio 0.085.
        double capsule = SegmentDistance(u, v, 0.5, 0.30, 0.5, 0.47) - 0.085;

        // Arco a U sotto la capsula: circonferenza di raggio 0.16 centrata in (0.5, 0.47), metà inferiore.
        double arcDist = Math.Abs(Math.Sqrt(((u - 0.5) * (u - 0.5)) + ((v - 0.47) * (v - 0.47))) - 0.16) - 0.03;
        if (v < 0.47)
        {
            // Sopra il centro l'arco termina con estremità arrotondate.
            double left = Math.Sqrt(((u - 0.34) * (u - 0.34)) + ((v - 0.47) * (v - 0.47))) - 0.03;
            double right = Math.Sqrt(((u - 0.66) * (u - 0.66)) + ((v - 0.47) * (v - 0.47))) - 0.03;
            arcDist = Math.Min(left, right);
        }

        // Asta e base.
        double stem = BoxDistance(u, v, 0.5, 0.685, 0.028, 0.06);
        double foot = BoxDistance(u, v, 0.5, 0.745, 0.11, 0.028);

        double d = Math.Min(Math.Min(capsule, arcDist), Math.Min(stem, foot));
        return Coverage(-d / pixel);
    }

    private static double SegmentDistance(double px, double py, double ax, double ay, double bx, double by)
    {
        double abx = bx - ax;
        double aby = by - ay;
        double t = Math.Clamp((((px - ax) * abx) + ((py - ay) * aby)) / ((abx * abx) + (aby * aby)), 0, 1);
        double cx = ax + (t * abx);
        double cy = ay + (t * aby);
        return Math.Sqrt(((px - cx) * (px - cx)) + ((py - cy) * (py - cy)));
    }

    private static double BoxDistance(double px, double py, double cx, double cy, double halfWidth, double halfHeight)
    {
        double qx = Math.Abs(px - cx) - halfWidth;
        double qy = Math.Abs(py - cy) - halfHeight;
        double outside = Math.Sqrt((Math.Max(qx, 0) * Math.Max(qx, 0)) + (Math.Max(qy, 0) * Math.Max(qy, 0)));
        return outside + Math.Min(Math.Max(qx, qy), 0);
    }

    /// <summary>Distanza con segno (positiva = dentro, in pixel) → copertura antialiasata.</summary>
    private static double Coverage(double signedDistancePixels) => Math.Clamp(signedDistancePixels + 0.5, 0, 1);

    private static byte ToByte(double value) => (byte)Math.Clamp((int)Math.Round(value), 0, 255);
}
