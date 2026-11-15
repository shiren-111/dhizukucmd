# dhizukucmd

通过 `app_process` 运行的 Dhizuku DeviceOwner 命令行客户端，以及用于验证 Android 9–17 兼容性的 GitHub Actions 模拟器测试。

## 文件

- `DhizukuClient.dex` — 客户端 dex（一个 dex 包含全部命令，Android 9–17 运行时自适应）
- `Dhizuku.apk` — Dhizuku 2.13.0（仅用于模拟器测试）
- `client/DhizukuAppProcessClient.java` — 客户端源码
- `ci/emulator-test.sh` — 模拟器内安装 Dhizuku / 设为 DO / 执行命令的脚本
- `.github/workflows/emulator.yml` — 手动触发的 Actions 工作流，输入 API level

## 工作流

Actions → Android emulator test → Run workflow，填写 `api_levels` JSON 数组（默认 28–37.0；`37.0` 需带引号，因为 API 37 的系统镜像包名是 `android-37.0`）。

脚本会：

1. 安装 Dhizuku 并执行 `dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver`
2. 从 APK 提取 dex 到 `/data/local/tmp`，推送 `DhizukuClient.dex`
3. 运行 `status`、`perm`、`app-status`、`camera`、`hide`、`suspend`、`block-uninstall`、`system`、`secure`、`restrictions`
4. 失败时上传截图、logcat、`dumpsys device_policy` 作为 artifact

## 本地运行

```bash
CLASSPATH=/data/local/tmp/dhizuku_api.dex:/data/local/tmp/dhizuku_api2.dex:/data/local/tmp/DhizukuClient.dex \
  app_process /system/bin DhizukuAppProcessClient help
```
