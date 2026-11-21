using System.Runtime.InteropServices;
using System.Text;

namespace RemoteDevTerminal;

public partial class App : System.Windows.Application
{
    protected override void OnStartup(System.Windows.StartupEventArgs e)
    {
        var askpassPassword = Environment.GetEnvironmentVariable("RDT_ASKPASS_PASSWORD");
        if (e.Args.Any(arg => string.Equals(arg, "--ssh-askpass", StringComparison.OrdinalIgnoreCase)) ||
            askpassPassword is not null)
        {
            var prompt = e.Args.FirstOrDefault(arg => !string.Equals(arg, "--ssh-askpass", StringComparison.OrdinalIgnoreCase)) ?? "";
            var confirm = Environment.GetEnvironmentVariable("SSH_ASKPASS_PROMPT") == "confirm";
            if (confirm)
            {
                if (Environment.GetEnvironmentVariable("RDT_ASKPASS_ALLOW_CONFIRM") != "1" ||
                    System.Windows.MessageBox.Show(prompt, "SSH 主机指纹",
                        System.Windows.MessageBoxButton.YesNo, System.Windows.MessageBoxImage.Question,
                        System.Windows.MessageBoxResult.No) != System.Windows.MessageBoxResult.Yes)
                {
                    Shutdown(1);
                    return;
                }
                Shutdown(WriteAskPassAnswer("yes") ? 0 : 1);
                return;
            }
            if (string.IsNullOrEmpty(askpassPassword) || prompt.IndexOf("passphrase", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                Shutdown(1);
                return;
            }
            Shutdown(WriteAskPassAnswer(askpassPassword) ? 0 : 1);
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

    private static bool WriteAskPassAnswer(string answer)
    {
        var bytes = Encoding.UTF8.GetBytes(answer + "\n");
        try
        {
            var handle = GetStdHandle(-11);
            return handle != IntPtr.Zero && handle != new IntPtr(-1)
                && WriteFile(handle, bytes, (uint)bytes.Length, out var written, IntPtr.Zero) && written == bytes.Length;
        }
        finally { Array.Clear(bytes, 0, bytes.Length); }
    }

    [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr GetStdHandle(int handle);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool WriteFile(IntPtr handle, byte[] bytes, uint count, out uint written, IntPtr overlapped);
}
