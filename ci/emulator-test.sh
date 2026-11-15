#!/usr/bin/env bash
# 在 GitHub Actions Android 模拟器上安装 Dhizuku、设为 DeviceOwner，
# 然后用 app_process 运行 DhizukuClient.dex 的全套命令。
set -euo pipefail

PKG=com.rosan.dhizuku
DO_COMPONENT="$PKG/.server.DhizukuDAReceiver"
CLIENT=DhizukuAppProcessClient
CP=/data/local/tmp/dhizuku_api.dex:/data/local/tmp/dhizuku_api2.dex:/data/local/tmp/DhizukuClient.dex

debug_dump() {
  adb exec-out screencap -p > screenshot.png 2>/dev/null || true
  adb logcat -d > logcat.txt 2>/dev/null || true
  adb shell dumpsys device_policy > device_policy.txt 2>/dev/null || true
  adb shell dumpsys package "$PKG" > package.txt 2>/dev/null || true
  adb shell "CLASSPATH=$CP app_process /system/bin $CLIENT help" > help.txt 2>&1 || true
}
trap debug_dump EXIT

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 2
done
adb shell input keyevent 82 || true
adb shell wm dismiss-keyguard || true

echo "== install Dhizuku =="
adb install -r Dhizuku.apk

echo "== set device owner =="
adb shell dpm set-device-owner "$DO_COMPONENT"
sleep 2

echo "== launch Dhizuku once =="
adb shell am start -n "$PKG/.ui.activity.SettingsActivity" || true
sleep 5
adb shell input keyevent 3 || true

echo "== extract Dhizuku dex and push client =="
rm -rf dex
mkdir -p dex
unzip -o Dhizuku.apk 'classes*.dex' -d dex
adb push dex/classes.dex /data/local/tmp/dhizuku_api.dex
adb push dex/classes2.dex /data/local/tmp/dhizuku_api2.dex
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
run system screen_off_timeout 600000 || true
run system screen_off_timeout null || true

echo "== secure skip_first_use_hints set/unset =="
run secure skip_first_use_hints 1
run secure skip_first_use_hints null

echo "== restrictions =="
run restrictions

echo "== ALL TESTS PASSED =="
