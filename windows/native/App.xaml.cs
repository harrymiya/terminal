using System.Runtime.InteropServices;
using System.Text;

namespace RemoteDevTerminal;

public partial class App : System.Windows.Application
{
    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        if (e.Args.Any(arg => string.Equals(arg, "--ssh-askpass", StringComparison.OrdinalIgnoreCase)))
        {
            var secret = Environment.GetEnvironmentVariable("RDT_ASKPASS_PASSWORD") ?? "";
            var bytes = Encoding.UTF8.GetBytes(secret + Environment.NewLine);
            var handle = GetStdHandle(-11);
            if (handle != IntPtr.Zero && handle != new IntPtr(-1)) WriteFile(handle, bytes, (uint)bytes.Length, out _, IntPtr.Zero);
            Shutdown();
            return;
        }
        if (e.Args.Any(arg => string.Equals(arg, "--mcp-stdio", StringComparison.OrdinalIgnoreCase)))
        {
            Environment.ExitCode = McpStdioBridge.Run();
            Shutdown();
            return;
        }
        base.OnStartup(e);
        new MainWindow().Show();
    }

    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr GetStdHandle(int handle);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool WriteFile(IntPtr handle, byte[] bytes, uint count, out uint written, IntPtr overlapped);
}
