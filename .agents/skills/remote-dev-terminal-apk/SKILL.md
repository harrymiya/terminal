---
name: remote-dev-terminal-apk
description: Build the Remote Dev Terminal Android APK and upload a test draft to the APP test management endpoint.
---

# Remote Dev Terminal APK packaging

Use the scripts in this repository. The build is an offline Gradle debug build; the upload script targets the APP test management endpoint and always creates a non-forced draft.

## Build only

From the project root:

```bash
bash scripts/build-apk.sh
```

The APK is copied to `dist/RemoteDevTerminal-<versionName>-debug.apk`.

## Build and upload to APP test

Provide the upload token through `APK_RELEASE_TOKEN` or create the ignored local file `config/app-release-upload.token`. Never add the token to source control or print it.

```bash
APK_RELEASE_UPDATE_LOG="Remote Dev Terminal <version>" bash scripts/release-test.sh
```

To upload an APK that was already built:

```bash
APK_RELEASE_UPDATE_LOG="Remote Dev Terminal <version>" bash scripts/upload-apk-test.sh
```

`APK_RELEASE_UPDATE_LOG` is optional. The script uses the project version when it is omitted. An alternate token file can be selected with `APK_RELEASE_TOKEN_FILE`.

## Behavior and limits

- The target is fixed to the APP test upload endpoint at `rxtest.shdmu-ch.com:18801`.
- Uploads use `force_update=false` and `min_supported_version_code=1`.
- Upload creates a draft; publishing it remains a separate action in Wish Admin.
- `APK_BUILD_OFFLINE=false` can be set for a build that needs Gradle dependency downloads.
