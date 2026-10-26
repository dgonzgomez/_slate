#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -lt 1 || $# -gt 2 ]]; then echo 'Usage: bash scripts/install.sh DEVICE_ADDRESS_OR_ADB_SERIAL [APK_PATH]' >&2; exit 2; fi
slate_device="$1"
slate_apk="${2:-build/Slate-firetv.apk}"
slate_adb="${ADB:-adb}"
if ! command -v "$slate_adb" >/dev/null && [[ -x .tools/platform-tools/adb ]]; then slate_adb="$PWD/.tools/platform-tools/adb"; fi
[[ -f "$slate_apk" ]] || { echo "APK not found: $slate_apk" >&2; exit 1; }
# IP addresses may be supplied with or without the usual Fire OS ADB port.
if [[ "$slate_device" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then slate_device="$slate_device:5555"; fi
if [[ "$slate_device" == *:* ]]; then "$slate_adb" connect "$slate_device"; fi
"$slate_adb" -s "$slate_device" get-state >/dev/null
sdk_level=$("$slate_adb" -s "$slate_device" shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$sdk_level" =~ ^[0-9]+$ && "$sdk_level" -ge 22 ]] || { echo 'Slate needs Android-based Fire OS / Android API 22 or newer. Vega OS is unsupported.' >&2; exit 1; }
# -r preserves favorites/settings. A signature mismatch fails; never uninstall automatically.
"$slate_adb" -s "$slate_device" install -r "$slate_apk"
for permission in READ_LOGS DUMP; do
  if ! "$slate_adb" -s "$slate_device" shell pm grant dev.slate "android.permission.$permission"; then
    echo "Fire OS denied $permission. Slate can still be opened as an app; Home-button behavior may be restricted." >&2
  fi
done
if ! "$slate_adb" -s "$slate_device" shell appops set dev.slate GET_USAGE_STATS allow; then
  echo 'Usage access was not available on this firmware.' >&2
fi
"$slate_adb" -s "$slate_device" shell am start -n dev.slate/.MainActivity
printf '%s\n' 'Slate installed. Test Home from another app. Amazon Home remains enabled.'
