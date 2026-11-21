using System.Net;
using System.IO;
using System.Text;
using System.Text.Json;

namespace RemoteDevTerminal;

internal static class McpStdioBridge
{
    public static int Run()
    {
        var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));
        var output = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true };
        var url = Environment.GetEnvironmentVariable("RDT_MCP_URL");
        if (string.IsNullOrWhiteSpace(url))
        {
            var discovery = Path.Combine(Path.GetTempPath(), "remote-dev-terminal-mcp.json");
            if (File.Exists(discovery))
            {
                try { using var doc = JsonDocument.Parse(File.ReadAllText(discovery)); url = doc.RootElement.GetProperty("url").GetString(); }
                catch { }
            }
        }
        if (string.IsNullOrWhiteSpace(url))
        {
            Console.Error.WriteLine("Remote Dev Terminal MCP server is not running.");
            return 1;
        }
        string? line;
        while ((line = input.ReadLine()) is not null)
        {
            if (line.Length == 0) continue;
            try
            {
                using var request = JsonDocument.Parse(line);
                if (request.RootElement.TryGetProperty("method", out var method) && method.GetString()?.StartsWith("notifications/", StringComparison.Ordinal) == true) continue;
                var http = (HttpWebRequest)WebRequest.Create(url);
                http.Method = "POST"; http.ContentType = "application/json"; http.Timeout = 120_000;
                var bytes = Encoding.UTF8.GetBytes(line);
                using (var stream = http.GetRequestStream()) stream.Write(bytes, 0, bytes.Length);
                using var response = (HttpWebResponse)http.GetResponse();
                using var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8);
                var reply = reader.ReadToEnd();
                if (!string.IsNullOrWhiteSpace(reply)) output.WriteLine(reply);
            }
            catch (Exception ex)
            {
                var id = "null";
                try { using var doc = JsonDocument.Parse(line); if (doc.RootElement.TryGetProperty("id", out var value)) id = value.GetRawText(); }
                catch { }
                output.WriteLine("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32000,\"message\":" + JsonSerializer.Serialize(ex.Message) + "}}");
            }
        }
        return 0;
    }
}
