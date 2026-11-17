# Remote Dev Terminal · Android

Remote Dev Terminal 安卓客户端采用 Kotlin + Jetpack Compose 开发，用于连接 SSH 开发环境并运行远程 CLI Agent。

## 功能特性

- 本地 SSH 主机、工作区目录与 Agent 配置，支持自定义名称与 Emoji 图标。
- 密码由 Android Keystore 加密存储，首次连接支持 SSH 主机指纹确认。
- 交互式 SSH PTY 终端会话，支持断线自动重连，为每个任务创建独立的 tmux 会话。
- 多任务标签页管理，支持左右滑动切换、活动输出提示、未读指示与确认提示通知。
- Termux 风格固定双行功能快捷按键栏、透明终端输入框、中文 CJK 双宽字符渲染与输入法快速切换开关。
- 支持与服务端进行会话、工作区和 Agent 配置的跨端同步。
- 编辑主机时可设置多端同步标识；同一台主机在各设备上填入相同值，即按此标识优先同步。

## 构建与打包

安卓工程为独立的 Gradle 工程，可直接在 `android` 目录下执行构建，或使用根目录下的打包脚本：

### 使用根目录脚本（推荐）

```bash
# 离线构建 debug APK（产物输出至 dist/RemoteDevTerminal-<versionName>-debug.apk）
bash scripts/build-apk.sh

# 打包并上传至测试管理端
APK_RELEASE_UPDATE_LOG="版本更新说明" bash scripts/release-test.sh
```

### 在 android 目录中手动构建

```bash
cd android

# 离线构建 Debug APK
./gradlew --offline :app:assembleDebug

# 产物位置
# android/app/build/outputs/apk/debug/app-debug.apk
```
