#!/bin/bash
# p10_package.sh — ship v0.10.0-p10: replace p9 APK/kit in download/,
# refresh SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p10-kit-stage

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
         p9_package.sh p10_package.sh TestSandbox.java ElfGateTest.java \
         TestTarGz.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.10.0-p10

Install `opencode-p10-v0.10.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0-v0.9.0 → installs as an UPDATE, no uninstall.

## What P10 is — the "self-tested polish" release

Every fix in this release was verified by an automated self-test harness
that renders the app's REAL views and drives its REAL HTTP calls:

- PERMISSION BUTTONS FIXED (the big one): Allow / Always allow / Deny
  were dead because the reply ran on the same single worker thread as
  the message POST — which blocks until the agent run finishes, and
  permissions always arrive MID-RUN. Replies now run on their own pool
  (verified end-to-end: tap → POST /permission/{id}/reply {"reply":...}),
  with v2 + legacy endpoint fallbacks and visible allow/deny toasts.
- FAST FLING FIXED on the project deck: a quick flick now ALWAYS lands on
  the next (or previous) card — ViewPager's direction rule anchored at
  the page where the gesture started. Slow drags still commit past the
  halfway point. 5 regression tests cover the exact complaints.
- CARDS STAY TOGETHER: the deck is a stacked wallet now — per-card stride
  is card height + a small gap instead of a full screen, neighbors peek
  above/below the active card, dead space goes where neighbors peek.
- CHAT BLOCKS REDESIGNED: tool calls are icon-disc cards (per-tool color:
  shell, read, write, edit, patch, search, webfetch, plan, sub-agent)
  with status line, tap-anywhere expand, rounded code blocks for
  INPUT/OUTPUT; THINKING cards are violet-tinted with italic voice;
  the permission ask is an indigo approval sheet with big Deny /
  Always allow / Allow buttons.
- OPENCODE ZEN KEY: the API keys screen now lists OpenCode Zen first
  (opencode's own provider — one key unlocks all its models).
- EASIER NAVIGATION: visible back button in the chat header (chat →
  project deck).

## Self-test evidence (shipped in the kit, tests/ in apk-gradle)

- DeckSnapTest (5): fast fling advances / goes back, half-drag commits,
  small nudge settles, clamps at the last card.
- PermissionFlowTest (2): Allow/Deny/Always round-trips against a mock
  opencode server on 127.0.0.1:4096 — the exact endpoint + reply values
  extracted from the shipped v1.18.25 binary.
- ScreenTest (3): renders home deck, chat transcript (tool/thinking
  cards), permission sheet, settings, API keys to real PNGs.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p10-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p9-v0.9.0-debug.apk" "$DL/opencode-p9-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p10-v0.10.0-debug.apk"
cp -f "$STAGE/opencode-p10-kit.tar.gz" "$DL/opencode-p10-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p10-v0.10.0-debug.apk \
          opencode-p10-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
