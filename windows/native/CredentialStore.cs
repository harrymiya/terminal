using System.Runtime.InteropServices;
using System.Security.Cryptography;
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
            return Convert.ToBase64String(ProtectedData.Protect(clear, entropy, DataProtectionScope.CurrentUser));
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
            clear = ProtectedData.Unprotect(blob, entropy, DataProtectionScope.CurrentUser);
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

    private sealed class CredentialPayload
    {
        public CredentialPayload() { }
        public string? UserName { get; set; }
        public string? Secret { get; set; }
    }

    private const uint Generic = 1;

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
}
