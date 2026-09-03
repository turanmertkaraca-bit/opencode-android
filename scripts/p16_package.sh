#!/bin/bash
# p16_package.sh — ship v0.16.0-p16: replace the APK/kit in download/,
# refresh SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p16-kit-stage

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
         p9_package.sh p9_gh_ship.sh p16_package.sh p16_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$PROJ/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null \
    || cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.16.0-p16

Install `opencode-p16-v0.16.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.15.0 → installs as an UPDATE, no uninstall.

## What P16 is — Zen ≠ Go keys fixed + LIVE Files + DeX

- THE KEY FIX: OpenCode Zen (`opencode`) and OpenCode Go (`opencode-go`)
  are SEPARATE providers with SEPARATE keys — the catalog proves it
  (97 zen models vs 34 go models). API keys now has BOTH rows with ids
  matching the catalog exactly; a first-time save auto-restarts the
  server so the provider goes live. The model picker grows a "＋ key"
  chip on every unconfigured provider header, the open sheet refreshes
  in place when you come back from pasting it, and a ↻ button re-fetches
  any time. 401/402 errors now say WHICH key is missing — Zen and Go
  keys are separate.
- FILES IS LIVE: the project is watched recursively (depth 6 / 220 dirs
  cap, hidden dirs skipped). A live rail streams every change (new/mod/
  del · path · age, tap to jump), rows touched inside the open folder
  re-render instantly with a hot ● badge (fades ~10 s), scroll position
  survives every update, and a ● LIVE pill pauses/resumes watching.
- DeX: resizeableActivity + configChanges everywhere — window resizes
  never restart an activity or kill a stream. Wide windows (≥600 dp)
  get the desktop silhouette: centered ~720 dp chat column, 760 dp
  model sheet, same for Files. Keyboard: Ctrl+M models, Ctrl+K palette,
  Ctrl+J sessions.
- ANIMATIONS & QoL: staggered first-bind unfold in the model sheet
  (recycled rows stay still), haptic send, doctor documents the Zen/Go
  split. All gated by the existing animations toggle.
- CARRIED FROM P14/P15: bash-shim single-line invariant (+ tests), P12a
  picker semantics (live gate + self-heal), unattended auto-allow,
  session-spend pill, proot dir bootstrap + PROOT_TMP_DIR preloader,
  environment detection + welcome row, Debian install.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p16-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p9-v0.9.0-debug.apk" "$DL/opencode-p9-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p16-v0.16.0-debug.apk"
cp -f "$STAGE/opencode-p16-kit.tar.gz" "$DL/opencode-p16-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p16-v0.16.0-debug.apk \
          opencode-p16-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
