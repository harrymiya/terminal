using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteDevTerminal;

internal static class ConfigStore
{
    private static readonly JsonSerializerOptions Options = new() { WriteIndented = true };
    private static readonly string ConfigPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        ".config", "remote-dev-terminal", "config.json");

    public static JsonObject Load()
    {
        JsonObject config;
        if (File.Exists(ConfigPath))
        {
            try { config = JsonNode.Parse(File.ReadAllText(ConfigPath)) as JsonObject ?? new JsonObject(); }
            catch (Exception ex) when (ex is IOException or JsonException)
            {
                throw new InvalidDataException($"配置文件无法读取：{ConfigPath}\n{ex.Message}", ex);
            }
        }
        else config = new JsonObject();

        config["hosts"] ??= new JsonArray();
        config["agents"] ??= new JsonArray(
            new JsonObject { ["name"] = "Codex", ["command"] = "codex" },
            new JsonObject { ["name"] = "OpenCode", ["command"] = "opencode" },
            new JsonObject { ["name"] = "Antigravity", ["command"] = "agy" },
            new JsonObject { ["name"] = "Shell", ["command"] = "bash -l" });
        config["sync_url"] ??= "";
        config["sync_token"] ??= "";
        config["sync_username"] ??= "";
        config["offline_mode"] ??= false;
        config["auto_sync_enabled"] ??= false;
        config["sync_interval_seconds"] ??= 5;
        config["auto_enter"] ??= false;
        config["auto_enter_delay"] ??= 1000;
        config["agent_quota_interval_seconds"] ??= 60;
        config["host_metrics_interval_seconds"] ??= 5;
        config["theme"] ??= "cyberpunk";
        config["tiled"] ??= true;
        config["tile_layout"] ??= "grid";
        config["ui_scale"] ??= 100;
        config["mcp_enabled"] ??= true;
        return config;
    }

    public static void Save(JsonObject config)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(ConfigPath)!);
        var temp = ConfigPath + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            File.WriteAllText(temp, config.ToJsonString(Options));
            if (File.Exists(ConfigPath)) File.Replace(temp, ConfigPath, null, true);
            else File.Move(temp, ConfigPath);
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
}
