// Coordina l'icona flottante: segue il campo attivo, ricorda la posizione scelta dall'utente,
// si nasconde a schermo intero. Spec §2.4, §2.5, §2.6. Aggiunto il 22/10/2026 alle 11:32.
using Zzitto.Platform.Input;
using Zzitto.Platform.Shell;
using Zzitto.Platform.Storage;

namespace Zzitto.Platform.Overlay;

public sealed class OverlayCoordinator : IDisposable
{
    private static readonly TimeSpan Debounce = TimeSpan.FromMilliseconds(150);

    private readonly ShellHost _shell;
    private readonly ISettingsProvider _settings;
    private readonly Func<bool> _canMove;
    private readonly FocusAnchorReader _reader = new();
    private readonly Timer _timer;
    private readonly object _lock = new();
    private bool _running;
    private bool _pending;
    private bool _disposed;

    /// <param name="canMove">false durante la dettatura: l'icona resta ferma finché il ciclo non termina.</param>
    public OverlayCoordinator(ShellHost shell, ISettingsProvider settings, Func<bool> canMove)
    {
        _shell = shell ?? throw new ArgumentNullException(nameof(shell));
        _settings = settings ?? throw new ArgumentNullException(nameof(settings));
        _canMove = canMove ?? throw new ArgumentNullException(nameof(canMove));
        _timer = new Timer(_ => Run(), null, Timeout.Infinite, Timeout.Infinite);
        _shell.FocusChanged += OnFocusChanged;
        _shell.OverlayMovedByUser += OnMovedByUser;
        _settings.Changed += OnSettingsChanged;
    }

    /// <summary>Ricalcola la posizione (dopo un breve intervallo, per raggruppare gli eventi di focus).</summary>
    public void Refresh()
    {
        lock (_lock)
        {
            if (!_disposed)
            {
                _timer.Change(Debounce, Timeout.InfiniteTimeSpan);
            }
        }
    }

    public void Dispose()
    {
        lock (_lock)
        {
            _disposed = true;
            _timer.Dispose();
        }

        _shell.FocusChanged -= OnFocusChanged;
        _shell.OverlayMovedByUser -= OnMovedByUser;
        _settings.Changed -= OnSettingsChanged;
    }

    private void OnFocusChanged(object? sender, EventArgs e) => Refresh();

    private void OnSettingsChanged(object? sender, ZzittoSettings e) => Refresh();

    private void OnMovedByUser(object? sender, (int X, int Y) position)
    {
        // Spec §2.6: l'ultima posizione scelta dall'utente è il riferimento quando il campo non è rilevabile.
        _settings.Update(s => s with { OverlayX = position.X, OverlayY = position.Y });
    }

    private void Run()
    {
        lock (_lock)
        {
            if (_disposed)
            {
                return;
            }

            if (_running)
            {
                _pending = true;
                return;
            }

            _running = true;
        }

        try
        {
            Reposition();
        }
        catch (Exception)
        {
            // Il posizionamento è un aiuto visivo: un errore non deve fermare l'app.
        }
        finally
        {
            bool again;
            lock (_lock)
            {
                _running = false;
                again = _pending;
                _pending = false;
            }

            if (again)
            {
                Refresh();
            }
        }
    }

    private void Reposition()
    {
        ZzittoSettings settings = _settings.Current;
        if (settings.OverlayMode == OverlayMode.Hidden || ShellHost.IsFullScreenAppActive())
        {
            _shell.HideOverlay();
            return;
        }

        if (!_canMove())
        {
            return;
        }

        (int X, int Y)? user = settings.OverlayX is int x && settings.OverlayY is int y ? (x, y) : null;
        ScreenRect? caret = null;
        ScreenRect? element = null;
        if (settings.OverlayMode == OverlayMode.FollowField)
        {
            FieldAnchor? anchor = _reader.Read();
            if (anchor is not null && anchor.ProcessId == Environment.ProcessId)
            {
                return; // focus nelle finestre di Zzitto: l'icona non si sposta
            }

            if (anchor is { IsEditable: true })
            {
                caret = anchor.Caret;
                element = anchor.Element;
            }
            else if (anchor is { Caret: not null })
            {
                caret = anchor.Caret; // cursore Win32 senza UI Automation
            }
        }

        ScreenRect reference = caret ?? element ?? (user is { } u ? new ScreenRect(u.X, u.Y, 1, 1) : new ScreenRect(0, 0, 1, 1));
        double scale = ShellHost.ScaleFor(reference);
        int size = ShellHost.OverlaySizeFor(scale);
        OverlayPosition position = OverlayPlacement.Compute(caret, element, user, size, scale, ShellHost.WorkAreaFor);
        _shell.ShowOverlayAt(position.X, position.Y, scale);
    }
}
