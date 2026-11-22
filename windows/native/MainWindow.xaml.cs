using Microsoft.Web.WebView2.Core;
using System.IO;
using System.Diagnostics;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Net.Sockets;
using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Globalization;
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows;
using System.Windows.Threading;

namespace RemoteDevTerminal;

public partial class MainWindow : Window
{
    private readonly Dictionary<string, SshTerminalSession> _sessions = new(StringComparer.Ordinal);
    private readonly Dictionary<string, JsonObject> _sessionHosts = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonElement>> _mcpCalls = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, Lazy<(string Address, int Port)>> _sshEndpointCache = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, string> _announcedSshEndpoints = new(StringComparer.Ordinal);
    private McpServer? _mcpServer;
    private bool _ready;
    private bool _syncInFlight;
    private bool _metricsInFlight;
    private bool _quotaInFlight;
    private readonly DispatcherTimer _syncTimer = new();
    private readonly DispatcherTimer _metricsTimer = new();
    private readonly DispatcherTimer _quotaTimer = new();

    public MainWindow() => InitializeComponent();

    private async void Window_Loaded(object sender, RoutedEventArgs e)
    {
        try
        {
            await Browser.EnsureCoreWebView2Async();
            var core = Browser.CoreWebView2;
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.IsStatusBarEnabled = false;
            core.Settings.IsZoomControlEnabled = false;
            core.SetVirtualHostNameToFolderMapping("rdt.local", Path.Combine(AppContext.BaseDirectory, "www"), CoreWebView2HostResourceAccessKind.DenyCors);
            core.NavigationStarting += (_, args) =>
            {
                if (!args.Uri.StartsWith("https://rdt.local/", StringComparison.OrdinalIgnoreCase)) args.Cancel = true;
            };
            core.WebMessageReceived += Core_WebMessageReceived;
            core.NavigationCompleted += (_, args) =>
            {
                if (!args.IsSuccess) return;
                _ready = true;
                SendConfig();
                ConfigureSyncTimer();
                ConfigureRefreshTimers();
                StartMcpServer();
            };
            Browser.Source = new Uri("https://rdt.local/index.html");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this,
                "Remote Dev Terminal 无法启动 WebView2。请检查 Microsoft Edge WebView2 Runtime 是否可用。\n\n" + ex.Message,
                "Remote Dev Terminal", MessageBoxButton.OK, MessageBoxImage.Error);
            Close();
        }
    }

    private void Core_WebMessageReceived(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        try
        {
            using var document = JsonDocument.Parse(e.WebMessageAsJson);
            var message = document.RootElement;
            var action = Text(message, "action");
            switch (action)
            {
                case "load-config": SendConfig(); break;
                case "save-config": SaveConfig(message); break;
                case "connect": Connect(message); break;
                case "input": Input(message); break;
                case "resize": Resize(message); break;
                case "close": CloseSession(Text(message, "id")); break;
                case "clear-host-password": ClearHostPassword(message); break;
                case "upload-image": _ = UploadImageAsync(message); break;
                case "upload-image-path": _ = UploadImagePathAsync(message); break;
                case "get-ssh-public-key": _ = GetSshPublicKeyAsync(message); break;
                case "sync-auth": _ = SyncAuthAsync(message); break;
                case "sync-now": _ = SyncNowAsync(manual: true); break;
                case "mcp-info": ShowMcpInfo(); break;
                case "delete-host-profile": _ = DeleteHostProfileAsync(Text(message, "host_id")); break;
                case "session-sync": _ = SyncSessionAsync(message); break;
                case "browse-directories": _ = BrowseDirectoriesAsync(message); break;
                case "admin-users": _ = GetAdminUsersAsync(); break;
                case "admin-user-save": _ = SaveAdminUserAsync(message); break;
                case "admin-user-delete": _ = DeleteAdminUserAsync(Number(message, "user_id", 0)); break;
                case "mcp-result": ResolveMcpCall(message); break;
            }
        }
        catch (Exception ex) { Send(new { type = "notice", message = ex.Message }); }
    }

    private void SendConfig()
    {
        if (!_ready) return;
        try
        {
            var config = ConfigStore.Load();
            if (config["hosts"] is JsonArray hosts)
            {
                foreach (var node in hosts.OfType<JsonObject>())
                {
                    node["credential_saved"] = ReadHostPassword(Text(node, "credential_id"), Text(node, "username")).Length > 0;
                }
            }
            Send(new { type = "config", config });
        }
        catch (Exception ex)
        {
            Send(new { type = "notice", message = ex.Message });
            Send(new { type = "config", config = new JsonObject { ["hosts"] = new JsonArray(), ["agents"] = new JsonArray() } });
        }
    }

    private void SaveConfig(JsonElement message)
    {
        var node = JsonNode.Parse(message.GetProperty("config").GetRawText()) as JsonObject
            ?? throw new InvalidDataException("收到的配置格式无效。");
        if (node["hosts"] is JsonArray hosts)
        {
            var previousHosts = (ConfigStore.Load()["hosts"] as JsonArray ?? new JsonArray()).OfType<JsonObject>().ToList();
            foreach (var host in hosts.OfType<JsonObject>())
            {
                var previous = previousHosts.FirstOrDefault(item => Text(item, "id") == Text(host, "id"));
                host.Remove("credential_saved");
                host.Remove("credential_id");
                if (previous?["credential_id"] is { } reference) host["credential_id"] = reference.DeepClone();
            }
            if (message.TryGetProperty("ssh_password_change", out var change))
            {
                var host = hosts.OfType<JsonObject>().FirstOrDefault(item => Text(item, "id") == Text(change, "host_id"))
                    ?? throw new InvalidDataException("找不到要保存 SSH 密码的主机。");
                var credentialId = Text(host, "credential_id");
                if (credentialId.Length == 0) credentialId = Text(host, "id");
                if (credentialId.Length == 0) throw new InvalidDataException("主机凭据 ID 为空。");
                var password = Text(change, "password");
                if (password.Length == 0)
                {
                    CredentialStore.Delete("RemoteDevTerminal:host:" + credentialId);
                    host.Remove("credential_id");
                }
                else
                {
                    CredentialStore.Write("RemoteDevTerminal:host:" + credentialId, Text(host, "username"), password);
                    host["credential_id"] = credentialId;
                }
            }
        }
        ConfigStore.Save(node);
        Send(new { type = "saved" });
        if (message.TryGetProperty("ssh_password_change", out _)) SendConfig();
        ConfigureSyncTimer();
        ConfigureRefreshTimers();
    }

    private void Connect(JsonElement message)
    {
        var id = Text(message, "id");
        if (id.Length is < 8 or > 64 || id.Any(c => !Uri.IsHexDigit(c)))
            throw new ArgumentException("终端 ID 无效。");
        CloseSession(id);

        var requestedHost = message.GetProperty("host");
        var currentHost = (ConfigStore.Load()["hosts"] as JsonArray)?.OfType<JsonObject>()
            .FirstOrDefault(item => Text(item, "id") == Text(requestedHost, "id"));
        using var currentHostDocument = currentHost is null ? null : JsonDocument.Parse(currentHost.ToJsonString());
        var host = currentHostDocument?.RootElement ?? requestedHost;
        var workspace = message.GetProperty("workspace");
        var agent = message.GetProperty("agent");
        var username = Text(host, "username");
        var (address, port) = ResolveSshEndpoint(host);
        Send(new { type = "endpoint-selected", id, address, port });

        var session = new SshTerminalSession(id);
        var hostNode = JsonNode.Parse(host.GetRawText()) as JsonObject ?? new JsonObject();
        _sessionHosts[id] = hostNode;
        var savedPassword = currentHost is not null ? ReadHostPassword(Text(currentHost, "credential_id"), username) : "";
        session.DataReceived += bytes => Dispatcher.BeginInvoke(() =>
        {
            if (_sessions.TryGetValue(id, out var current) && ReferenceEquals(current, session))
                Send(new { type = "output", id, data = Convert.ToBase64String(bytes) });
        });
        session.Exited += code => Dispatcher.BeginInvoke(() =>
        {
            if (_sessions.TryGetValue(id, out var current) && ReferenceEquals(current, session))
            {
                _sessions.Remove(id);
                _sessionHosts.Remove(id);
                session.Dispose();
                Send(new { type = "closed", id, code });
            }
        }, DispatcherPriority.ContextIdle);

        _sessions[id] = session;
        try
        {
            session.Start(username, address, port, Text(workspace, "path"), Text(agent, "command"),
                Number(message, "cols", 100), Number(message, "rows", 30), savedPassword);
            Send(new { type = "connected", id });
        }
        catch (Exception ex)
        {
            _sessions.Remove(id);
            _sessionHosts.Remove(id);
            session.Dispose();
            Send(new { type = "error", id, message = ex.Message });
        }
    }

    private void Input(JsonElement message)
    {
        var id = Text(message, "id");
        if (_sessions.TryGetValue(id, out var session)) session.Write(Text(message, "data"));
    }

    private void Resize(JsonElement message)
    {
        var id = Text(message, "id");
        if (_sessions.TryGetValue(id, out var session))
            session.Resize(Number(message, "cols", 100), Number(message, "rows", 30));
    }

    private void CloseSession(string id)
    {
        if (!_sessions.TryGetValue(id, out var session)) return;
        _sessions.Remove(id);
        _sessionHosts.Remove(id);
        session.Dispose();
    }

    private static string ReadHostPassword(string credentialId, string username)
    {
        if (credentialId.Length == 0) return "";
        var credential = CredentialStore.Read("RemoteDevTerminal:host:" + credentialId);
        return credential is { } saved && saved.UserName == username ? saved.Secret : "";
    }

    private void ClearHostPassword(JsonElement message)
    {
        var id = Text(message, "credential_id");
        if (id.Length > 0) CredentialStore.Delete("RemoteDevTerminal:host:" + id);
    }

    private async Task SyncAuthAsync(JsonElement message)
    {
        var mode = Text(message, "mode");
        try
        {
            var config = ConfigStore.Load();
            var url = config["sync_url"]?.GetValue<string>()?.TrimEnd('/') ?? "";
            if (url.Length == 0) throw new InvalidOperationException("请先填写同步服务地址并保存设置。");
            if (mode == "login")
            {
                var username = Text(message, "username").Trim();
                var password = Text(message, "password");
                var response = await SyncRequestAsync(url + "/v1/auth/login", "POST", null,
                    new { username, password }).ConfigureAwait(false);
                using var document = JsonDocument.Parse(response);
                var root = document.RootElement;
                var token = Text(root, "access_token");
                if (token.Length == 0) throw new InvalidDataException("同步服务没有返回登录令牌。");
                config["sync_token"] = token;
                config["sync_username"] = Text(root, "username");
                config["sync_is_admin"] = root.TryGetProperty("is_admin", out var admin) && admin.ValueKind == JsonValueKind.True;
                ConfigStore.Save(config);
                CredentialStore.Write("RemoteDevTerminal:sync:" + Text(root, "username").ToLowerInvariant(), Text(root, "username"), password);
                Dispatcher.BeginInvoke(() => { ConfigureSyncTimer(); Send(new { type = "sync-auth-result", success = true, auth = true, message = "同步账号登录成功。" }); SendConfig(); });
            }
            else
            {
                var token = config["sync_token"]?.GetValue<string>() ?? "";
                if (token.Length > 0) await SyncRequestAsync(url + "/v1/auth/logout", "POST", token, new { }).ConfigureAwait(false);
                var username = config["sync_username"]?.GetValue<string>() ?? "";
                if (username.Length > 0) CredentialStore.Delete("RemoteDevTerminal:sync:" + username.ToLowerInvariant());
                config["sync_token"] = ""; config["sync_username"] = ""; config["sync_is_admin"] = false;
                ConfigStore.Save(config);
                Dispatcher.BeginInvoke(() => { ConfigureSyncTimer(); Send(new { type = "sync-auth-result", success = true, auth = true, message = "已退出同步账号。" }); SendConfig(); });
            }
        }
        catch (Exception ex) { Dispatcher.BeginInvoke(() => Send(new { type = "sync-auth-result", success = false, auth = true, message = ex.Message })); }
    }

    private async Task SyncNowAsync(bool manual)
    {
        if (_syncInFlight) return;
        _syncInFlight = true;
        try
        {
            var config = ConfigStore.Load();
            if (Bool(config, "offline_mode")) throw new InvalidOperationException("当前处于离线模式，已跳过跨设备同步。");
            var url = Text(config, "sync_url").TrimEnd('/');
            var token = Text(config, "sync_token");
            if (url.Length == 0 || token.Length == 0) throw new InvalidOperationException("请先填写同步服务地址并登录账号。");
            await SynchronizeAccountAsync(config, url, token).ConfigureAwait(false);
            if (manual) Dispatcher.BeginInvoke(() => Send(new { type = "sync-auth-result", success = true, manual = true, message = "主机、Agent、工作区和共享会话已同步。" }));
            Dispatcher.BeginInvoke(SendConfig);
        }
        catch (Exception ex)
        {
            Dispatcher.BeginInvoke(() =>
            {
                if (ex is SyncHttpException http && http.StatusCode == 401)
                {
                    var expired = ConfigStore.Load(); expired["sync_token"] = ""; ConfigStore.Save(expired); ConfigureSyncTimer();
                }
                Send(new { type = "sync-auth-result", success = false, manual, message = ex.Message });
            });
        }
        finally { _syncInFlight = false; }
    }

    private static async Task<string> SyncRequestAsync(string url, string method, string? token, object? payload)
    {
        var request = (HttpWebRequest)WebRequest.Create(url);
        request.Method = method; request.Timeout = 10000; request.ReadWriteTimeout = 10000;
        request.ContentType = "application/json";
        if (!string.IsNullOrEmpty(token)) request.Headers[HttpRequestHeader.Authorization] = "Bearer " + token;
        if (payload is not null)
        {
            var bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload));
            request.ContentLength = bytes.Length;
            using var stream = await request.GetRequestStreamAsync().ConfigureAwait(false);
            await stream.WriteAsync(bytes, 0, bytes.Length).ConfigureAwait(false);
        }
        try
        {
            using var response = (HttpWebResponse)await request.GetResponseAsync().ConfigureAwait(false);
            using var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8);
            return await reader.ReadToEndAsync().ConfigureAwait(false);
        }
        catch (WebException ex) when (ex.Response is HttpWebResponse response)
        {
            using (response)
            using (var reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8))
            {
                var body = await reader.ReadToEndAsync().ConfigureAwait(false);
                var errorMessage = body.Length == 0 ? ex.Message : body;
                try { using var document = JsonDocument.Parse(body); if (document.RootElement.TryGetProperty("error", out var error)) errorMessage = error.GetString() ?? errorMessage; }
                catch (JsonException) { }
                int? revision = null;
                try
                {
                    using var document = JsonDocument.Parse(body);
                    if (document.RootElement.TryGetProperty("revision", out var revisionValue) && revisionValue.TryGetInt32(out var parsedRevision)) revision = parsedRevision;
                }
                catch (JsonException) { }
                throw new SyncHttpException((int)response.StatusCode, errorMessage, ex, revision);
            }
        }
    }

    private void ConfigureSyncTimer()
    {
        try
        {
            var config = ConfigStore.Load();
            if (!Bool(config, "auto_sync_enabled") || Bool(config, "offline_mode") || Text(config, "sync_token").Length == 0)
            {
                _syncTimer.Stop();
                return;
            }
            var seconds = Math.Max(5, Math.Min(300, Int(config["sync_interval_seconds"], 5)));
            _syncTimer.Interval = TimeSpan.FromSeconds(seconds);
            if (!_syncTimer.IsEnabled)
            {
                _syncTimer.Tick -= SyncTimer_Tick;
                _syncTimer.Tick += SyncTimer_Tick;
                _syncTimer.Start();
            }
        }
        catch { _syncTimer.Stop(); }
    }

    private async void SyncTimer_Tick(object? sender, EventArgs e) => await SyncNowAsync(manual: false);

    private void ConfigureRefreshTimers()
    {
        var config = ConfigStore.Load();
        var endpointProbes = ProbeConfiguredSshEndpointsAsync(config["hosts"] as JsonArray);
        _metricsTimer.Interval = TimeSpan.FromSeconds(Math.Max(5, Math.Min(300, Int(config["host_metrics_interval_seconds"], 5))));
        _quotaTimer.Interval = TimeSpan.FromSeconds(Math.Max(15, Math.Min(300, Int(config["agent_quota_interval_seconds"], 60))));
        if (!_metricsTimer.IsEnabled) { _metricsTimer.Tick += MetricsTimer_Tick; _metricsTimer.Start(); }
        if (!_quotaTimer.IsEnabled) { _quotaTimer.Tick += QuotaTimer_Tick; _quotaTimer.Start(); }
        _ = RefreshInitialDataAsync(endpointProbes);
    }

    private Task ProbeConfiguredSshEndpointsAsync(JsonArray? hosts)
    {
        var jobs = (hosts?.OfType<JsonObject>() ?? Enumerable.Empty<JsonObject>()).Select(host =>
        {
            var serializedHost = host.ToJsonString();
            return Task.Run(() =>
            {
                try
                {
                    using var document = JsonDocument.Parse(serializedHost);
                    ResolveSshEndpoint(document.RootElement);
                }
                catch { }
            });
        }).ToArray();
        return Task.WhenAll(jobs);
    }

    private async Task RefreshInitialDataAsync(Task endpointProbes)
    {
        await endpointProbes.ConfigureAwait(false);
        await Task.WhenAll(RefreshHostMetricsAsync(), RefreshAgentQuotasAsync()).ConfigureAwait(false);
    }

    private async void MetricsTimer_Tick(object? sender, EventArgs e) => await RefreshHostMetricsAsync();
    private async void QuotaTimer_Tick(object? sender, EventArgs e) => await RefreshAgentQuotasAsync();

    private async Task RefreshHostMetricsAsync()
    {
        if (_metricsInFlight || !_ready) return;
        _metricsInFlight = true;
        try
        {
            var config = ConfigStore.Load();
            var hosts = config["hosts"] as JsonArray ?? new JsonArray();
            var jobs = hosts.OfType<JsonObject>().Select(async host =>
            {
                try
                {
                    var remote = "LC_ALL=C; export LC_ALL; if [ -r /proc/meminfo ] && [ -r /proc/stat ]; then mem=$(awk '/^MemTotal:/ {t=$2} /^MemAvailable:/ {a=$2} END {if(t>0 && a!=\"\") printf \"%.2f\",100*a/t}' /proc/meminfo); first=$(awk '/^cpu / {print $2,$3,$4,$5,$6,$7,$8,$9; exit}' /proc/stat); sleep 1; second=$(awk '/^cpu / {print $2,$3,$4,$5,$6,$7,$8,$9; exit}' /proc/stat); cpu=$(printf '%s\\n%s\\n' \"$first\" \"$second\" | awk 'NR==1 {for(i=1;i<=NF;i++) a[i]=$i; next} {for(i=1;i<=NF;i++) d[i]=$i-a[i]; total=0; for(i=1;i<=NF;i++) total+=d[i]; if(total>0) printf \"%.2f\",100*(total-d[4]-d[5])/total}'); fi; printf '\\nRDT_METRICS %s %s\\n' \"$mem\" \"$cpu\"";
                    var result = await RunSshCommandAsync(host, remote, null, 15_000).ConfigureAwait(false);
                    if (result.ExitCode != 0) return;
                    var line = result.Output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries).LastOrDefault(x => x.StartsWith("RDT_METRICS ", StringComparison.Ordinal));
                    if (line is null) return;
                    var values = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
                    if (values.Length != 3 || !double.TryParse(values[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var memory) || !double.TryParse(values[2], NumberStyles.Float, CultureInfo.InvariantCulture, out var cpu) || memory is < 0 or > 100 || cpu is < 0 or > 100) return;
                    var hostId = Text(host, "id");
                    Dispatcher.BeginInvoke(() => Send(new { type = "host-metrics", host_id = hostId, memory, cpu }));
                }
                catch { }
            }).ToArray();
            await Task.WhenAll(jobs).ConfigureAwait(false);
        }
        finally { _metricsInFlight = false; }
    }

    private async Task RefreshAgentQuotasAsync()
    {
        if (_quotaInFlight || !_ready) return;
        _quotaInFlight = true;
        try
        {
            var config = ConfigStore.Load();
            var hostList = config["hosts"] as JsonArray;
            var host = hostList?.OfType<JsonObject>().FirstOrDefault(item => Text(item, "id") == Text(config, "current_host_id")) ?? hostList?.OfType<JsonObject>().FirstOrDefault();
            if (host is null) return;
            var hostId = Text(host, "id");
            var hostAgents = host["agents"] as JsonArray;
            var agents = hostAgents is { Count: > 0 } ? hostAgents : config["agents"] as JsonArray ?? new JsonArray();
            using var throttle = new SemaphoreSlim(4);
            var jobs = agents.OfType<JsonObject>().Select(async agent =>
            {
                var command = Text(agent, "command");
                var firstWord = Regex.Match(command.TrimStart(), "^\"([^\"]+)\"|^'([^']+)'|^([^\\s\"']+)");
                if (!firstWord.Success) return;
                var executable = firstWord.Value.Trim('"', '\'');
                var name = executable.Replace('\\', '/').Split('/').Last().ToLowerInvariant();
                if (name is not ("codex" or "codex2" or "codx" or "agy" or "agy2")) return;
                string? error = null;
                JsonArray? quota = null;
                await throttle.WaitAsync().ConfigureAwait(false);
                try
                {
                    quota = name is "codex" or "codex2" or "codx"
                        ? await ReadCodexQuotaAsync(host, executable).ConfigureAwait(false)
                        : await ReadAgyQuotaAsync(host, executable).ConfigureAwait(false);
                    if (quota is null) error = "未获取到额度，请检查 SSH 连接和远端 Agent 登录状态。";
                }
                catch (Exception ex) { error = ex.Message; }
                finally { throttle.Release(); }
                Dispatcher.BeginInvoke(() => Send(new { type = "agent-quota", host_id = hostId, command, windows = quota ?? new JsonArray(), error }));
            }).ToArray();
            await Task.WhenAll(jobs).ConfigureAwait(false);
        }
        finally { _quotaInFlight = false; }
    }

    private async Task<JsonArray?> ReadAgyQuotaAsync(JsonObject host, string executable)
    {
        var remote = BuildQuotaCommand(executable, "-p \"/usage\" --output-format json");
        var result = await RunSshCommandAsync(host, remote, null, 30_000).ConfigureAwait(false);
        if (result.ExitCode != 0) return null;
        using var doc = ParseFirstJsonObject(result.Output);
        if (doc is null) return null;
        if (!doc.RootElement.TryGetProperty("command", out var commandResult) || !commandResult.TryGetProperty("data", out var data) || !data.TryGetProperty("groups", out var groups)) return null;
        var windows = new Dictionary<int, JsonObject>();
        foreach (var group in groups.EnumerateArray())
        {
            var geminiGroup = Text(group, "name").Trim().Equals("Gemini Models", StringComparison.OrdinalIgnoreCase);
            if (!group.TryGetProperty("buckets", out var buckets)) continue;
            foreach (var bucket in buckets.EnumerateArray())
            {
                var id = Text(bucket, "id").ToLowerInvariant(); if (!id.Contains("gemini")) continue;
                var minutes = id.Contains("weekly") || id.Contains("7d") ? 10080 : id.Contains("5h") || id.Contains("session") ? 300 : 0;
                if (minutes == 0 || minutes == 10080 && !geminiGroup || !bucket.TryGetProperty("remaining_fraction", out var value) || !value.TryGetDouble(out var fraction)) continue;
                var remaining = Math.Max(0, Math.Min(100, (int)(fraction * 100)));
                var reset = bucket.TryGetProperty("reset_time", out var resetTime) ? FormatReset(resetTime.GetString()) : "";
                windows[minutes] = new JsonObject { ["label"] = minutes == 10080 ? "7d" : "5h", ["remaining"] = remaining, ["reset"] = reset };
            }
        }
        var resultArray = new JsonArray(); foreach (var pair in windows.OrderBy(pair => pair.Key)) resultArray.Add(pair.Value);
        return resultArray.Count > 0 ? resultArray : null;
    }

    private async Task<JsonArray?> ReadCodexQuotaAsync(JsonObject host, string executable)
    {
        var remote = BuildQuotaCommand(executable, "app-server --listen stdio://");
        var start = CreateSshStartInfo(host, remote);
        using var process = new Process { StartInfo = start };
        if (!process.Start()) return null;
        _ = process.StandardError.ReadToEndAsync();
        try
        {
            process.StandardInput.WriteLine("{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"remote_dev_terminal\",\"version\":\"1.0\"}}}");
            process.StandardInput.Flush();
            using var initialized = await ReadJsonLineAsync(process, 1, 15_000).ConfigureAwait(false);
            if (initialized is null || initialized.RootElement.TryGetProperty("error", out _)) return null;
            process.StandardInput.WriteLine("{\"method\":\"initialized\",\"params\":{}}");
            process.StandardInput.WriteLine("{\"id\":2,\"method\":\"account/rateLimits/read\",\"params\":{}}");
            process.StandardInput.Flush();
            using var response = await ReadJsonLineAsync(process, 2, 15_000).ConfigureAwait(false);
            if (response is null || !response.RootElement.TryGetProperty("result", out var result) || !result.TryGetProperty("rateLimits", out var limits)) return null;
            var windows = new List<(int Minutes, int Remaining, string Reset)>();
            foreach (var name in new[] { "primary", "secondary" })
            {
                if (limits.ValueKind != JsonValueKind.Object || !limits.TryGetProperty(name, out var window) ||
                    window.ValueKind != JsonValueKind.Object ||
                    !window.TryGetProperty("windowDurationMins", out var mins) || mins.ValueKind != JsonValueKind.Number || !mins.TryGetInt32(out var minutes) ||
                    !window.TryGetProperty("usedPercent", out var used) || used.ValueKind != JsonValueKind.Number || !used.TryGetDouble(out var percent)) continue;
                var remaining = Math.Max(0, Math.Min(100, 100 - (int)percent));
                var reset = window.TryGetProperty("resetsAt", out var resets) && resets.ValueKind == JsonValueKind.Number &&
                    resets.TryGetInt64(out var timestamp) && timestamp is > 0 and <= 253402300799L
                    ? FormatReset(DateTimeOffset.FromUnixTimeSeconds(timestamp).ToString("O", CultureInfo.InvariantCulture)) : "";
                windows.Add((minutes, remaining, reset));
            }
            var chosen = new List<JsonObject>();
            var hourly = windows.Where(window => window.Minutes > 0 && window.Minutes < 1440).OrderBy(window => window.Minutes).FirstOrDefault();
            if (hourly.Minutes > 0) chosen.Add(new JsonObject { ["label"] = hourly.Minutes % 60 == 0 ? $"{hourly.Minutes / 60}h" : $"{hourly.Minutes}min", ["remaining"] = hourly.Remaining, ["reset"] = hourly.Reset });
            var weekly = windows.FirstOrDefault(window => window.Minutes == 10080);
            if (weekly.Minutes > 0) chosen.Add(new JsonObject { ["label"] = "7d", ["remaining"] = weekly.Remaining, ["reset"] = weekly.Reset });
            var array = new JsonArray(); foreach (var item in chosen) array.Add(item); return array.Count > 0 ? array : null;
        }
        finally { try { process.Kill(); } catch { } }
    }

    private static string BuildQuotaCommand(string executable, string arguments)
    {
        // Keep simple command names unquoted so the interactive login shell can expand aliases.
        var invocation = Regex.IsMatch(executable, "^[a-zA-Z0-9_]+$") ? executable : QuoteShell(executable);
        return "\"${SHELL:-/bin/bash}\" -l -i -c " + QuoteShell($"{invocation} {arguments}");
    }

    private static async Task<JsonDocument?> ReadJsonLineAsync(Process process, int expectedId, int timeoutMs)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < deadline)
        {
            var read = process.StandardOutput.ReadLineAsync();
            var wait = Task.Delay(Math.Max(1, (int)(deadline - DateTime.UtcNow).TotalMilliseconds));
            if (await Task.WhenAny(read, wait).ConfigureAwait(false) != read) return null;
            var line = await read.ConfigureAwait(false); if (line is null) return null;
            try
            {
                var document = JsonDocument.Parse(line);
                if (document.RootElement.ValueKind == JsonValueKind.Object &&
                    document.RootElement.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.Number &&
                    id.TryGetInt32(out var value) && value == expectedId) return document;
                document.Dispose();
            }
            catch (JsonException) { }
        }
        return null;
    }

    private static JsonDocument? ParseFirstJsonObject(string text)
    {
        for (var start = 0; start < text.Length; start++)
        {
            if (text[start] != '{') continue;
            var depth = 0;
            var inString = false;
            var escaped = false;
            for (var index = start; index < text.Length; index++)
            {
                var character = text[index];
                if (inString)
                {
                    if (escaped) escaped = false;
                    else if (character == '\\') escaped = true;
                    else if (character == '"') inString = false;
                    continue;
                }
                if (character == '"') inString = true;
                else if (character == '{') depth++;
                else if (character == '}' && --depth == 0)
                {
                    try { return JsonDocument.Parse(text.Substring(start, index - start + 1)); }
                    catch (JsonException) { break; }
                }
            }
        }
        return null;
    }

    private static string FormatReset(string? timestamp)
    {
        if (string.IsNullOrWhiteSpace(timestamp)) return "";
        if (!DateTimeOffset.TryParse(timestamp, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var reset)) return "";
        var delta = reset - DateTimeOffset.UtcNow;
        return delta.TotalDays >= 1 ? Math.Floor(delta.TotalDays).ToString(CultureInfo.InvariantCulture) + "d" : delta.TotalSeconds > 0 ? "<1d" : "";
    }

    private async Task SynchronizeAccountAsync(JsonObject config, string url, string token)
    {
        using var me = JsonDocument.Parse(await SyncRequestAsync(url + "/v1/auth/me", "GET", token, null).ConfigureAwait(false));
        var username = Text(me.RootElement, "username");
        config["sync_username"] = username;
        config["sync_is_admin"] = me.RootElement.TryGetProperty("is_admin", out var isAdmin) && isAdmin.ValueKind == JsonValueKind.True;
        var previousAgents = (config["agents"] as JsonArray ?? new JsonArray()).OfType<JsonObject>().Select(Clone).ToList();
        var syncedAgents = await SyncAgentsAsync(config, url, token, username).ConfigureAwait(false);
        foreach (var host in (config["hosts"] as JsonArray ?? new JsonArray()).OfType<JsonObject>())
        {
            var oldIndex = Int(host["selected_agent"], 0);
            if (oldIndex < 0 || oldIndex >= previousAgents.Count) continue;
            var command = Text(previousAgents[oldIndex], "command");
            var newIndex = syncedAgents.OfType<JsonObject>().ToList().FindIndex(agent => Text(agent, "command") == command);
            if (newIndex >= 0) host["selected_agent"] = newIndex;
        }
        config["agents"] = syncedAgents;

        var dirtyHosts = StringSet(config["dirty_host_profiles"]);
        var deletedHosts = StringSet(config["deleted_host_profiles"]);
        foreach (var hostId in deletedHosts)
            await SyncRequestAsync(url + "/v1/hosts/" + Uri.EscapeDataString(hostId), "DELETE", token, null).ConfigureAwait(false);

        var hostResponse = ParseObject(await SyncRequestAsync(url + "/v1/hosts", "GET", token, null).ConfigureAwait(false));
        var profiles = hostResponse["hosts"] as JsonArray ?? new JsonArray();
        var profileById = profiles.OfType<JsonObject>().Where(p => !Bool(p, "deleted"))
            .GroupBy(p => Text(p, "id"), StringComparer.OrdinalIgnoreCase)
            .ToDictionary(group => group.Key, group => group.First(), StringComparer.OrdinalIgnoreCase);
        var hosts = config["hosts"] as JsonArray ?? new JsonArray();
        var mergedHosts = new JsonArray();
        var matchedProfiles = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var originalHostIds = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        foreach (var hostNode in hosts.OfType<JsonObject>())
        {
            var host = Clone(hostNode);
            var id = Text(host, "id");
            profileById.TryGetValue(id, out var remote);
            var isDirty = dirtyHosts.Contains(id);
            if (remote is null || isDirty)
            {
                var profile = BuildHostProfile(host, config);
                using var saved = JsonDocument.Parse(await SyncRequestAsync(url + "/v1/hosts/" + Uri.EscapeDataString(id), "PUT", token, profile).ConfigureAwait(false));
                if (saved.RootElement.TryGetProperty("host", out var canonical) && canonical.TryGetProperty("id", out var canonicalId))
                {
                    var newId = canonicalId.GetString() ?? id;
                    if (newId != id)
                    {
                        host["id"] = newId;
                        originalHostIds[newId] = id;
                    }
                    if (profileById.TryGetValue(newId, out var canonicalProfile)) remote = canonicalProfile;
                    dirtyHosts.Remove(id);
                }
            }
            if (remote is not null)
            {
                var remoteId = Text(remote, "id");
                matchedProfiles.Add(remoteId);
                if (!isDirty) ApplyHostProfile(host, remote, config);
            }
            var hostIdNow = Text(host, "id");
            host["directories"] = await SyncWorkspacesAsync(host, config, url, token, username).ConfigureAwait(false);
            var sharedUrl = url + "/v1/hosts/" + Uri.EscapeDataString(hostIdNow) + "/users/" + Uri.EscapeDataString(Text(host, "username")) + "/sessions";
            var sessionData = ParseObject(await SyncRequestAsync(sharedUrl, "GET", token, null).ConfigureAwait(false));
            var sharedMap = GetObject(config, "shared_sessions");
            sharedMap[hostIdNow] = sessionData["sessions"]?.DeepClone() ?? new JsonArray();
            mergedHosts.Add(host);
        }

        foreach (var profile in profileById.Values)
        {
            var id = Text(profile, "id");
            if (matchedProfiles.Contains(id) || mergedHosts.OfType<JsonObject>().Any(h => Text(h, "id") == id)) continue;
            var host = new JsonObject();
            ApplyHostProfile(host, profile, config);
            host["directories"] = await SyncWorkspacesAsync(host, config, url, token, username).ConfigureAwait(false);
            var sharedUrl = url + "/v1/hosts/" + Uri.EscapeDataString(id) + "/users/" + Uri.EscapeDataString(Text(host, "username")) + "/sessions";
            var sessionData = ParseObject(await SyncRequestAsync(sharedUrl, "GET", token, null).ConfigureAwait(false));
            var sharedMap = GetObject(config, "shared_sessions");
            sharedMap[id] = sessionData["sessions"]?.DeepClone() ?? new JsonArray();
            mergedHosts.Add(host);
        }

        await Dispatcher.InvokeAsync(() =>
        {
            var latest = ConfigStore.Load();
            var localHosts = (latest["hosts"] as JsonArray ?? new JsonArray()).OfType<JsonObject>().ToList();
            foreach (var host in mergedHosts.OfType<JsonObject>())
            {
                var id = Text(host, "id");
                var local = localHosts.FirstOrDefault(item => Text(item, "id") == id);
                if (local is null && originalHostIds.TryGetValue(id, out var originalId))
                {
                    local = localHosts.FirstOrDefault(item => Text(item, "id") == originalId);
                    if (Text(latest, "current_host_id") == originalId) latest["current_host_id"] = id;
                }
                // A password can be saved or cleared while the sync requests are running.
                host.Remove("credential_id");
                if (local?["credential_id"] is { } reference) host["credential_id"] = reference.DeepClone();
            }
            latest["hosts"] = mergedHosts;
            latest["agents"] = config["agents"]?.DeepClone();
            latest["sync_username"] = username;
            latest["sync_is_admin"] = config["sync_is_admin"]?.DeepClone();
            latest["dirty_host_profiles"] = JsonArrayFrom(dirtyHosts);
            latest["deleted_host_profiles"] = new JsonArray();
            latest["dirty_workspaces"] = config["dirty_workspaces"]?.DeepClone() ?? new JsonArray();
            latest["agents_dirty"] = config["agents_dirty"]?.DeepClone() ?? JsonValue.Create(false);
            latest["workspace_sync_revisions"] = config["workspace_sync_revisions"]?.DeepClone() ?? new JsonObject();
            latest["agent_sync_revisions"] = config["agent_sync_revisions"]?.DeepClone() ?? new JsonObject();
            latest["shared_sessions"] = config["shared_sessions"]?.DeepClone() ?? new JsonObject();
            ConfigStore.Save(latest);
        }).Task.ConfigureAwait(false);
    }

    private async Task<JsonArray> SyncWorkspacesAsync(JsonObject host, JsonObject config, string url, string token, string username)
    {
        var hostId = Text(host, "id");
        var path = url + "/v1/workspaces/" + Uri.EscapeDataString(hostId);
        var remote = ParseObject(await SyncRequestAsync(path, "GET", token, null).ConfigureAwait(false));
        var revision = Int(remote["revision"], 0);
        var identity = username + ":" + hostId;
        var revisions = GetObject(config, "workspace_sync_revisions");
        var baseRevision = Int(revisions[identity], revision);
        var local = new JsonArray();
        foreach (var directory in (host["directories"] as JsonArray ?? new JsonArray()).OfType<JsonObject>())
        {
            var item = new JsonObject
            {
                ["id"] = Hash(Text(directory, "path")),
                ["name"] = Text(directory, "name"),
                ["path"] = Text(directory, "path"),
                ["icon"] = Text(directory, "icon") is { Length: > 0 } icon ? icon : "📁"
            };
            if (item["name"]?.ToString().Length > 0 && item["path"]?.ToString().StartsWith("/") == true) local.Add(item);
        }
        var shouldPush = !Bool(remote, "configured") || StringSet(config["dirty_workspaces"]).Contains(hostId);
        JsonArray result;
        if (shouldPush)
        {
            var requestBody = new JsonObject { ["workspaces"] = local, ["base_revision"] = baseRevision };
            JsonObject? response = null;
            for (var attempt = 0; attempt < 3; attempt++)
            {
                try { response = ParseObject(await SyncRequestAsync(path, "PUT", token, requestBody).ConfigureAwait(false)); break; }
                catch (SyncHttpException ex) when (ex.StatusCode == 409 && ex.Revision.HasValue && attempt < 2) { requestBody["base_revision"] = ex.Revision.Value; }
            }
            if (response is null) throw new InvalidOperationException("工作区同步冲突，请稍后重试。");
            result = response["workspaces"] as JsonArray ?? local;
            revision = Int(response["revision"], revision);
            var dirty = StringSet(config["dirty_workspaces"]); dirty.Remove(hostId); config["dirty_workspaces"] = JsonArrayFrom(dirty);
        }
        else result = remote["workspaces"] as JsonArray ?? new JsonArray();
        revisions[identity] = revision;
        var normalized = new JsonArray();
        foreach (var item in result.OfType<JsonObject>()) normalized.Add(new JsonObject
        {
            ["id"] = Text(item, "id"), ["name"] = Text(item, "name"), ["path"] = Text(item, "path"), ["icon"] = Text(item, "icon")
        });
        return normalized;
    }

    private async Task<JsonArray> SyncAgentsAsync(JsonObject config, string url, string token, string username)
    {
        const string path = "/v1/agents/account";
        var remote = ParseObject(await SyncRequestAsync(url + path, "GET", token, null).ConfigureAwait(false));
        var revision = Int(remote["revision"], 0);
        var identity = username + ":account";
        var revisions = GetObject(config, "agent_sync_revisions");
        var baseRevision = Int(revisions[identity], revision);
        var local = new JsonArray();
        foreach (var agent in (config["agents"] as JsonArray ?? new JsonArray()).OfType<JsonObject>())
        {
            var item = new JsonObject { ["id"] = Hash(Text(agent, "command")), ["name"] = Text(agent, "name"), ["command"] = Text(agent, "command"), ["icon"] = Text(agent, "icon") is { Length: > 0 } icon ? icon : "✳" };
            if (Text(item, "name").Length > 0 && Text(item, "command").Length > 0) local.Add(item);
        }
        JsonArray result;
        if (!Bool(remote, "configured") || Bool(config, "agents_dirty"))
        {
            var body = new JsonObject { ["agents"] = local, ["base_revision"] = baseRevision };
            JsonObject? response = null;
            for (var attempt = 0; attempt < 3; attempt++)
            {
                try { response = ParseObject(await SyncRequestAsync(url + path, "PUT", token, body).ConfigureAwait(false)); break; }
                catch (SyncHttpException ex) when (ex.StatusCode == 409 && ex.Revision.HasValue && attempt < 2) { body["base_revision"] = ex.Revision.Value; }
            }
            if (response is null) throw new InvalidOperationException("Agent 同步冲突，请稍后重试。");
            result = response["agents"] as JsonArray ?? local;
            revision = Int(response["revision"], revision);
        }
        else result = remote["agents"] as JsonArray ?? new JsonArray();
        revisions[identity] = revision; config["agents_dirty"] = false;
        var normalized = new JsonArray();
        foreach (var item in result.OfType<JsonObject>()) normalized.Add(new JsonObject { ["name"] = Text(item, "name"), ["command"] = Text(item, "command"), ["icon"] = Text(item, "icon"), ["id"] = Text(item, "id") });
        return normalized;
    }

    private static JsonObject BuildHostProfile(JsonObject host, JsonObject config)
    {
        var agents = config["agents"] as JsonArray ?? new JsonArray();
        var selected = Math.Max(0, Math.Min(Int(host["selected_agent"], 0), Math.Max(0, agents.Count - 1)));
        var command = agents.Count > 0 ? Text(agents[selected] as JsonObject ?? new JsonObject(), "command") : "";
        var lan = Text(host, "lan_address");
        var external = Text(host, "external_address");
        if (lan.Length == 0 && external.Length == 0) external = Text(host, "address");
        var port = Int(host["port"], 22);
        var lanPort = Int(host["lan_port"], port);
        var externalPort = Int(host["external_port"], port);
        if (port is < 1 or > 65535 || lanPort is < 1 or > 65535 || externalPort is < 1 or > 65535)
            throw new InvalidDataException("主机端口必须在 1 到 65535 之间，无法同步此主机。");
        var keys = new JsonArray();
        var syncId = Text(host, "sync_id");
        if (syncId.Length > 0) keys.Add(Hash("manual:" + syncId));
        var fingerprint = Text(host, "_sync_fingerprint");
        if (fingerprint.Length > 0) keys.Add(Hash(fingerprint));
        foreach (var address in new[] { Text(host, "legacy_address"), Text(host, "address"), lan, external }) if (address.Length > 0) keys.Add(Hash(address.ToLowerInvariant()));
        return new JsonObject
        {
            ["id"] = Text(host, "id"), ["name"] = Text(host, "name"), ["lan_address"] = lan, ["external_address"] = external,
            ["lan_port"] = lanPort, ["external_port"] = externalPort, ["port"] = port, ["username"] = Text(host, "username"),
            ["icon"] = Text(host, "icon") is { Length: > 0 } icon ? icon : "⌘", ["selected_agent_command"] = command, ["legacy_keys"] = keys
        };
    }

    private static void ApplyHostProfile(JsonObject host, JsonObject profile, JsonObject config)
    {
        foreach (var key in new[] { "id", "name", "lan_address", "external_address", "lan_port", "external_port", "port", "username", "icon" })
            if (profile[key] is not null) host[key] = profile[key]!.DeepClone();
        host["address"] = Text(host, "external_address") is { Length: > 0 } external ? external : Text(host, "lan_address");
        var command = Text(profile, "selected_agent_command");
        var agents = config["agents"] as JsonArray ?? new JsonArray();
        var index = agents.OfType<JsonObject>().ToList().FindIndex(a => Text(a, "command") == command);
        if (index >= 0) host["selected_agent"] = index;
        host["directories"] ??= new JsonArray();
    }

    private async Task SyncSessionAsync(JsonElement message)
    {
        try
        {
            var config = ConfigStore.Load();
            if (Bool(config, "offline_mode")) return;
            var url = Text(config, "sync_url").TrimEnd('/'); var token = Text(config, "sync_token");
            var host = message.GetProperty("host"); var id = Text(host, "id"); var username = Text(host, "username");
            if (url.Length == 0 || token.Length == 0 || id.Length == 0 || username.Length == 0) return;
            var sessionId = Text(message, "id"); var method = Text(message, "method");
            var endpoint = url + "/v1/hosts/" + Uri.EscapeDataString(id) + "/users/" + Uri.EscapeDataString(username) + "/sessions/" + Uri.EscapeDataString(sessionId);
            if (method == "PUT")
            {
                var item = JsonNode.Parse(message.GetProperty("item").GetRawText()) as JsonObject;
                if (item is null) return;
                await SyncRequestAsync(endpoint, "PUT", token, item).ConfigureAwait(false);
            }
            else if (method == "DELETE") await SyncRequestAsync(endpoint, "DELETE", token, null).ConfigureAwait(false);
        }
        catch { }
    }

    private async Task DeleteHostProfileAsync(string hostId)
    {
        try { var config = ConfigStore.Load(); var url = Text(config, "sync_url").TrimEnd('/'); var token = Text(config, "sync_token"); if (!Bool(config, "offline_mode") && url.Length > 0 && token.Length > 0) await SyncRequestAsync(url + "/v1/hosts/" + Uri.EscapeDataString(hostId), "DELETE", token, null).ConfigureAwait(false); }
        catch { }
    }

    private async Task GetAdminUsersAsync()
    {
        try
        {
            var config = ConfigStore.Load(); if (!Bool(config, "sync_is_admin")) throw new InvalidOperationException("当前同步账号不是管理员。");
            var data = ParseObject(await SyncRequestAsync(Text(config, "sync_url").TrimEnd('/') + "/v1/admin/users", "GET", Text(config, "sync_token"), null).ConfigureAwait(false));
            Dispatcher.BeginInvoke(() => Send(new { type = "admin-users", users = data["users"] }));
        }
        catch (Exception ex) { Dispatcher.BeginInvoke(() => Send(new { type = "sync-auth-result", success = false, message = ex.Message })); }
    }

    private async Task SaveAdminUserAsync(JsonElement message)
    {
        try
        {
            var config = ConfigStore.Load(); if (!Bool(config, "sync_is_admin")) throw new InvalidOperationException("当前同步账号不是管理员。");
            var username = Text(message, "username").Trim().ToLowerInvariant(); var password = Text(message, "password");
            var payload = new { username, password, is_admin = message.TryGetProperty("is_admin", out var admin) && admin.ValueKind == JsonValueKind.True };
            var id = Number(message, "user_id", 0); var method = id > 0 ? "PUT" : "POST";
            var path = Text(config, "sync_url").TrimEnd('/') + "/v1/admin/users" + (id > 0 ? "/" + id : "");
            await SyncRequestAsync(path, method, Text(config, "sync_token"), payload).ConfigureAwait(false);
            Dispatcher.BeginInvoke(() => Send(new { type = "admin-result", success = true, message = "同步账号已保存。" }));
        }
        catch (Exception ex) { Dispatcher.BeginInvoke(() => Send(new { type = "admin-result", success = false, message = ex.Message })); }
    }

    private async Task DeleteAdminUserAsync(int userId)
    {
        try
        {
            var config = ConfigStore.Load(); if (!Bool(config, "sync_is_admin")) throw new InvalidOperationException("当前同步账号不是管理员。");
            await SyncRequestAsync(Text(config, "sync_url").TrimEnd('/') + "/v1/admin/users/" + userId, "DELETE", Text(config, "sync_token"), null).ConfigureAwait(false);
            Dispatcher.BeginInvoke(() => Send(new { type = "admin-result", success = true, message = "同步账号已删除。" }));
        }
        catch (Exception ex) { Dispatcher.BeginInvoke(() => Send(new { type = "admin-result", success = false, message = ex.Message })); }
    }

    private async Task UploadImageAsync(JsonElement message)
    {
        var id = Text(message, "id");
        try
        {
            if (!_sessions.TryGetValue(id, out var session) || !_sessionHosts.TryGetValue(id, out var host)) throw new InvalidOperationException("终端会话已关闭。");
            var encoded = Text(message, "data");
            if (encoded.Length == 0 || encoded.Length > 28_000_000) throw new InvalidDataException("图片为空或超过 20 MB。");
            var bytes = Convert.FromBase64String(encoded);
            if (bytes.Length > 20 * 1024 * 1024) throw new InvalidDataException("图片超过 20 MB。");
            var path = await UploadImageBytesAsync(host, bytes, "jpg").ConfigureAwait(false);
            session.Write(QuoteShell(path) + " ");
            Dispatcher.BeginInvoke(() => Send(new { type = "image-uploaded", id, path }));
        }
        catch (Exception ex) { Dispatcher.BeginInvoke(() => Send(new { type = "error", id, message = ex.Message })); }
    }

    private async Task<string> UploadImageBytesAsync(JsonObject host, byte[] bytes, string extension)
    {
        if (bytes.Length == 0 || bytes.Length > 20 * 1024 * 1024) throw new InvalidDataException("图片为空或超过 20 MB。");
        var pathName = Guid.NewGuid().ToString("N") + "." + extension;
        var remote = "umask 077; dir=\"$HOME/.remote-dev-terminal/images\"; mkdir -p -- \"$dir\" || exit 1; tmp=\"$dir/." + pathName + ".tmp\"; target=\"$dir/" + pathName + "\"; cat > \"$tmp\" && mv -- \"$tmp\" \"$target\" && printf '\\nRDT_IMAGE_PATH %s\\n' \"$target\"";
        var (exitCode, output, error) = await RunSshCommandAsync(host, remote, bytes, 60_000).ConfigureAwait(false);
        if (exitCode != 0) throw new InvalidOperationException(error.Trim().Length > 0 ? error.Trim() : "远程图片上传失败。");
        var pathLine = output.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries).LastOrDefault(line => line.StartsWith("RDT_IMAGE_PATH ", StringComparison.Ordinal));
        var path = pathLine?.Substring("RDT_IMAGE_PATH ".Length).Trim() ?? "";
        if (!path.StartsWith("/", StringComparison.Ordinal)) throw new InvalidDataException("远端没有返回有效图片路径。");
        return path;
    }

    private async Task UploadImagePathAsync(JsonElement message)
    {
        var requestId = Text(message, "request_id"); var sessionId = Text(message, "id");
        try
        {
            if (!_sessions.TryGetValue(sessionId, out var session) || !_sessionHosts.TryGetValue(sessionId, out var host)) throw new InvalidOperationException("终端会话已关闭。");
            var sourcePath = Path.GetFullPath(Text(message, "image_path"));
            if (!File.Exists(sourcePath)) throw new FileNotFoundException("本地图片不存在。", sourcePath);
            using var image = Image.FromFile(sourcePath);
            using var buffer = new MemoryStream();
            image.Save(buffer, ImageFormat.Png);
            if (buffer.Length > 20 * 1024 * 1024) throw new InvalidDataException("图片转换为 PNG 后超过 20 MB。");
            var path = await UploadImageBytesAsync(host, buffer.ToArray(), "png").ConfigureAwait(false);
            var prefix = Text(message, "prompt_prefix"); if (prefix.Length == 0) prefix = "请查看这张图片：";
            session.Write(prefix + " " + QuoteShell(path) + "\r");
            Dispatcher.BeginInvoke(() => Send(new { type = "mcp-image-result", request_id = requestId, result = new { path } }));
        }
        catch (Exception ex) { Dispatcher.BeginInvoke(() => Send(new { type = "mcp-image-result", request_id = requestId, error = ex.Message })); }
    }

    private async Task GetSshPublicKeyAsync(JsonElement message)
    {
        var requestId = Text(message, "request_id");
        try
        {
            var sshDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".ssh");
            var keyPath = Path.Combine(sshDir, "id_ed25519"); var publicPath = keyPath + ".pub";
            if (!File.Exists(publicPath) && message.TryGetProperty("generate_if_missing", out var generate) && generate.ValueKind == JsonValueKind.True)
            {
                Directory.CreateDirectory(sshDir);
                var keygen = Path.Combine(Path.GetDirectoryName(SshExecutable())!, "ssh-keygen.exe");
                var start = new ProcessStartInfo(keygen) { Arguments = "-t ed25519 -N \"\" -f " + QuoteArgument(keyPath), UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
                using var process = Process.Start(start) ?? throw new InvalidOperationException("无法启动 ssh-keygen。");
                await Task.Run(() => process.WaitForExit()).ConfigureAwait(false);
                if (process.ExitCode != 0) throw new InvalidOperationException(process.StandardError.ReadToEnd());
            }
            if (!File.Exists(publicPath)) throw new FileNotFoundException("未找到 ~/.ssh/id_ed25519.pub；可用 generate_if_missing=true 自动生成。");
            var result = new { path = publicPath, public_key = File.ReadAllText(publicPath, Encoding.UTF8).Trim() };
            Dispatcher.BeginInvoke(() => Send(new { type = "mcp-public-key", request_id = requestId, result }));
        }
        catch (Exception ex) { Dispatcher.BeginInvoke(() => Send(new { type = "mcp-public-key", request_id = requestId, error = ex.Message })); }
    }

    private async Task BrowseDirectoriesAsync(JsonElement message)
    {
        var requestId = Text(message, "request_id");
        var requested = Text(message, "path");
        if (requested.Length == 0) requested = "~";
        object reply;
        try
        {
            var host = JsonNode.Parse(message.GetProperty("host").GetRawText()) as JsonObject ?? throw new InvalidDataException("主机配置无效。");
            const string listingScript = """
                dir=$1
                case "$dir" in
                    '~') dir=$HOME ;;
                    '~/'*) dir="$HOME/${dir#??}" ;;
                    -*) dir="./$dir" ;;
                esac
                unset CDPATH
                if ! cd "$dir" 2>/dev/null || [ ! -r . ] || [ ! -x . ]; then
                    printf '%s\n' '目录不存在或没有读取权限。' >&2
                    exit 2
                fi
                printf '\000RDT_DIRECTORY_LIST\000%s\000' "$PWD"
                for entry in ./* ./.[!.]* ./..?*; do
                    if [ -d "$entry" ]; then
                        printf '%s\000' "${entry#./}"
                    fi
                done
                """;
            var remote = "sh -c " + QuoteShell(listingScript.Replace("\r\n", "\n")) + " rdt-browse " + QuoteShell(requested);
            var result = await Task.Run(() => RunSshCommandAsync(host, remote, null, 25_000)).ConfigureAwait(false);
            if (result.ExitCode != 0)
            {
                var error = result.Error.Trim();
                if (error.IndexOf("Host key verification failed", StringComparison.OrdinalIgnoreCase) >= 0)
                    error = "SSH 主机指纹未确认或已变更。请先核对主机指纹，再浏览远端目录。";
                throw new InvalidOperationException(error.Length > 0 ? error : "读取远端目录失败。");
            }

            const string marker = "\0RDT_DIRECTORY_LIST\0";
            var start = result.Output.IndexOf(marker, StringComparison.Ordinal);
            if (start < 0 || !result.Output.EndsWith("\0", StringComparison.Ordinal))
                throw new InvalidDataException("远端未返回有效的目录列表。");
            var fields = result.Output.Substring(start + marker.Length).Split('\0');
            var current = fields[0];
            if (!current.StartsWith("/", StringComparison.Ordinal))
                throw new InvalidDataException("远端未返回有效的绝对路径。");
            var prefix = current.TrimEnd('/') + "/";
            var entries = fields.Skip(1)
                .Where(name => name.Length > 0 && name.IndexOf('/') < 0 && name != "." && name != "..")
                .OrderBy(name => name, StringComparer.OrdinalIgnoreCase)
                .Select(name => new { name, path = prefix + name }).ToArray();
            reply = new { type = "browse-result", request_id = requestId, current, directories = entries };
        }
        catch (Exception ex)
        {
            reply = new { type = "browse-result", request_id = requestId, error = ex.Message, current = requested, directories = new JsonArray() };
        }
        await Dispatcher.InvokeAsync(() => Send(reply)).Task.ConfigureAwait(false);
    }

    private async Task<(int ExitCode, string Output, string Error)> RunSshCommandAsync(JsonObject host, string remote, byte[]? input, int timeoutMs)
    {
        var start = CreateSshStartInfo(host, remote);
        using var process = new Process { StartInfo = start };
        if (!process.Start()) throw new InvalidOperationException("无法启动 Windows OpenSSH。");
        var outputTask = process.StandardOutput.ReadToEndAsync(); var errorTask = process.StandardError.ReadToEndAsync();
        if (input is { Length: > 0 }) await process.StandardInput.BaseStream.WriteAsync(input, 0, input.Length).ConfigureAwait(false);
        process.StandardInput.Close();
        if (!await Task.Run(() => process.WaitForExit(timeoutMs)).ConfigureAwait(false))
        {
            try { process.Kill(); } catch { }
            throw new TimeoutException("SSH 操作超时。");
        }
        return (process.ExitCode, await outputTask.ConfigureAwait(false), await errorTask.ConfigureAwait(false));
    }

    private ProcessStartInfo CreateSshStartInfo(JsonObject host, string remote)
    {
        using var document = JsonDocument.Parse(host.ToJsonString());
        var (address, port) = ResolveSshEndpoint(document.RootElement);
        var username = Text(host, "username");
        var password = ReadHostPassword(Text(host, "credential_id"), username);
        var start = new ProcessStartInfo(SshExecutable())
        {
            Arguments = "-T -o " + (password.Length > 0 ? "BatchMode=no" : "BatchMode=yes") + " -o StrictHostKeyChecking=yes -o NumberOfPasswordPrompts=1 -o ConnectTimeout=8 -p " + port + " -l " + QuoteArgument(username) + " -- " + QuoteArgument(address) + " " + QuoteArgument(remote),
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
        };
        if (password.Length > 0)
        {
            start.EnvironmentVariables["SSH_ASKPASS"] = Process.GetCurrentProcess().MainModule?.FileName ?? SshExecutable();
            start.EnvironmentVariables["SSH_ASKPASS_REQUIRE"] = "force";
            start.EnvironmentVariables["DISPLAY"] = "rdt";
            start.EnvironmentVariables["RDT_ASKPASS_PASSWORD"] = password;
        }
        return start;
    }

    private void ShowMcpInfo() => MessageBox.Show(this,
        _mcpServer is null ? "MCP 服务未启动。请检查系统设置中的 MCP 开关。" : $"MCP HTTP 服务：\nhttp://127.0.0.1:{_mcpServer.Port}/mcp\n\n发现文件：\n{Path.Combine(Path.GetTempPath(), "remote-dev-terminal-mcp.json")}\n\nstdio 接入：RemoteDevTerminal.exe --mcp-stdio",
        "MCP 接入说明", MessageBoxButton.OK, MessageBoxImage.Information);

    private void StartMcpServer()
    {
        try
        {
            if (!Bool(ConfigStore.Load(), "mcp_enabled")) return;
            _mcpServer = new McpServer(this); _mcpServer.Start();
        }
        catch (Exception ex) { Send(new { type = "notice", message = "MCP 服务启动失败：" + ex.Message }); }
    }

    internal Task<JsonElement> InvokeMcpToolAsync(string name, JsonElement arguments)
    {
        var id = Guid.NewGuid().ToString("N");
        var completion = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!_mcpCalls.TryAdd(id, completion)) throw new InvalidOperationException("无法创建 MCP 请求。");
        Dispatcher.BeginInvoke(() => Send(new { type = "mcp-tool", id, name, arguments = arguments.Clone() }));
        _ = Task.Delay(TimeSpan.FromSeconds(90)).ContinueWith(_ =>
        {
            if (_mcpCalls.TryRemove(id, out var pending)) pending.TrySetException(new TimeoutException("MCP 工具执行超时。"));
        });
        return completion.Task;
    }

    private void ResolveMcpCall(JsonElement message)
    {
        var id = Text(message, "id");
        if (!_mcpCalls.TryRemove(id, out var completion)) return;
        if (message.TryGetProperty("error", out var error) && error.ValueKind != JsonValueKind.Null)
            completion.TrySetException(new InvalidOperationException(error.GetString() ?? error.ToString()));
        else if (message.TryGetProperty("result", out var result)) completion.TrySetResult(result.Clone());
        else completion.TrySetResult(JsonDocument.Parse("null").RootElement.Clone());
    }

    private static string SshExecutable() => SshTerminalSession.ResolveSshExecutable();
    private (string Address, int Port) ResolveSshEndpoint(JsonElement host)
    {
        var lan = Text(host, "lan_address");
        var external = Text(host, "external_address");
        if (lan.Length == 0 && external.Length == 0) external = Text(host, "address");
        var lanPort = ParsePort(Text(host, "lan_port"), ParsePort(Text(host, "port"), 22));
        var externalPort = ParsePort(Text(host, "external_port"), ParsePort(Text(host, "port"), 22));
        var candidates = new List<(string Address, int Port)>();
        if (lan.Length > 0) candidates.Add((lan, lanPort));
        if (external.Length > 0 && (external != lan || externalPort != lanPort)) candidates.Add((external, externalPort));
        if (candidates.Count == 0) throw new ArgumentException("请填写主机 SSH 地址。");

        var key = string.Join("\u001f", Text(host, "id"), Text(host, "username"), lan, lanPort.ToString(CultureInfo.InvariantCulture), external, externalPort.ToString(CultureInfo.InvariantCulture));
        var resolution = _sshEndpointCache.GetOrAdd(key, _ => new Lazy<(string Address, int Port)>(
            () => ProbeSshEndpoint(candidates), LazyThreadSafetyMode.ExecutionAndPublication)).Value;
        // The lazy entry is shared by startup probing, terminal connects, and other SSH operations,
        // so a host's address is probed only once for this application launch.
        var remembered = resolution;
        AnnounceSshEndpoint(host, key, remembered);
        return remembered;
    }

    private void AnnounceSshEndpoint(JsonElement host, string signature, (string Address, int Port) endpoint)
    {
        var hostId = Text(host, "id");
        if (hostId.Length == 0) return;
        var value = signature + "\u001f" + endpoint.Address + ":" + endpoint.Port.ToString(CultureInfo.InvariantCulture);
        while (true)
        {
            if (_announcedSshEndpoints.TryGetValue(hostId, out var previous))
            {
                if (previous == value) return;
                if (_announcedSshEndpoints.TryUpdate(hostId, value, previous)) break;
            }
            else if (_announcedSshEndpoints.TryAdd(hostId, value)) break;
        }
        Dispatcher.BeginInvoke(() =>
        {
            if (_announcedSshEndpoints.TryGetValue(hostId, out var current) && current == value)
                Send(new { type = "host-endpoint", host_id = hostId, signature, address = endpoint.Address, port = endpoint.Port });
        });
    }

    private static (string Address, int Port) ProbeSshEndpoint(List<(string Address, int Port)> candidates)
    {
        // Prefer an endpoint already in known_hosts when both routes are available.
        // OpenSSH still verifies the server's actual key on every connection.
        var ordered = candidates.Count > 1
            ? candidates.OrderByDescending(candidate => HasKnownHostEntry(candidate.Address, candidate.Port))
            : (IEnumerable<(string Address, int Port)>)candidates;
        foreach (var candidate in ordered)
            if (CanConnect(candidate.Address, candidate.Port)) return candidate;
        return candidates[candidates.Count - 1];
    }

    private static bool HasKnownHostEntry(string address, int port)
    {
        try
        {
            var sshDirectory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".ssh");
            var keygen = Path.Combine(Path.GetDirectoryName(SshExecutable())!, "ssh-keygen.exe");
            var lookup = port == 22 ? address : $"[{address}]:{port}";
            foreach (var name in new[] { "known_hosts", "known_hosts2" })
            {
                var file = Path.Combine(sshDirectory, name);
                if (!File.Exists(file)) continue;
                using var process = Process.Start(new ProcessStartInfo(keygen)
                {
                    Arguments = $"-F {QuoteArgument(lookup)} -f {QuoteArgument(file)}",
                    UseShellExecute = false, CreateNoWindow = true,
                    RedirectStandardOutput = true, RedirectStandardError = true
                });
                if (process is null) continue;
                _ = process.StandardOutput.ReadToEndAsync();
                _ = process.StandardError.ReadToEndAsync();
                if (!process.WaitForExit(1000)) { process.Kill(); continue; }
                if (process.ExitCode == 0) return true;
            }
        }
        catch (Exception) { }
        return false;
    }
    private static int ParsePort(string value, int fallback) => int.TryParse(value, out var port) && port is >= 1 and <= 65535 ? port : fallback;
    private static bool CanConnect(string address, int port)
    {
        try
        {
            using var client = new TcpClient();
            if (!client.ConnectAsync(address, port).Wait(TimeSpan.FromMilliseconds(900)) || !client.Connected) return false;
            client.ReceiveTimeout = 900;
            using var stream = client.GetStream();
            var line = new List<byte>(128);
            for (var count = 0; count < 1024; count++)
            {
                var value = stream.ReadByte();
                if (value < 0) return false;
                if (value == '\n')
                {
                    if (Encoding.ASCII.GetString(line.ToArray()).StartsWith("SSH-", StringComparison.Ordinal)) return true;
                    line.Clear();
                }
                else if (value != '\r') line.Add((byte)value);
            }
            return false;
        }
        catch { return false; }
    }
    private static string QuoteShell(string value) => "'" + value.Replace("'", "'\"'\"'") + "'";
    private static string QuoteArgument(string value) => SshTerminalSession.QuoteArgument(value);

    private void Send(object message)
    {
        if (!_ready) return;
        try { Browser.CoreWebView2.PostWebMessageAsJson(JsonSerializer.Serialize(message)); }
        catch (InvalidOperationException) { }
    }

    private static string Text(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined) return "";
        return value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString();
    }

    private static int Number(JsonElement element, string name, int fallback) =>
        element.TryGetProperty(name, out var value) && value.TryGetInt32(out var result) ? result : fallback;

    private static string Text(JsonObject element, string name) => element[name]?.ToString() ?? "";
    private static int Int(JsonNode? value, int fallback)
    {
        try { return value?.GetValue<int>() ?? fallback; }
        catch { return int.TryParse(value?.ToString(), out var result) ? result : fallback; }
    }
    private static bool Bool(JsonObject element, string name)
    {
        try { return element[name]?.GetValue<bool>() ?? false; }
        catch { return bool.TryParse(element[name]?.ToString(), out var value) && value; }
    }
    private static JsonObject GetObject(JsonObject parent, string key)
    {
        if (parent[key] is JsonObject existing) return existing;
        var created = new JsonObject(); parent[key] = created; return created;
    }
    private static HashSet<string> StringSet(JsonNode? node) => new((node as JsonArray ?? new JsonArray()).Select(value => value?.ToString() ?? "").Where(value => value.Length > 0), StringComparer.Ordinal);
    private static JsonArray JsonArrayFrom(IEnumerable<string> values)
    {
        var array = new JsonArray(); foreach (var value in values) array.Add(value); return array;
    }
    private static JsonObject Clone(JsonObject value) => JsonNode.Parse(value.ToJsonString()) as JsonObject ?? new JsonObject();
    private static JsonObject ParseObject(string json) => JsonNode.Parse(json) as JsonObject ?? new JsonObject();
    private static string Hash(string value)
    {
        using var sha = SHA256.Create(); return BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(value))).Replace("-", "").ToLowerInvariant().Substring(0, 32);
    }

    private void Window_Closed(object? sender, EventArgs e)
    {
        _syncTimer.Stop(); _metricsTimer.Stop(); _quotaTimer.Stop();
        _mcpServer?.Dispose(); _mcpServer = null;
        foreach (var session in _sessions.Values.ToArray()) session.Dispose();
        _sessions.Clear();
        _sessionHosts.Clear();
        Browser.Dispose();
    }
}

internal sealed class SyncHttpException : InvalidOperationException
{
    public int StatusCode { get; }
    public int? Revision { get; }
    public SyncHttpException(int statusCode, string message, Exception inner, int? revision) : base(message, inner) { StatusCode = statusCode; Revision = revision; }
}
