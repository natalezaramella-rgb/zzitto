// Scelta del microfono quando ce n'è più di uno (Spec §5.7, impostazioni; §4.3 "win-audio").
// Richiesta del titolare del 22/10/2026. Aggiunto il 22/10/2026 alle 17:43.
using System.Runtime.InteropServices;
using Zzitto.Platform.Interop;

namespace Zzitto.Platform.Audio;

/// <summary>Microfono attivo nel sistema. <see cref="Id"/> è l'identificativo stabile dell'endpoint WASAPI.</summary>
public sealed record MicrophoneInfo(string Id, string Name, bool IsSystemDefault);

/// <summary>Logica pura della scelta: testabile senza hardware.</summary>
public static class MicrophoneSelection
{
    /// <summary>
    /// Dispositivo da usare: quello scelto se è ancora collegato, altrimenti null (= predefinito di sistema).
    /// Un microfono scollegato non blocca la dettatura: si torna al predefinito senza errori.
    /// </summary>
    public static string? Resolve(string? preferredId, IEnumerable<MicrophoneInfo> available)
    {
        ArgumentNullException.ThrowIfNull(available);
        if (string.IsNullOrWhiteSpace(preferredId))
        {
            return null;
        }

        return available.Any(m => string.Equals(m.Id, preferredId, StringComparison.OrdinalIgnoreCase)) ? preferredId : null;
    }

    /// <summary>Nomi univoci per l'elenco delle impostazioni (due microfoni con lo stesso nome ricevono " (2)").</summary>
    public static IReadOnlyList<MicrophoneInfo> WithUniqueNames(IEnumerable<MicrophoneInfo> devices)
    {
        ArgumentNullException.ThrowIfNull(devices);
        var seen = new Dictionary<string, int>(StringComparer.CurrentCultureIgnoreCase);
        var result = new List<MicrophoneInfo>();
        foreach (MicrophoneInfo device in devices.OrderByDescending(d => d.IsSystemDefault).ThenBy(d => d.Name, StringComparer.CurrentCultureIgnoreCase))
        {
            string name = string.IsNullOrWhiteSpace(device.Name) ? "Microfono" : device.Name.Trim();
            seen[name] = seen.TryGetValue(name, out int n) ? n + 1 : 1;
            result.Add(device with { Name = seen[name] == 1 ? name : $"{name} ({seen[name]})" });
        }

        return result;
    }
}

/// <summary>Elenco dei microfoni attivi tramite MMDevice API.</summary>
public static class MicrophoneDevices
{
    private const uint DeviceStateActive = 0x1;
    private const uint StgmRead = 0;

    // PKEY_Device_FriendlyName (functiondiscoverykeys_devpkey.h).
    private static readonly Guid FriendlyNameFormat = new("A45C254E-DF1C-4EFD-8020-67D146A850E0");

    /// <summary>Microfoni attivi, il predefinito per primo. In caso di errore restituisce un elenco vuoto.</summary>
    public static IReadOnlyList<MicrophoneInfo> List()
    {
        IMMDeviceEnumerator? enumerator = null;
        IMMDeviceCollection? collection = null;
        var devices = new List<MicrophoneInfo>();
        try
        {
            enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
            string? defaultId = null;
            if (enumerator.GetDefaultAudioEndpoint(EDataFlow.Capture, ERole.Console, out IMMDevice defaultDevice) >= 0)
            {
                defaultDevice.GetId(out defaultId);
                Release(defaultDevice);
            }

            if (enumerator.EnumAudioEndpoints(EDataFlow.Capture, DeviceStateActive, out collection) < 0 ||
                collection.GetCount(out uint count) < 0)
            {
                return devices;
            }

            for (uint i = 0; i < count; i++)
            {
                if (collection.Item(i, out IMMDevice device) < 0)
                {
                    continue;
                }

                try
                {
                    if (device.GetId(out string id) >= 0 && !string.IsNullOrEmpty(id))
                    {
                        devices.Add(new MicrophoneInfo(id, FriendlyName(device), string.Equals(id, defaultId, StringComparison.OrdinalIgnoreCase)));
                    }
                }
                finally
                {
                    Release(device);
                }
            }
        }
        catch (COMException)
        {
            // Servizio audio non disponibile: nessun microfono da proporre, resta il predefinito.
        }
        finally
        {
            Release(collection);
            Release(enumerator);
        }

        return MicrophoneSelection.WithUniqueNames(devices);
    }

    private static string FriendlyName(IMMDevice device)
    {
        if (device.OpenPropertyStore(StgmRead, out IPropertyStore store) < 0)
        {
            return string.Empty;
        }

        try
        {
            var key = new PropertyKey(FriendlyNameFormat, 14);
            if (store.GetValue(ref key, out PropVariant value) < 0)
            {
                return string.Empty;
            }

            try
            {
                return value.VarType == PropVariantNative.VtLpwstr && value.Value != IntPtr.Zero
                    ? Marshal.PtrToStringUni(value.Value) ?? string.Empty
                    : string.Empty;
            }
            finally
            {
                PropVariantNative.PropVariantClear(ref value);
            }
        }
        finally
        {
            Release(store);
        }
    }

    private static void Release(object? comObject)
    {
        if (comObject is not null && Marshal.IsComObject(comObject))
        {
            Marshal.ReleaseComObject(comObject);
        }
    }
}
