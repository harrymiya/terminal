using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace RemoteDevTerminal;

internal static class CredentialStore
{
    private const uint Generic = 1;
    private const uint PersistLocalMachine = 2;

    public static void Write(string target, string userName, string secret)
    {
        var blob = Encoding.Unicode.GetBytes(secret);
        var pointer = Marshal.AllocHGlobal(blob.Length);
        try
        {
            Marshal.Copy(blob, 0, pointer, blob.Length);
            var credential = new NativeCredential
            {
                Type = Generic,
                TargetName = target,
                UserName = userName,
                CredentialBlob = pointer,
                CredentialBlobSize = (uint)blob.Length,
                Persist = PersistLocalMachine
            };
            if (!CredWrite(ref credential, 0)) throw new Win32Exception(Marshal.GetLastWin32Error(), "无法保存 Windows 凭据。");
        }
        finally
        {
            for (var i = 0; i < blob.Length; i++) Marshal.WriteByte(pointer, i, 0);
            Marshal.FreeHGlobal(pointer);
            Array.Clear(blob, 0, blob.Length);
        }
    }

    public static (string UserName, string Secret)? Read(string target)
    {
        if (!CredRead(target, Generic, 0, out var pointer)) return null;
        try
        {
            var credential = Marshal.PtrToStructure<NativeCredential>(pointer);
            var secret = credential.CredentialBlobSize == 0 ? "" : Marshal.PtrToStringUni(credential.CredentialBlob, checked((int)credential.CredentialBlobSize / 2)) ?? "";
            return (credential.UserName ?? "", secret);
        }
        finally { CredFree(pointer); }
    }

    public static void Delete(string target)
    {
        if (!CredDelete(target, Generic, 0))
        {
            var error = Marshal.GetLastWin32Error();
            if (error != 1168) throw new Win32Exception(error, "无法删除 Windows 凭据。");
        }
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

    [DllImport("advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredWrite(ref NativeCredential credential, uint flags);
    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
    [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CredDelete(string target, uint type, uint flags);
    [DllImport("advapi32.dll", EntryPoint = "CredFree")]
    private static extern void CredFree(IntPtr buffer);
}
