#!/bin/bash
# p18_toolchain_fix.sh — re-provision gradle 8.9 + android sdk (p0 script's
# steps without the pipefail foot-gun). Idempotent.
set -e
TOOLS=/home/z/p0-tools
SDK=$TOOLS/android-sdk
mkdir -p "$TOOLS" "$SDK"
cd "$TOOLS"

if [ ! -x gradle-8.9/bin/gradle ]; then
  echo "==> downloading gradle-8.9-bin.zip"
  [ -f gradle-8.9-bin.zip ] || curl -fsSL --retry 3 -o gradle-8.9-bin.zip \
    https://services.gradle.org/distributions/gradle-8.9-bin.zip
  echo "==> unzipping gradle"
  rm -rf gradle-8.9
  unzip -q gradle-8.9-bin.zip
fi
echo "gradle: $(gradle-8.9/bin/gradle --version 2>/dev/null | grep Gradle | head -1)"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> downloading commandlinetools"
  [ -f cmdtools.zip ] || curl -fsSL --retry 3 -o cmdtools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  echo "==> unzipping cmdline-tools"
  rm -rf "$TOOLS/cmdt-tmp" "$SDK/cmdline-tools/latest"
  unzip -q cmdtools.zip -d "$TOOLS/cmdt-tmp"
  mkdir -p "$SDK/cmdline-tools"
  mv "$TOOLS/cmdt-tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TOOLS/cmdt-tmp"
fi

export ANDROID_HOME="$SDK"
echo "==> accepting licenses"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
echo "==> installing platforms;android-34 + build-tools;34.0.0 + platform-tools"
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null

echo "==> verify"
ls "$SDK/platforms" "$SDK/build-tools" 2>/dev/null
touch /home/z/p0-toolchain.DONE
echo "ALL DONE"
