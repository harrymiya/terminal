---
name: remote-dev-terminal-apk-release
description: 在 remote-dev-terminal 项目中构建 Android APK，并上传到 APP 测试管理端创建草稿。
---

# Remote Dev Terminal 打包与上传

当用户要求打包、发布测试版、上传 APK 或推送到 APP 管理端时使用本流程。项目根目录的 `AGENTS.md` 会要求 Codex 在这些操作前读取本文件。

## 一键打包并上传

在项目根目录执行：

```bash
APK_RELEASE_UPDATE_LOG="Remote Dev Terminal 更新说明" bash scripts/release-test.sh
```

脚本先离线构建 Android debug APK，再上传到 APP 测试管理端。默认版本信息来自 `app/build.gradle.kts`。构建产物路径为：

```text
dist/RemoteDevTerminal-<versionName>-debug.apk
```

如果只需要打包：

```bash
bash scripts/build-apk.sh
```

上传已构建的 APK：

```bash
APK_RELEASE_UPDATE_LOG="Remote Dev Terminal 更新说明" bash scripts/upload-apk-test.sh
```

## 上传凭据

上传前必须配置上传 token，二选一：

- 环境变量 `APK_RELEASE_TOKEN`
- 本地忽略文件 `config/app-release-upload.token`

也可以通过 `APK_RELEASE_TOKEN_FILE` 指定其他 token 文件。不要把 token 写进源码、命令记录或文档，也不要打印 token。凭据未配置时，先完成打包，再向用户说明需要配置凭据才能上传。

本机已将管理端上传凭据另存到本项目的 `config/app-release-upload.token`（Git 忽略文件），后续优先直接使用，无须重复向用户索要。若文件缺失，先检查 `/home/agent/code/jiankang_app_uniapp/config/app-release-upload.token` 是否存在且非空；存在时可将其复制到本项目的上述路径并设置权限为 `600`，或通过 `APK_RELEASE_TOKEN_FILE` 指向它。只检查文件状态，不输出凭据内容；上述位置均不可用时再告知用户。

## 上传行为

- 上传目标固定为 APP 测试管理端的 APK 上传接口，地址配置在 `scripts/upload-apk-test.sh` 中。
- 脚本使用 `force_update=false`，并将 `min_supported_version_code` 设为 `1`。
- 上传成功会在管理端创建草稿；如需正式发布，之后仍须在 Wish Admin 中发布该草稿。
- 上传脚本会检查接口返回的 version code 是否与项目版本一致。
- 若管理端提示 version code 已存在，递增 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`，重新构建后再上传；不要重复提交同一版本号。
- 默认构建使用 Gradle 离线模式；确实需要下载依赖时可设置 `APK_BUILD_OFFLINE=false`。

## 完成后核对

确认构建输出显示 `BUILD SUCCESSFUL`，并记录版本号、version code、APK 路径和 SHA-256。上传成功时确认接口返回草稿 ID、版本号和状态；不要把 token 或完整请求头写入结果。
