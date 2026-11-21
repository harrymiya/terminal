using System.Net;
using System.Net.Sockets;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteDevTerminal;

internal sealed class McpServer : IDisposable
{
    private readonly MainWindow _window;
    private readonly CancellationTokenSource _stop = new();
    private readonly JsonArray _tools;
    private TcpListener? _listener;
    private string _discoveryPath = "";

    public int Port { get; private set; }

    public McpServer(MainWindow window)
    {
        _window = window;
        _tools = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "www", "mcp-tools.json"))) as JsonArray ?? new JsonArray();
    }

    public void Start()
    {
        var requested = int.TryParse(Environment.GetEnvironmentVariable("RDT_MCP_PORT"), out var port) ? port : 17333;
        for (var candidate = requested; candidate <= Math.Min(65535, requested + 32); candidate++)
        {
            try { _listener = new TcpListener(IPAddress.Loopback, candidate); _listener.Start(); Port = candidate; break; }
            catch (SocketException) { _listener?.Stop(); _listener = null; if (Environment.GetEnvironmentVariable("RDT_MCP_PORT") is not null) break; }
        }
        if (_listener is null) throw new InvalidOperationException("无法启动本机 MCP 服务（端口被占用）。");
        _discoveryPath = Path.Combine(Path.GetTempPath(), "remote-dev-terminal-mcp.json");
        File.WriteAllText(_discoveryPath, JsonSerializer.Serialize(new { url = $"http://127.0.0.1:{Port}/mcp", port = Port }));
        _ = Task.Run(AcceptLoopAsync);
    }

    private async Task AcceptLoopAsync()
    {
        while (!_stop.IsCancellationRequested && _listener is not null)
        {
            TcpClient client;
            try { client = await _listener.AcceptTcpClientAsync().ConfigureAwait(false); }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (_stop.IsCancellationRequested) { break; }
            _ = Task.Run(() => HandleClientAsync(client));
        }
    }

    private async Task HandleClientAsync(TcpClient client)
    {
        using (client)
        {
            try
            {
                var stream = client.GetStream();
                var request = await ReadRequestAsync(stream, _stop.Token).ConfigureAwait(false);
                if (request.Path != "/mcp" && request.Path != "/mcp/") { await WriteResponseAsync(stream, 404, "Not Found", "").ConfigureAwait(false); return; }
                if (request.Method == "GET") { await WriteResponseAsync(stream, 200, "OK", "{\"status\":\"ready\"}").ConfigureAwait(false); return; }
                if (request.Method != "POST") { await WriteResponseAsync(stream, 405, "Method Not Allowed", "").ConfigureAwait(false); return; }
                using var document = JsonDocument.Parse(request.Body);
                var root = document.RootElement;
                var method = root.TryGetProperty("method", out var methodValue) ? methodValue.GetString() ?? "" : "";
                var hasId = root.TryGetProperty("id", out var requestId);
                if (!hasId || method.StartsWith("notifications/", StringComparison.Ordinal))
                {
                    await WriteResponseAsync(stream, 202, "Accepted", "").ConfigureAwait(false); return;
                }
                object? result = method switch
                {
                    "initialize" => new { protocolVersion = "2024-11-05", capabilities = new { tools = new { listChanged = false } }, serverInfo = new { name = "remote-dev-terminal-windows", version = "1.0.0" } },
                    "ping" => new { },
                    "tools/list" => new { tools = _tools },
                    "tools/call" => await CallToolAsync(root).ConfigureAwait(false),
                    _ => throw new McpProtocolException(-32601, "Method not found: " + method)
                };
                var body = JsonSerializer.Serialize(new { jsonrpc = "2.0", id = requestId.Clone(), result });
                await WriteResponseAsync(stream, 200, "OK", body).ConfigureAwait(false);
            }
            catch (McpProtocolException ex)
            {
                try
                {
                    var body = JsonSerializer.Serialize(new { jsonrpc = "2.0", id = (object?)null, error = new { code = ex.Code, message = ex.Message } });
                    await WriteResponseAsync(client.GetStream(), 200, "OK", body).ConfigureAwait(false);
                }
                catch { }
            }
            catch (Exception ex)
            {
                try { await WriteResponseAsync(client.GetStream(), 400, "Bad Request", JsonSerializer.Serialize(new { error = ex.Message })).ConfigureAwait(false); } catch { }
            }
        }
    }

    private async Task<object> CallToolAsync(JsonElement request)
    {
        if (!request.TryGetProperty("params", out var parameters) || !parameters.TryGetProperty("name", out var nameValue))
            throw new McpProtocolException(-32602, "Missing tool name.");
        var name = nameValue.GetString() ?? "";
        var args = parameters.TryGetProperty("arguments", out var argsValue) ? argsValue.Clone() : JsonDocument.Parse("{}").RootElement.Clone();
        try
        {
            var result = await _window.InvokeMcpToolAsync(name, args).ConfigureAwait(false);
            return new { content = new[] { new { type = "text", text = result.GetRawText() } }, isError = false };
        }
        catch (Exception ex)
        {
            return new { content = new[] { new { type = "text", text = ex.Message } }, isError = true };
        }
    }

    private static async Task<(string Method, string Path, byte[] Body)> ReadRequestAsync(NetworkStream stream, CancellationToken cancellationToken)
    {
        var header = new List<byte>(); var one = new byte[1];
        while (header.Count < 64 * 1024)
        {
            var count = await stream.ReadAsync(one, 0, 1, cancellationToken).ConfigureAwait(false);
            if (count == 0) throw new IOException("Client disconnected.");
            header.Add(one[0]);
            var n = header.Count;
            if (n >= 4 && header[n - 4] == 13 && header[n - 3] == 10 && header[n - 2] == 13 && header[n - 1] == 10) break;
        }
        var lines = Encoding.ASCII.GetString(header.ToArray()).Split(new[] { "\r\n" }, StringSplitOptions.None);
        var first = lines[0].Split(' ');
        if (first.Length < 2) throw new InvalidDataException("Malformed HTTP request.");
        var length = 0;
        foreach (var line in lines.Skip(1))
            if (line.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase)) int.TryParse(line.Substring(15).Trim(), out length);
        if (length < 0 || length > 2 * 1024 * 1024) throw new InvalidDataException("Request body too large.");
        var body = new byte[length]; var offset = 0;
        while (offset < length)
        {
            var count = await stream.ReadAsync(body, offset, length - offset, cancellationToken).ConfigureAwait(false);
            if (count == 0) throw new IOException("Incomplete HTTP body.");
            offset += count;
        }
        return (first[0], first[1].Split('?')[0], body);
    }

    private static async Task WriteResponseAsync(NetworkStream stream, int status, string reason, string body)
    {
        var bytes = Encoding.UTF8.GetBytes(body);
        var header = Encoding.ASCII.GetBytes($"HTTP/1.1 {status} {reason}\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {bytes.Length}\r\nConnection: close\r\nAccess-Control-Allow-Origin: null\r\n\r\n");
        await stream.WriteAsync(header, 0, header.Length).ConfigureAwait(false);
        if (bytes.Length > 0) await stream.WriteAsync(bytes, 0, bytes.Length).ConfigureAwait(false);
    }

    public void Dispose()
    {
        _stop.Cancel();
        _listener?.Stop();
        if (_discoveryPath.Length > 0) try { File.Delete(_discoveryPath); } catch { }
        _stop.Dispose();
    }

    private sealed class McpProtocolException : Exception
    {
        public int Code { get; }
        public McpProtocolException(int code, string message) : base(message) => Code = code;
    }
}
