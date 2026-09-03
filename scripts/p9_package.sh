#!/bin/bash
# p9_package.sh — ship v0.9.0-p9: replace p8 APK/kit in download/, refresh
# SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p9-kit-stage

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/" 2>/dev/null || true
mkdir -p "$STAGE/apk-gradle/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p4_scan_binary.py \
         p4_scan_binary2.py p5_scan_binary.py p5_scan2.py p6_scan_binary.py \
         p6_scan2.py p6_build.sh p6_package.sh p7_package.sh p8_package.sh \
         p9_package.sh p9_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$PROJ/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null \
    || cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.9.0-p9

Install `opencode-p9-v0.9.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.8.0 → installs as an UPDATE, no uninstall.

## What P9 is — the "graphite" release (monochrome + fixes + pkg)

- MONOCHROME "graphite" theme: hierarchy from brightness steps, no hue —
  graphite credit-card gradients, dark-gray user bubble, hairline rules.
  One desaturated clay red is reserved for errors only.
- MODEL PICKER, FIXED: root cause found by scanning the shipped binary —
  a provider WITHOUT credentials is never loaded, so /config/providers
  lists its models but every send fails with "Model not found". The
  picker now sorts keyed providers first, marks "(no key)" providers,
  and offers an inline "paste key & use" dialog right from the picker.
  OpenCode Zen ("opencode") is now the FIRST provider in API keys — the
  requested opencode API key finally has a home. Saving a key restarts
  the server so the provider actually loads.
- ERROR GUIDANCE: model-not-found / 401 / 429 failures now explain the
  fix ("provider X has NO API key — API keys → paste") instead of a raw
  HTTP code. The send retry no longer silently drops the selected model.
- PERMISSIONS + STOP, FIXED: Allow / Always / Deny and ■ stop used to
  queue behind the in-flight send POST (single executor) and never fired
  mid-turn — they now run on a dedicated control lane, always responsive.
- SANDBOX DOCTOR, FIXED: results actually replace "checking…" (the old
  dialog called setView() after show() — a no-op), now lists pkg too.
- PACKAGE MANAGER: `pkg list` / `pkg install jq|fzf|yq|gh|shfmt|lazygit|glow`
  inside the sandbox shell (busybox-wget powered shim) + one-tap
  "Agent tools" installs in Diagnostics. Static aarch64 catalog, verified.
- COST METER: per-message "model · ⇅ tokens · $cost" footer, live session
  total "$X.XXXX · Nk tok" in the chat header (delta-accurate under SSE).
- CHAT, GROUNDED & SMALLER: 14sp type, tighter bubbles, assistant blocks
  anchored on a hairline left rule, tool cards with mono names + boxed
  RUN/DONE/ERR badges (RUN breathes) and inset dark wells for input/
  output, smooth follow-scroll, spring send button.
- PROJECT DECK: cards sit together (no more one-card-per-screen voids),
  a REAL fling always advances one card (fast swipes finally work), and
  the misleading horizontal dots are now a vertical tick rail.
- QA BRIDGE (Settings → developer): the "companion app" idea, in-app —
  off by default; paste a fine-grained GitHub token + repo and the app
  polls qa/commands.txt (shot / log / state / open <screen> / restart /
  toast) and uploads screenshots of its own windows + diagnostics back
  to the repo. Development with real eyes on a real device.
- Watchdog silence window 3.5 s → 12 s (long tool runs no longer look
  stalled). Same zero-dependency, framework-only codebase.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p9-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p8-v0.8.0-debug.apk" "$DL/opencode-p8-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p9-v0.9.0-debug.apk"
cp -f "$STAGE/opencode-p9-kit.tar.gz" "$DL/opencode-p9-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p9-v0.9.0-debug.apk \
          opencode-p9-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
