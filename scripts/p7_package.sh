#!/bin/bash
# p7_package.sh — ship v0.7.0-p7: replace p6 APK/kit in download/, refresh
# SHA256SUMS + README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p7-kit-stage

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/"
mkdir -p "$STAGE/apk-gradle/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p4_scan_binary.py \
         p4_scan_binary2.py p5_scan_binary.py p5_scan2.py p6_scan_binary.py \
         p6_scan2.py p6_build.sh p6_package.sh ElfGateTest.java TestTarGz.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.7.0-p7

Install `opencode-p7-v0.7.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0 → installs as an UPDATE, no uninstall needed.

## What P7 is

A from-scratch rewrite of the UI around ONE idea: opening the app opens
the chat — like the opencode TUI.

- Launch → boot log (one-time unpack on a fresh install) → chat. No
  wizard, no setup cards, no manual binary import (the agent binary is
  bundled in the APK), no sandbox gate. The proot/Alpine sandbox is GONE:
  the bundled agent is an Android (bionic/NDK) build and runs natively in
  the app's exec-allowed storage — the Termux pattern, faster than proot.
- ⌘ button (top right) = the TUI's Ctrl+P: new chat, sessions, model
  picker, Build/Plan toggle, API keys, server logs & shell, restart
  server, expand/collapse all cards, copy last response, export chat.
- Build/Plan chip in the composer = the TUI's Tab (agent is sent with
  every message).
- "✦ Thinking" reasoning cards and tool-call cards are COLLAPSED by
  default; tap to expand; errors auto-expand; ⌘ has expand/collapse all.
- Permission asks (bash, edit, webfetch…) appear as a pinned card above
  the composer: Allow once / Always / Deny — even works in the
  background via the foreground-service notification.
- Model chip → EVERY provider/model the server knows, with search;
  picking sets the per-message model AND the server default.
- API keys are configured in the app (⌘ → API keys): provider keys →
  auth.json, plus custom OpenAI-compatible endpoints (OpenRouter, Groq,
  Ollama, LM Studio, vLLM…).
- Diagnostics (⌘ → Server logs & shell): live server log, native shell
  console (busybox + system tools, no proot), binary facts + re-unpack,
  optional "DNS bridge" (local CONNECT proxy) for exotic VPN/DNS setups,
  and SAF import of your own static arm64 tools into bin/ (git, rg…).
- Crashes are captured to last-crash.txt and shown on next boot — no
  more silent "going into chat crashes the app".

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p7-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p6-v0.6.0-debug.apk" "$DL/opencode-p6-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p7-v0.7.0-debug.apk"
cp -f "$STAGE/opencode-p7-kit.tar.gz" "$DL/opencode-p7-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p7-v0.7.0-debug.apk \
          opencode-p7-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
