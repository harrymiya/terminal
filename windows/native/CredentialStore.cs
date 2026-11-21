using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.IO;
using System.Text;
using System.Text.Json;

namespace RemoteDevTerminal;

internal static class CredentialStore
{
    private static readonly object Gate = new();
    private static readonly string FilePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "RemoteDevTerminal", "credentials.json");

    public static void Write(string target, string userName, string secret)
    {
        lock (Gate)
        {
            var entries = LoadEntries();
            entries[target] = Protect(target, userName, secret);
            SaveEntries(entries);
            DeleteLegacy(target);
        }
    }

    public static (string UserName, string Secret)? Read(string target)
    {
        lock (Gate)
        {
            var entries = LoadEntries();
            if (entries.TryGetValue(target, out var encrypted))
            {
                try { return Unprotect(target, encrypted); }
                catch (CryptographicException) { return null; }
                catch (JsonException) { return null; }
                catch (FormatException) { return null; }
            }

            // Move credentials saved by older versions out of Windows Credential Manager
            // the first time they are used. New reads and writes use only the local file.
            var legacy = ReadLegacy(target);
            if (legacy is null) return null;
            entries[target] = Protect(target, legacy.Value.UserName, legacy.Value.Secret);
            SaveEntries(entries);
            DeleteLegacy(target);
            return legacy;
        }
    }

    public static void Delete(string target)
    {
        lock (Gate)
        {
            var entries = LoadEntries();
            if (entries.Remove(target)) SaveEntries(entries);
            DeleteLegacy(target);
        }
    }

    private static Dictionary<string, string> LoadEntries()
    {
        if (!File.Exists(FilePath)) return new Dictionary<string, string>(StringComparer.Ordinal);
        try
        {
            return JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(FilePath))
                ?? new Dictionary<string, string>(StringComparer.Ordinal);
        }
        catch (JsonException)
        {
            return new Dictionary<string, string>(StringComparer.Ordinal);
        }
    }

    private static string Protect(string target, string userName, string secret)
    {
        var clear = JsonSerializer.SerializeToUtf8Bytes(new CredentialPayload { UserName = userName, Secret = secret });
        var entropy = Encoding.UTF8.GetBytes(target);
        try
        {
            return Convert.ToBase64String(ProtectWithDpapi(clear, entropy));
        }
        finally
        {
            Array.Clear(clear, 0, clear.Length);
            Array.Clear(entropy, 0, entropy.Length);
        }
    }

    private static (string UserName, string Secret) Unprotect(string target, string encrypted)
    {
        var blob = Convert.FromBase64String(encrypted);
        var entropy = Encoding.UTF8.GetBytes(target);
        byte[]? clear = null;
        try
        {
            clear = UnprotectWithDpapi(blob, entropy);
            var payload = JsonSerializer.Deserialize<CredentialPayload>(clear)
                ?? throw new JsonException("Credential payload is empty.");
            return (payload.UserName ?? "", payload.Secret ?? "");
        }
        finally
        {
            Array.Clear(blob, 0, blob.Length);
            Array.Clear(entropy, 0, entropy.Length);
            if (clear is not null) Array.Clear(clear, 0, clear.Length);
        }
    }

    private static void SaveEntries(Dictionary<string, string> entries)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        var temp = FilePath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            File.WriteAllText(temp, JsonSerializer.Serialize(entries));
            if (File.Exists(FilePath)) File.Replace(temp, FilePath, null, true);
            else File.Move(temp, FilePath);
        }
        finally
        {
            if (File.Exists(temp)) File.Delete(temp);
        }
    }

    private static (string UserName, string Secret)? ReadLegacy(string target)
    {
        if (!CredRead(target, Generic, 0, out var pointer)) return null;
        try
        {
            var credential = Marshal.PtrToStructure<NativeCredential>(pointer);
            var secret = credential.CredentialBlobSize == 0 ? "" :
                Marshal.PtrToStringUni(credential.CredentialBlob, checked((int)credential.CredentialBlobSize / 2)) ?? "";
            return (credential.UserName ?? "", secret);
        }
        finally { CredFree(pointer); }
    }

    private static void DeleteLegacy(string target)
    {
        // Migration and local-cache operations remain usable even if Windows refuses cleanup.
        CredDelete(target, Generic, 0);
    }

    private static byte[] ProtectWithDpapi(byte[] clear, byte[] entropy)
    {
        var input = AllocateBlob(clear);
        var optionalEntropy = AllocateBlob(entropy);
        DataBlob output = default;
        try
        {
            if (!CryptProtectData(ref input, null, ref optionalEntropy, IntPtr.Zero, IntPtr.Zero,
                    CryptProtectUiForbidden, out output))
                throw new CryptographicException(Marshal.GetLastWin32Error());
            return CopyBlob(output);
        }
        finally
        {
            ClearAndFreeBlob(ref input);
            ClearAndFreeBlob(ref optionalEntropy);
            ClearAndFreeLocalBlob(ref output);
        }
    }

    private static byte[] UnprotectWithDpapi(byte[] encrypted, byte[] entropy)
    {
        var input = AllocateBlob(encrypted);
        var optionalEntropy = AllocateBlob(entropy);
        DataBlob output = default;
        IntPtr description = IntPtr.Zero;
        try
        {
            if (!CryptUnprotectData(ref input, out description, ref optionalEntropy, IntPtr.Zero, IntPtr.Zero,
                    CryptProtectUiForbidden, out output))
                throw new CryptographicException(Marshal.GetLastWin32Error());
            return CopyBlob(output);
        }
        finally
        {
            ClearAndFreeBlob(ref input);
            ClearAndFreeBlob(ref optionalEntropy);
            ClearAndFreeLocalBlob(ref output);
            if (description != IntPtr.Zero) LocalFree(description);
        }
    }

    private static DataBlob AllocateBlob(byte[] bytes)
    {
        if (bytes.Length == 0) return default;
        var pointer = Marshal.AllocHGlobal(bytes.Length);
        Marshal.Copy(bytes, 0, pointer, bytes.Length);
        return new DataBlob { Size = bytes.Length, Data = pointer };
    }

    private static byte[] CopyBlob(DataBlob blob)
    {
        if (blob.Size == 0) return Array.Empty<byte>();
        var result = new byte[blob.Size];
        Marshal.Copy(blob.Data, result, 0, result.Length);
        return result;
    }

    private static void ClearAndFreeBlob(ref DataBlob blob)
    {
        if (blob.Data == IntPtr.Zero) return;
        for (var index = 0; index < blob.Size; index++) Marshal.WriteByte(blob.Data, index, 0);
        Marshal.FreeHGlobal(blob.Data);
        blob = default;
    }

    private static void ClearAndFreeLocalBlob(ref DataBlob blob)
    {
        if (blob.Data == IntPtr.Zero) return;
        for (var index = 0; index < blob.Size; index++) Marshal.WriteByte(blob.Data, index, 0);
        LocalFree(blob.Data);
        blob = default;
    }

    private sealed class CredentialPayload
    {
        public CredentialPayload() { }
        public string? UserName { get; set; }
        public string? Secret { get; set; }
    }

    private const uint Generic = 1;
    private const uint CryptProtectUiForbidden = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int Size;
        public IntPtr Data;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct NativeCredential
    {
        public uint Flags;
        public uint Type;
        public string? TargetName;
        public string? Comment;
        public long LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist;
        public uint AttributeCount;
        public IntPtr Attributes;
        public string? TargetAlias;
        public string? UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
    [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredDelete(string target, uint type, uint flags);
    [DllImport("advapi32.dll", EntryPoint = "CredFree")]
    private static extern void CredFree(IntPtr buffer);
    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptProtectData(ref DataBlob dataIn, string? description, ref DataBlob optionalEntropy,
        IntPtr reserved, IntPtr promptStruct, uint flags, out DataBlob dataOut);
    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CryptUnprotectData(ref DataBlob dataIn, out IntPtr description, ref DataBlob optionalEntropy,
        IntPtr reserved, IntPtr promptStruct, uint flags, out DataBlob dataOut);
    [DllImport("kernel32.dll", EntryPoint = "LocalFree")]
    private static extern IntPtr LocalFree(IntPtr memory);
}
