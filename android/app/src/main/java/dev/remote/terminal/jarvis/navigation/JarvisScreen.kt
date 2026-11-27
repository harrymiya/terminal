package dev.remote.terminal.jarvis.navigation

internal enum class JarvisScreen(val id: String, val label: String, vararg val aliases: String) {
    HOME("home", "首页", "主页", "主页面", "主界面", "首页界面"),
    TERMINAL("terminal", "终端", "终端页", "终端页面"),
    SETTINGS("settings", "设置", "设置页", "设置页面"),
    MANAGE("manage", "主机管理", "管理", "管理页", "管理页面", "主机", "工作区", "工作区管理"),
    AGENTS("agents", "开发者", "开发者页", "开发者页面", "开发者列表"),
    LOGS("logs", "历史记录", "history", "历史", "历史页", "日志", "操作日志", "会话历史"),
    LOGIN("login", "登录", "登录页", "登录页面", "账号登录页");

    companion object {
        private val navigationCommand = Regex(
            """^(?:(?:请|麻烦|帮我|给我|替我|能不能|可以|能帮我|能替我)\s*){0,4}(?:现在\s*)?(?:切换回到?|切换到|切回到?|切到|返回到?|回到|回去|回|跳转到|跳到|切去|进入|打开|去|显示|带我到|带我回)\s*(?:一下\s*)?(?:Remote\s*Dev\s*(?:的)?\s*)?(.+?)(?:\s*(?:一下|吧|好吗|可以吗|行吗|谢谢))*$""",
            RegexOption.IGNORE_CASE
        )

        fun fromIdOrAlias(value: String): JarvisScreen? {
            val key = value.trim()
            return entries.firstOrNull { screen ->
                screen.id.equals(key, true) || screen.label == key || key in screen.aliases
            }
        }

        fun fromCommand(text: String): JarvisScreen? {
            val clean = text.trim().trimEnd('。', '！', '!', '？', '?', '，', ',').trimEnd()
            val target = navigationCommand.matchEntire(clean)?.groupValues?.get(1) ?: return null
            return fromIdOrAlias(target)
        }
    }
}
