// Testi dell'interfaccia dalle risorse (Strings/<lingua>/Resources.resw): interfaccia predisposta per altre
// lingue (Spec §2.1). Aggiunto il 22/10/2026 alle 11:32.
using System.Globalization;
using Microsoft.Windows.ApplicationModel.Resources;

namespace Zzitto.App.Services;

internal static class Loc
{
    private static readonly Lazy<ResourceLoader?> Loader = new(() =>
    {
        try
        {
            return new ResourceLoader();
        }
        catch (Exception)
        {
            return null;
        }
    });

    /// <summary>Testo per la chiave indicata; se manca restituisce la chiave stessa (errore visibile, non un crash).</summary>
    public static string Get(string key)
    {
        try
        {
            string? value = Loader.Value?.GetString(key);
            return string.IsNullOrEmpty(value) ? key : value;
        }
        catch (Exception)
        {
            return key;
        }
    }

    public static string Format(string key, params object[] args) =>
        string.Format(CultureInfo.CurrentCulture, Get(key), args);
}
