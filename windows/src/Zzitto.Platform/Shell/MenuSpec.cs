// Descrizione di un menu contestuale (area di notifica e icona flottante). Spec §2.4, §5.1. Aggiunto il 22/10/2026 alle 11:32.
namespace Zzitto.Platform.Shell;

public sealed record MenuItemSpec(
    int Id,
    string Text,
    bool Checked = false,
    bool Enabled = true,
    IReadOnlyList<MenuItemSpec>? Children = null)
{
    public bool IsSeparator => Id == SeparatorId;

    public const int SeparatorId = -1;

    public static MenuItemSpec Separator { get; } = new(SeparatorId, string.Empty);
}
