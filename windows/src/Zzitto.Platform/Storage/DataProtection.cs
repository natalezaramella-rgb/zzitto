// Cifratura locale con DPAPI (CryptProtectData), legata all'account Windows dell'utente.
// Spec §5.6, §5.11, §10 (cronologia, dizionario e scorciatoie cifrati; equivalente Windows dell'Android Keystore).
// Aggiunto il 22/10/2026 alle 11:32.
#pragma warning disable CS0649
using System.Runtime.InteropServices;
using System.Text;

namespace Zzitto.Platform.Storage;

public interface IDataProtector
{
    byte[] Protect(ReadOnlySpan<byte> plaintext);

    /// <summary>Lancia <see cref="System.Security.Cryptography.CryptographicException"/> se i dati sono alterati.</summary>
    byte[] Unprotect(ReadOnlySpan<byte> ciphertext);
}

public sealed unsafe class DpapiDataProtector : IDataProtector
{
    private const int CryptProtectUiForbidden = 0x1;

    // Entropia aggiuntiva: separa i dati di Zzitto da altri blob DPAPI dello stesso utente.
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("Zzitto.Zaramella.v1");

    public byte[] Protect(ReadOnlySpan<byte> plaintext) => Transform(plaintext, protect: true);

    public byte[] Unprotect(ReadOnlySpan<byte> ciphertext) => Transform(ciphertext, protect: false);

    private static byte[] Transform(ReadOnlySpan<byte> input, bool protect)
    {
        fixed (byte* inputPtr = input)
        fixed (byte* entropyPtr = Entropy)
        {
            var inBlob = new DataBlob { cbData = (uint)input.Length, pbData = (IntPtr)inputPtr };
            var entropyBlob = new DataBlob { cbData = (uint)Entropy.Length, pbData = (IntPtr)entropyPtr };
            var outBlob = default(DataBlob);
            bool ok = protect
                ? CryptProtectData(&inBlob, null, &entropyBlob, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, &outBlob)
                : CryptUnprotectData(&inBlob, IntPtr.Zero, &entropyBlob, IntPtr.Zero, IntPtr.Zero, CryptProtectUiForbidden, &outBlob);
            if (!ok)
            {
                int error = Marshal.GetLastPInvokeError();
                throw new System.Security.Cryptography.CryptographicException(
                    (protect ? "Cifratura" : "Decifratura") + " DPAPI non riuscita (errore " + error + ").");
            }

            try
            {
                var result = new byte[outBlob.cbData];
                Marshal.Copy(outBlob.pbData, result, 0, result.Length);
                return result;
            }
            finally
            {
                // Spec §10: il testo in chiaro non resta nella memoria nativa.
                new Span<byte>((void*)outBlob.pbData, (int)outBlob.cbData).Clear();
                LocalFree(outBlob.pbData);
            }
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public uint cbData;
        public IntPtr pbData;
    }

    [DllImport("crypt32.dll", SetLastError = true, ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptProtectData(DataBlob* dataIn, char* description, DataBlob* optionalEntropy,
        IntPtr reserved, IntPtr promptStruct, int flags, DataBlob* dataOut);

    [DllImport("crypt32.dll", SetLastError = true, ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(DataBlob* dataIn, IntPtr description, DataBlob* optionalEntropy,
        IntPtr reserved, IntPtr promptStruct, int flags, DataBlob* dataOut);

    [DllImport("kernel32.dll", ExactSpelling = true)]
    private static extern IntPtr LocalFree(IntPtr memory);
}
