#!/bin/bash
# p17_package.sh — ship v0.17.0-p17: replace the APK/kit in download/,
# refresh SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p17-kit-stage

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
         p17_package.sh p17_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$PROJ/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null \
    || cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.17.0-p17

Install `opencode-p17-v0.17.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.16.0 → installs as an UPDATE, no uninstall.

## What P17 is — the final-polish round

- THE EDIT SHOWER (live directory changes in the chat itself): one slim
  card in the transcript while the agent works — `● LIVE · 3 files ·
  src/App.tsx` on one line. Auto-expands ONLY while edits are fresh
  (staggered slide-ins of the newest touched files: ✎/＋/−, burst ×N,
  ages), auto-collapses ~4 s after the last change, settles after the
  run into a one-line `EDITS · N edits · M files` record. Tap a file →
  the PEEK: ≤11 numbered lines around the EXACT edited line (located
  from the edit tool's new-content snippet, tail fallback), focus
  marked ▸ — never the full file. Inotify event-driven, watched ONLY
  while the agent works; zero polling.
- SCREENSHOT VISION: new ◉ chip in the composer. Tries the server's
  native image part first (raw pixels to the agent); if the server
  can't take it, a FREE vision model from the Zen gateway describes the
  screenshot (kimi-k2.5-free → qwen3.6-plus-free → mimo-v2.5-free → …,
  keyless-friendly) and the description is fed to the agent as context.
  Images render as proper chat bubbles (rounded frame, caption, tap for
  full view); history image parts replay as bubbles too.
- COOL IDLE (the heat fix): the PARTIAL_WAKE_LOCK is no longer held for
  the whole server lifetime — it exists only while agent events flow,
  released on session.idle (Settings → keep alive → "Cool idle", default
  ON). Also killed: an INFINITE alpha animator that pulsed the veil's
  dot while the veil sat GONE 24/7 on the main screen.
- NEW ICON: dark blue-gray gradient base, subtle blue chevron with a
  soft glow, slate-gray underscore (the old flat #E6E6E6 was "too
  white"). Adaptive circle/squircle safe; monochrome kept.
- CARRIED FROM P16: Zen ≠ Go key rows + auto-restart + ＋ key chips,
  LIVE Files, DeX resizeable + centered desktop column + Ctrl+M/K/J,
  animations toggle, haptic send. Settings Version row finally bumped.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p17-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p16-v0.16.0-debug.apk" "$DL/opencode-p16-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p17-v0.17.0-debug.apk"
cp -f "$STAGE/opencode-p17-kit.tar.gz" "$DL/opencode-p17-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p17-v0.17.0-debug.apk \
          opencode-p17-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
