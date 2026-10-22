// Test del posizionamento e del disegno dell'icona flottante (Spec §2.4, §2.5, §2.6, §12). Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Input;
using Zzitto.Platform.Overlay;

namespace Zzitto.Tests;

public class OverlayPlacementTests
{
    private static readonly ScreenRect Work = new(0, 0, 1920, 1040);

    private static ScreenRect WorkArea(ScreenRect _) => Work;

    [Fact]
    public void Caret_IconToTheRightCenteredOnLine()
    {
        OverlayPosition p = OverlayPlacement.Compute(new ScreenRect(500, 300, 2, 20), new ScreenRect(100, 280, 800, 60), null, 40, 1.0, WorkArea);
        Assert.Equal(PlacementSource.Caret, p.Source);
        Assert.Equal(510, p.X);
        Assert.Equal(290, p.Y);
    }

    [Fact]
    public void Caret_AtRightEdge_IconGoesLeft()
    {
        OverlayPosition p = OverlayPlacement.Compute(new ScreenRect(1900, 300, 2, 20), null, null, 40, 1.0, WorkArea);
        Assert.Equal(1900 - 8 - 40, p.X);
    }

    [Fact]
    public void TallElement_IconInsideBottomRight()
    {
        OverlayPosition p = OverlayPlacement.Compute(null, new ScreenRect(100, 100, 600, 300), null, 40, 1.0, WorkArea);
        Assert.Equal(PlacementSource.Element, p.Source);
        Assert.Equal(700 - 40 - 8, p.X);
        Assert.Equal(400 - 40 - 8, p.Y);
    }

    [Fact]
    public void SingleLineElement_IconToTheRight()
    {
        OverlayPosition p = OverlayPlacement.Compute(null, new ScreenRect(100, 100, 300, 24), null, 40, 1.0, WorkArea);
        Assert.Equal(408, p.X);
        Assert.Equal(92, p.Y);
    }

    [Fact]
    public void NoInformation_UsesLastUserPosition()
    {
        // Spec §2.6: app che non espongono il campo → ultima posizione scelta dall'utente.
        OverlayPosition p = OverlayPlacement.Compute(null, null, (1200, 700), 40, 1.0, WorkArea);
        Assert.Equal(new OverlayPosition(1200, 700, PlacementSource.UserPosition), p);
    }

    [Fact]
    public void NoInformationAtAll_DefaultCorner()
    {
        OverlayPosition p = OverlayPlacement.Compute(null, null, null, 40, 1.0, WorkArea);
        Assert.Equal(PlacementSource.Default, p.Source);
        Assert.True(Work.Contains(p.X, p.Y));
        Assert.True(Work.Contains(p.X + 39, p.Y + 39));
    }

    [Fact]
    public void Position_IsClampedToWorkArea()
    {
        OverlayPosition p = OverlayPlacement.Compute(null, null, (5000, -300), 40, 1.0, WorkArea);
        Assert.Equal(1880, p.X);
        Assert.Equal(0, p.Y);
    }

    [Fact]
    public void Gap_ScalesWithDpi()
    {
        OverlayPosition p = OverlayPlacement.Compute(new ScreenRect(500, 300, 2, 20), null, null, 60, 1.5, WorkArea);
        Assert.Equal(502 + 12, p.X);
    }

    [Fact]
    public void EmptyRectangles_AreIgnored()
    {
        OverlayPosition p = OverlayPlacement.Compute(new ScreenRect(10, 10, 0, 0), new ScreenRect(0, 0, 0, 0), (300, 300), 40, 1.0, WorkArea);
        Assert.Equal(PlacementSource.UserPosition, p.Source);
    }
}

public class MicIconRendererTests
{
    [Theory]
    [InlineData(IconVisual.Idle)]
    [InlineData(IconVisual.Listening)]
    [InlineData(IconVisual.Error)]
    public void CenterIsOpaque_CornersTransparent(IconVisual visual)
    {
        const int size = 40;
        byte[] pixels = MicIconRenderer.Render(size, visual, 0.5, premultiplied: true);
        Assert.Equal(size * size * 4, pixels.Length);
        Assert.Equal(0, pixels[3]); // angolo in alto a sinistra
        int center = (((size / 2) * size) + (size / 2)) * 4;
        Assert.Equal(255, pixels[center + 3]);
    }

    [Fact]
    public void StatesHaveDifferentColors()
    {
        // Spec §2.5: lo stato si comunica con il colore.
        var colors = Enum.GetValues<IconVisual>().Select(MicIconRenderer.BackgroundColor).ToList();
        Assert.Equal(colors.Count, colors.Distinct().Count());
    }

    [Fact]
    public void Premultiplied_NeverExceedsAlpha()
    {
        byte[] pixels = MicIconRenderer.Render(32, IconVisual.Listening, 0.25, premultiplied: true);
        for (int i = 0; i < pixels.Length; i += 4)
        {
            Assert.True(pixels[i] <= pixels[i + 3] && pixels[i + 1] <= pixels[i + 3] && pixels[i + 2] <= pixels[i + 3]);
        }
    }

    [Fact]
    public void ListeningIsAnimated_IdleIsNot()
    {
        Assert.True(MicIconRenderer.IsAnimated(IconVisual.Listening));
        Assert.False(MicIconRenderer.IsAnimated(IconVisual.Idle));
        Assert.NotEqual(
            MicIconRenderer.Render(32, IconVisual.Listening, 0.0, true),
            MicIconRenderer.Render(32, IconVisual.Listening, 0.5, true));
    }
}
