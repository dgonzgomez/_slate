#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Standard Android SDK/JDK paths work on macOS, Linux, and WSL. Bundled tools
# remain a fallback for the existing developer workspace.
if [[ -z "${JAVA_HOME:-}" && -d .tools/jdk-17.0.21+9/Contents/Home ]]; then
  export JAVA_HOME="$PWD/.tools/jdk-17.0.21+9/Contents/Home"
fi
[[ -z "${JAVA_HOME:-}" ]] || export PATH="$JAVA_HOME/bin:$PATH"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$PWD/.tools/android-sdk}}"
compile_sdk="${SLATE_COMPILE_SDK:-35}"
build_tools="${SLATE_BUILD_TOOLS:-36.0.0}"
slate_tools="$sdk_root/build-tools/$build_tools"
slate_platform="$sdk_root/platforms/android-$compile_sdk/android.jar"
for tool in aapt d8 zipalign apksigner; do
  [[ -x "$slate_tools/$tool" ]] || { echo "Missing $tool. Install Android build-tools;$build_tools in ANDROID_HOME." >&2; exit 1; }
done
[[ -f "$slate_platform" ]] || { echo "Install Android platforms;android-$compile_sdk in ANDROID_HOME." >&2; exit 1; }
for tool in javac jar keytool zip python3; do command -v "$tool" >/dev/null || { echo "Missing $tool" >&2; exit 1; }; done
# A clean staging directory prevents deleted Java classes from leaking into APKs.
rm -rf build/staging
mkdir -p build/staging/classes build/staging/dex build/staging/generated/dev/slate
python3 scripts/prepare_build.py
"$slate_tools/aapt" package -f -M build/staging/AndroidManifest.xml -S app/res -A app/assets -I "$slate_platform" -F build/staging/unsigned.apk
javac -source 8 -target 8 -classpath "$slate_platform" -d build/staging/classes app/src/dev/slate/*.java build/staging/generated/dev/slate/BuildConfig.java
jar cf build/staging/classes.jar -C build/staging/classes .
"$slate_tools/d8" --lib "$slate_platform" --min-api 22 --output build/staging/dex build/staging/classes.jar
zip -jq build/staging/unsigned.apk build/staging/dex/*.dex
"$slate_tools/zipalign" -f 4 build/staging/unsigned.apk build/staging/aligned.apk
slate_key="${SLATE_KEYSTORE:-$PWD/.tools/debug.keystore}"
export SLATE_STORE_PASSWORD="${SLATE_STORE_PASSWORD:-android}"
export SLATE_KEY_PASSWORD="${SLATE_KEY_PASSWORD:-$SLATE_STORE_PASSWORD}"
slate_alias="${SLATE_KEY_ALIAS:-androiddebugkey}"
if [[ ! -f "$slate_key" ]]; then
  if [[ "${SLATE_RELEASE:-0}" == 1 ]]; then echo 'Release signing key is missing; refusing to generate a replacement.' >&2; exit 1; fi
  mkdir -p "$(dirname "$slate_key")"
  keytool -genkeypair -keystore "$slate_key" -storepass:env SLATE_STORE_PASSWORD -keypass:env SLATE_KEY_PASSWORD -alias "$slate_alias" -dname 'CN=Slate Development' -keyalg RSA -validity 10000
fi
"$slate_tools/apksigner" sign --ks "$slate_key" --ks-pass env:SLATE_STORE_PASSWORD --key-pass env:SLATE_KEY_PASSWORD --ks-key-alias "$slate_alias" --out build/staging/slate.apk build/staging/aligned.apk
"$slate_tools/apksigner" verify --verbose build/staging/slate.apk
# Only replace the last good artifact once the entire build succeeds.
cp build/staging/slate.apk build/slate.apk
cp build/staging/slate.apk build/Slate-firetv.apk
python3 - <<'PY'
import hashlib, pathlib
p = pathlib.Path('build/Slate-firetv.apk')
pathlib.Path('build/Slate-firetv.apk.sha256').write_text(hashlib.sha256(p.read_bytes()).hexdigest() + '  Slate-firetv.apk\n')
PY
ls -lh build/Slate-firetv.apk
