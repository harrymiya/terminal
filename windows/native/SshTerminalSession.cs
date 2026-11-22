using System.Collections;
using Microsoft.Win32.SafeHandles;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace RemoteDevTerminal;

internal sealed class SshTerminalSession : IDisposable
{
    private const uint HandleFlagInherit = 1;
    private const uint ExtendedStartupInfoPresent = 0x00080000;
    private const uint CreateUnicodeEnvironment = 0x00000400;
    private const uint StartfUseStdHandles = 0x00000100;
    private const int ProcThreadAttributePseudoConsole = 0x00020016;
    private const uint WaitObject0 = 0;
    private const uint WaitInfinite = 0xffffffff;

    private readonly object _writeLock = new();
    private SafeFileHandle? _input;
    private SafeFileHandle? _output;
    private IntPtr _pseudoConsole;
    private IntPtr _process;
    private Task? _reader;
    private volatile bool _disposed;
    private volatile bool _closed;

    public string Id { get; }
    public event Action<byte[]>? DataReceived;
    public event Action<int>? Exited;

    public SshTerminalSession(string id) => Id = id;

    public void Start(string username, string address, int port, string directory, string agent, int columns, int rows, string savedPassword = "", bool restoreOnly = false)
    {
        if (string.IsNullOrWhiteSpace(username) || string.IsNullOrWhiteSpace(address))
            throw new ArgumentException("请填写 SSH 用户名和地址。");
        if (string.IsNullOrWhiteSpace(directory) || !directory.StartsWith("/", StringComparison.Ordinal))
            throw new ArgumentException("远端工作区必须使用绝对路径。");
        if (port is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(port));

        var ssh = ResolveSshExecutable();

        SafeFileHandle? childInput = null;
        SafeFileHandle? childOutput = null;
        IntPtr attributeList = IntPtr.Zero;
        try
        {
            var security = new SecurityAttributes { Length = Marshal.SizeOf<SecurityAttributes>(), InheritHandle = true };
            if (!CreatePipe(out childInput, out _input, ref security, 0)) ThrowLastError("创建终端输入通道失败");
            if (!CreatePipe(out _output, out childOutput, ref security, 0)) ThrowLastError("创建终端输出通道失败");
            SetHandleInformation(_input, HandleFlagInherit, 0);
            SetHandleInformation(_output, HandleFlagInherit, 0);

            var size = new Coord { X = ClampShort(columns, 20), Y = ClampShort(rows, 5) };
            var hr = CreatePseudoConsole(size, childInput, childOutput, 0, out _pseudoConsole);
            if (hr < 0) Marshal.ThrowExceptionForHR(hr);

            nuint attributeBytes = 0;
            InitializeProcThreadAttributeList(IntPtr.Zero, 1, 0, ref attributeBytes);
            if (attributeBytes == 0) ThrowLastError("初始化终端进程属性失败");
            attributeList = Marshal.AllocHGlobal((nint)attributeBytes);
            if (!InitializeProcThreadAttributeList(attributeList, 1, 0, ref attributeBytes)) ThrowLastError("初始化终端进程属性失败");
            if (!UpdateProcThreadAttribute(attributeList, 0, (IntPtr)ProcThreadAttributePseudoConsole,
                    _pseudoConsole, (nuint)IntPtr.Size, IntPtr.Zero, IntPtr.Zero))
                ThrowLastError("附加 Windows 伪终端失败");

            var remote = BuildRemoteCommand(Id, directory, agent, restoreOnly);
            var commandLine = string.Join(" ", new[] {
                QuoteArgument(ssh), "-tt", "-o", "ServerAliveInterval=20", "-o", "ServerAliveCountMax=6",
                "-p", port.ToString(), "-l", QuoteArgument(username), "--", QuoteArgument(address), QuoteArgument(remote)
            });
            var startup = new StartupInfoEx
            {
                StartupInfo = new StartupInfo
                {
                    Cb = Marshal.SizeOf<StartupInfoEx>()
                },
                AttributeList = attributeList
            };
            var environment = BuildEnvironmentBlock(savedPassword);
            ProcessInformation processInfo;
            try
            {
                if (!CreateProcess(ssh, new StringBuilder(commandLine), IntPtr.Zero, IntPtr.Zero, false,
                        ExtendedStartupInfoPresent | CreateUnicodeEnvironment, environment, null,
                        ref startup, out processInfo))
                    ThrowLastError("启动 Windows OpenSSH 失败");
            }
            finally { Marshal.FreeHGlobal(environment); }

            _process = processInfo.Process;
            CloseHandle(processInfo.Thread);
            childInput.Dispose(); childInput = null;
            childOutput.Dispose(); childOutput = null;
            _reader = Task.Run(ReadLoop);
            _ = Task.Run(MonitorProcess);
        }
        catch
        {
            childInput?.Dispose(); childOutput?.Dispose();
            _input?.Dispose(); _output?.Dispose(); _input = null; _output = null;
            ClosePseudoConsoleHandle();
            throw;
        }
        finally
        {
            if (attributeList != IntPtr.Zero)
            {
                DeleteProcThreadAttributeList(attributeList);
                Marshal.FreeHGlobal(attributeList);
            }
        }
    }

