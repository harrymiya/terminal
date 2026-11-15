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

# Omarchy / Linux 桌面版

Linux 桌面版本位于 [omarchy/](omarchy/README.md)，与 Android 版本并列维护。
