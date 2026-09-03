#!/bin/bash
# p8_package.sh — ship v0.8.0-p8: replace p7 APK/kit in download/, refresh
# SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p8-kit-stage

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
         p6_scan2.py p6_build.sh p6_package.sh p7_package.sh p8_package.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.8.0-p8

Install `opencode-p8-v0.8.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0/v0.7.0 → installs as an UPDATE, no uninstall.

## What P8 is — the "make it beautiful, snappy, project-centric" release

- NEW HOME: your projects as CREDIT-CARD gradient cards in a vertical
  snap carousel (DeckView — custom framework-only pager). Swipe up/down,
  one card per gesture, neighbors peek + shrink + dim. Animated dots,
  pulsing status pill, staggered entrances.
- PER-PROJECT SANDBOXES: tapping a card opens that project's OWN sandbox
  — the opencode server is (re)started with the project folder as its
  working directory, so the agent's file tools, sessions and shell cwd
  are rooted at exactly that folder. The last project is pre-warmed at
  boot: opening its card is INSTANT (no restart).
- ＋ ghost card → folder picker (browse /sdcard, create folders) → new
  project card. Long-press a card → open / rename / remove (files are
  never touched).
- SETTINGS, RESTYLED: rounded sections, staggered entrance, switch rows,
  and a SANDBOX DOCTOR that shows what the agent can actually run
  (opencode version, busybox, bash/git shims, python3/node/gcc, PATH).
  Default-model picker (all models), API keys, DNS bridge, animations
  toggle (also honors system "remove animations").
- MIDNIGHT DECK PALETTE: deep blue-black background, indigo accent,
  glassy surfaces; fast activity transitions app-wide (≤ 230 ms); chat
  got entrance animations for new rows, a pulsing three-dot typing
  indicator, a "↓ latest" scroll pill, and spring feedback on send/mode
  chips. Zero libraries — still 100% framework views/APIs.
- Chat logic from P7 is unchanged: ⌘ palette (now + Projects/Settings),
  Build/Plan chip, collapsed thinking/tool cards, pinned permission
  card, all-models sheet, stop/abort, sessions, export.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p8-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p7-v0.7.0-debug.apk" "$DL/opencode-p7-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p8-v0.8.0-debug.apk"
cp -f "$STAGE/opencode-p8-kit.tar.gz" "$DL/opencode-p8-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p8-v0.8.0-debug.apk \
          opencode-p8-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
