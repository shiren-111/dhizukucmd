#!/usr/bin/env bash
# 在 GitHub Actions Android 模拟器上安装 Dhizuku、设为 DeviceOwner，
# 然后用 app_process 运行 DhizukuClient.dex 的全套命令。
set -euo pipefail

PKG=com.rosan.dhizuku
DO_COMPONENT="$PKG/.server.DhizukuDAReceiver"
CLIENT=DhizukuAppProcessClient
CP=/data/local/tmp/dhizuku_api.dex:/data/local/tmp/dhizuku_api2.dex:/data/local/tmp/DhizukuClient.dex
APK_FILE="${DHIZUKU_APK:-Dhizuku.apk}"
API_DEX_DIR="${DHIZUKU_API_DEX_DIR:-api-dex}"

debug_dump() {
  adb exec-out screencap -p > screenshot.png 2>/dev/null || true
  adb logcat -d > logcat.txt 2>/dev/null || true
  adb shell dumpsys device_policy > device_policy.txt 2>/dev/null || true
  adb shell dumpsys package "$PKG" > package.txt 2>/dev/null || true
  adb shell dumpsys account > account.txt 2>/dev/null || true
  adb shell pm list users > users.txt 2>/dev/null || true
  adb shell "CLASSPATH=$CP app_process /system/bin $CLIENT help" > help.txt 2>&1 || true
}
trap debug_dump EXIT

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
adb shell input keyevent 82 || true
adb shell wm dismiss-keyguard || true

SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Android SDK: $SDK"

# Android 16/17 的 DevicePolicyManagerService 在启动后异步计算
# hasIncompatibleAccounts；缓存未就绪时会保守地返回“有账户”，
# 导致紧接着执行的 dpm set-device-owner 误报 accounts on device。
if [ "$SDK" -ge 36 ]; then
  echo "== wait for DevicePolicyManager account cache =="
  for i in $(seq 1 30); do
    if adb logcat -d -s DevicePolicyManager 2>/dev/null | grep -q 'Finished calculating hasIncompatibleAccountsTask'; then
      echo "account cache ready after ${i}s"
      break
    fi
    sleep 1
  done
  sleep 2
fi

echo "== install Dhizuku from $APK_FILE =="
adb install -r "$APK_FILE"

echo "== set device owner =="
OWNER_SET=0
for attempt in 1 2 3; do
  if adb shell dpm set-device-owner "$DO_COMPONENT"; then
    OWNER_SET=1
    break
  fi
  echo "dpm set-device-owner attempt $attempt failed; retrying..."
  sleep 5
done
if [ "$OWNER_SET" != "1" ]; then
  echo "ERROR: failed to set device owner"
  exit 1
fi
sleep 2

echo "== launch Dhizuku once =="
adb shell am start -n "$PKG/.ui.activity.SettingsActivity" || true
sleep 5
adb shell input keyevent 3 || true

echo "== push latest API dex and client =="
adb push "$API_DEX_DIR/classes.dex" /data/local/tmp/dhizuku_api.dex
if [ -f "$API_DEX_DIR/classes2.dex" ]; then
  adb push "$API_DEX_DIR/classes2.dex" /data/local/tmp/dhizuku_api2.dex
else
  # 创建空文件避免 classpath 报错
  adb shell "touch /data/local/tmp/dhizuku_api2.dex"
fi
adb push DhizukuClient.dex /data/local/tmp/DhizukuClient.dex

run() {
  adb shell "CLASSPATH=$CP app_process /system/bin $CLIENT $*"
}

echo "== status =="
run status

echo "== perm (auto-grant) =="
run perm

echo "== status after perm =="
run status

echo "== app-status =="
run app-status com.android.settings

echo "== camera off/on =="
run camera off
adb shell dumpsys device_policy | grep -iE 'camera|disableCamera' || true
run camera on

echo "== hide on/off =="
run hide com.android.settings on
run app-status com.android.settings
run hide com.android.settings off

echo "== suspend on/off =="
run suspend com.android.settings on
run app-status com.android.settings
run suspend com.android.settings off

echo "== block-uninstall on/off =="
run block-uninstall com.android.settings on
run app-status com.android.settings
run block-uninstall com.android.settings off

echo "== system setting set/unset =="
run system screen_off_timeout 600000
SDK=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$SDK" -ge 31 ]; then
  run system screen_off_timeout null
else
  echo "skip system null: SettingsProvider rejects null before API 31 (sdk=$SDK)"
fi

echo "== secure skip_first_use_hints set/unset =="
run secure skip_first_use_hints 1
run secure skip_first_use_hints null

echo "== restrictions =="
run restrictions

echo "== ALL TESTS PASSED =="
