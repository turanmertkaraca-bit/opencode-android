#!/usr/bin/env bash
# ============================================================================
# p0_setup_toolchain.sh — Android build toolchain bootstrap (rootless)
#
# Installs into $HOME/p0-tools (no sudo needed):
#   - Gradle 8.9
#   - Android SDK cmdline-tools + platforms;android-34 + build-tools;34.0.0
#     + platform-tools
#
# Log: $HOME/p0-toolchain.log ; sentinel: $HOME/p0-toolchain.DONE
# ============================================================================
set -euo pipefail
LOG="$HOME/p0-toolchain.log"
touch "$LOG"
exec > >(tee -a "$LOG") 2>&1
say() { echo "==> $*"; }

TOOLS="$HOME/p0-tools"
SDK="$HOME/p0-tools/android-sdk"
mkdir -p "$TOOLS" "$SDK"

say "JDK in use"
java -version 2>&1 | head -2

cd "$TOOLS"

# ---- Gradle 8.9 -------------------------------------------------------------
if [ ! -x gradle-8.9/bin/gradle ]; then
  say "downloading gradle-8.9-bin.zip (~130 MB)"
  [ -f gradle-8.9-bin.zip ] || curl -fL --retry 3 -o gradle-8.9-bin.zip \
    https://services.gradle.org/distributions/gradle-8.9-bin.zip
  say "unzipping gradle"
  unzip -q gradle-8.9-bin.zip
fi
say "gradle: $(gradle-8.9/bin/gradle --version 2>/dev/null | grep Gradle | head -1 || echo pending)"

# ---- Android cmdline-tools --------------------------------------------------
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  say "downloading commandlinetools-linux-11076708 (~150 MB)"
  [ -f cmdtools.zip ] || curl -fL --retry 3 -o cmdtools.zip \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  say "unzipping cmdline-tools"
  rm -rf "$TOOLS/cmdt-tmp" "$SDK/cmdline-tools/latest"
  unzip -q cmdtools.zip -d "$TOOLS/cmdt-tmp"
  mkdir -p "$SDK/cmdline-tools"
  mv "$TOOLS/cmdt-tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$TOOLS/cmdt-tmp"
fi

# ---- Licenses + SDK packages -------------------------------------------------
export ANDROID_HOME="$SDK"
say "accepting licenses"
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
say "installing platforms;android-34 + build-tools;34.0.0 + platform-tools"
"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools" >/dev/null

say "verify"
ls "$SDK/platforms" "$SDK/build-tools" 2>/dev/null

say "ALL DONE"
touch "$HOME/p0-toolchain.DONE"