    public void Write(string text)
    {
        if (_disposed || _closed || _input is null || _input.IsInvalid) return;
        Write(Encoding.UTF8.GetBytes(text));
    }

    internal static string ResolveSshExecutable()
    {
        var bundled = Path.Combine(AppContext.BaseDirectory, "OpenSSH", "ssh.exe");
        var candidates = new[]
        {
            bundled,
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "OpenSSH", "ssh.exe"),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "System32", "OpenSSH", "ssh.exe")
        };
        foreach (var candidate in candidates)
            if (File.Exists(candidate)) return candidate;
        foreach (var directory in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(directory)) continue;
            var candidate = Path.Combine(directory.Trim('"'), "ssh.exe");
            if (File.Exists(candidate)) return candidate;
        }
        throw new FileNotFoundException(
            "找不到 SSH 客户端。请重新安装 Remote Dev Terminal，并确认程序目录内包含 OpenSSH\\ssh.exe。",
            bundled);
    }

    private void Write(byte[] bytes)
    {
        lock (_writeLock)
        {
            if (_disposed || _closed || _input is null || _input.IsInvalid) return;
            var offset = 0;
            while (offset < bytes.Length)
            {
                var chunk = new byte[bytes.Length - offset];
                Buffer.BlockCopy(bytes, offset, chunk, 0, chunk.Length);
                if (!WriteFile(_input, chunk, (uint)chunk.Length, out var written, IntPtr.Zero))
                {
                    var error = Marshal.GetLastWin32Error();
                    if (error is not 6 and not 109) throw new Win32Exception(error, "写入终端失败");
                    return;
                }
                if (written == 0) return;
                offset += (int)written;
            }
        }
    }

    public void Resize(int columns, int rows)
    {
        var handle = _pseudoConsole;
        if (_disposed || handle == IntPtr.Zero) return;
        var size = new Coord { X = ClampShort(columns, 20), Y = ClampShort(rows, 5) };
        ResizePseudoConsole(handle, size);
    }

    private void ReadLoop()
    {
        var buffer = new byte[8192];
        while (!_disposed && _output is { IsInvalid: false, IsClosed: false } output)
        {
            try
            {
                if (!ReadFile(output, buffer, (uint)buffer.Length, out var read, IntPtr.Zero) || read == 0) break;
                var chunk = new byte[(int)read]; Buffer.BlockCopy(buffer, 0, chunk, 0, (int)read);
                DataReceived?.Invoke(chunk);
            }
            catch (ObjectDisposedException) { break; }
            catch (Exception) when (_disposed) { break; }
        }
    }

    private void MonitorProcess()
    {
        var process = _process;
        if (process == IntPtr.Zero) return;
        WaitForSingleObject(process, WaitInfinite);
        GetExitCodeProcess(process, out var exitCode);
        _closed = true;
        ClosePseudoConsoleHandle();
        try { _reader?.Wait(TimeSpan.FromSeconds(2)); } catch { }
        CloseHandle(process);
        _process = IntPtr.Zero;
        if (!_disposed) Exited?.Invoke(unchecked((int)exitCode));
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        var process = _process;
        if (process != IntPtr.Zero && WaitForSingleObject(process, 0) != WaitObject0)
            TerminateProcess(process, 1);
        ClosePseudoConsoleHandle();
        _input?.Dispose(); _input = null;
        _output?.Dispose(); _output = null;
    }

    private void ClosePseudoConsoleHandle()
    {
        var handle = Interlocked.Exchange(ref _pseudoConsole, IntPtr.Zero);
        if (handle != IntPtr.Zero) ClosePseudoConsole(handle);
    }

    internal static string BuildCloseRemoteSessionCommand(string id)
    {
        var safeId = new string(id.Where(Uri.IsHexDigit).Take(18).ToArray()).ToLowerInvariant();
        if (safeId.Length < 8) return "";
        var session = "rdt_" + safeId;
        return $"if command -v tmux >/dev/null 2>&1 && tmux has-session -t {session} 2>/dev/null; then " +
               $"for p in $(tmux list-panes -t {session} -F '#{{pane_pid}}' 2>/dev/null); do " +
               $"pkill -TERM -P \"$p\" 2>/dev/null || true; kill -TERM \"$p\" 2>/dev/null || true; " +
               $"done; " +
               $"tmux kill-session -t {session} 2>/dev/null || true; " +
               $"fi";
    }

    private static string BuildRemoteCommand(string id, string directory, string agent, bool restoreOnly = false)
    {
        var safeId = new string(id.Where(Uri.IsHexDigit).Take(18).ToArray()).ToLowerInvariant();
        if (safeId.Length < 8) safeId = Guid.NewGuid().ToString("N").Substring(0, 18);
        var session = "rdt_" + safeId;
        var qDirectory = ShellQuote(directory);
        var qAgent = ShellQuote(agent);
        if (restoreOnly)
        {
            return $"cd -- {qDirectory} && if command -v tmux >/dev/null 2>&1 && tmux has-session -t {session} 2>/dev/null; then " +
                   $"tmux set-option -t {session} mouse on && tmux attach-session -t {session}; " +
                   $"else printf '\\n[会话已不存在，无法恢复]\\n'; exit 1; fi";
        }
        var inner = $"\"${{SHELL:-/bin/bash}}\" -l -i -c {qAgent}; code=$?; printf \\\"\\n[命令已退出，状态 %s；Shell 仍然可用]\\n\\\" \\\"$code\\\"; exec \\\"${{SHELL:-/bin/bash}}\\\" -l -i";
        return $"cd -- {qDirectory} && if command -v tmux >/dev/null 2>&1; then " +
               $"(tmux has-session -t {session} 2>/dev/null || tmux new-session -d -s {session} -c {qDirectory} {ShellQuote(inner)}) && " +
               $"tmux set-option -t {session} mouse on && tmux attach-session -t {session}; " +
               $"else printf '\\n[提示] 未安装 tmux，断线后无法恢复会话\\n'; exec \"${{SHELL:-/bin/bash}}\" -l -i -c {qAgent}; fi";
    }

    private static string ShellQuote(string value) => "'" + value.Replace("'", "'\"'\"'") + "'";

    private static IntPtr BuildEnvironmentBlock(string savedPassword)
    {
        var variables = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (DictionaryEntry entry in Environment.GetEnvironmentVariables())
            variables[Convert.ToString(entry.Key) ?? ""] = Convert.ToString(entry.Value) ?? "";

        // The embedded xterm.js terminal supports this terminfo entry on the remote host.
        variables["TERM"] = "xterm-256color";
        if (!string.IsNullOrEmpty(savedPassword))
        {
            variables["SSH_ASKPASS"] = Process.GetCurrentProcess().MainModule?.FileName ??
                throw new InvalidOperationException("无法定位 Remote Dev Terminal 程序以自动填写 SSH 密码。");
            variables["SSH_ASKPASS_REQUIRE"] = "force";
            variables["DISPLAY"] = "rdt";
            variables["RDT_ASKPASS_PASSWORD"] = savedPassword;
            variables["RDT_ASKPASS_ALLOW_CONFIRM"] = "1";
        }

        var block = string.Join("\0", variables.Select(pair => pair.Key + "=" + pair.Value)) + "\0\0";
        return Marshal.StringToHGlobalUni(block);
    }

    internal static string QuoteArgument(string value)
    {
        if (value.Length > 0 && !value.Any(char.IsWhiteSpace) && value.IndexOf('"') < 0) return value;
        var result = new StringBuilder("\"");
        var slashes = 0;
        foreach (var c in value)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') result.Append('\\', slashes * 2 + 1).Append('"');
            else { result.Append('\\', slashes).Append(c); }
            slashes = 0;
        }
        result.Append('\\', slashes * 2).Append('"');
        return result.ToString();
    }

    private static void ThrowLastError(string message) => throw new Win32Exception(Marshal.GetLastWin32Error(), message);
    private static short ClampShort(int value, int minimum) => (short)Math.Min(short.MaxValue, Math.Max(minimum, value));

    [StructLayout(LayoutKind.Sequential)] private struct Coord { public short X; public short Y; }
    [StructLayout(LayoutKind.Sequential)] private struct SecurityAttributes { public int Length; public IntPtr SecurityDescriptor; [MarshalAs(UnmanagedType.Bool)] public bool InheritHandle; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        public int Cb; public string? Reserved; public string? Desktop; public string? Title;
        public int X; public int Y; public int XSize; public int YSize; public int XCountChars; public int YCountChars;
        public int FillAttribute; public uint Flags; public short ShowWindow; public short Reserved2; public IntPtr Reserved2Pointer;
        public IntPtr StdInput; public IntPtr StdOutput; public IntPtr StdError;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] private struct StartupInfoEx { public StartupInfo StartupInfo; public IntPtr AttributeList; }
    [StructLayout(LayoutKind.Sequential)] private struct ProcessInformation { public IntPtr Process; public IntPtr Thread; public uint ProcessId; public uint ThreadId; }

    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool CreatePipe(out SafeFileHandle readPipe, out SafeFileHandle writePipe, ref SecurityAttributes attributes, int size);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetHandleInformation(SafeFileHandle handle, uint mask, uint flags);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern int CreatePseudoConsole(Coord size, SafeFileHandle input, SafeFileHandle output, uint flags, out IntPtr pseudoConsole);
    [DllImport("kernel32.dll")] private static extern void ClosePseudoConsole(IntPtr pseudoConsole);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern int ResizePseudoConsole(IntPtr pseudoConsole, Coord size);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool InitializeProcThreadAttributeList(IntPtr attributeList, int attributeCount, uint flags, ref nuint size);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool UpdateProcThreadAttribute(IntPtr attributeList, uint flags, IntPtr attribute, IntPtr value, nuint size, IntPtr previousValue, IntPtr returnSize);
    [DllImport("kernel32.dll")] private static extern void DeleteProcThreadAttributeList(IntPtr attributeList);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CreateProcessW")]
    private static extern bool CreateProcess(string applicationName, StringBuilder commandLine, IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint creationFlags, IntPtr environment, string? currentDirectory, ref StartupInfoEx startupInfo, out ProcessInformation processInformation);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool ReadFile(SafeFileHandle file, byte[] buffer, uint bytesToRead, out uint bytesRead, IntPtr overlapped);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool WriteFile(SafeFileHandle file, byte[] buffer, uint bytesToWrite, out uint bytesWritten, IntPtr overlapped);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetExitCodeProcess(IntPtr process, out uint exitCode);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateProcess(IntPtr process, uint exitCode);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
