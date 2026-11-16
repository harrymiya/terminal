# Remote Dev Terminal

Kotlin Android app for opening SSH development sessions and running remote CLI agents.

## Build

```bash
bash scripts/build-apk.sh
```

The app package is `dev.remote.terminal`. The debug APK is copied to
`dist/RemoteDevTerminal-<versionName>-debug.apk`.

## Build and upload to APP test

Set `APK_RELEASE_TOKEN` or store the token in the ignored file
`config/app-release-upload.token`, then run:

```bash
APK_RELEASE_UPDATE_LOG="Remote Dev Terminal update" bash scripts/release-test.sh
```

The upload creates a non-forced draft on the APP test management endpoint. Publishing remains a separate step in Wish Admin. See
[`remote-dev-terminal-apk`](.agents/skills/remote-dev-terminal-apk/SKILL.md)
for the full packaging workflow.

## Included

- Local SSH host, project directory, and agent configuration with editable names and emoji icons.
- Password encryption backed by Android Keystore and first-connection SSH host-key confirmation.
- Interactive SSH PTY with reconnect attempts and a separate tmux session for every task.
- Multiple task tabs with horizontal swipe, output activity, unread indicators, and approval-prompt notices.
- Termux-style fixed two-row key strip, transparent terminal input, double-width CJK rendering, and a keyboard toggle.

# 桌面版

桌面版本分别位于 [Omarchy](omarchy/README.md)、[Ubuntu](ubuntu/README.md) 和 [Windows](windows/README.md) 目录。Windows 版本通过 WSLg 运行，需要 WSL 2 和 Ubuntu。

各端使用相同的版本号，以 `app/build.gradle.kts` 的 `versionName` 为准。修改版本后运行 `python3 scripts/sync-desktop-versions.py`，提交各桌面目录生成的 `version.py`；使用 `python3 scripts/sync-desktop-versions.py --check` 检查是否一致。Android 的 `versionCode` 仍需在发布时单独递增。

跨设备共享会话的服务、frp 示例及客户端配置见 [同步服务说明](backend/README.md)。
