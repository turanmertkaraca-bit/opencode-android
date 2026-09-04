#!/bin/bash
# p18_package.sh — ship v0.18.0-p18: replace the APK/kit in download/,
# refresh SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p18-kit-stage

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
         p17_package.sh p17_gh_ship.sh p18_toolchain_fix.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$PROJ/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null \
    || cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.18.0-p18

Install `opencode-p18-v0.18.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.17.0 → installs as an UPDATE, no uninstall.

## What P18 is — the unstoppable sandbox

- THE SANDBOX HEALS ITSELF: when the opencode server process dies, the
  service auto-restarts it in place (backoff 1.5s → 4s → 8s), kills any
  stale port squatter first so a zombie listener can never wedge the
  respawn, and the chat stays attached (a ♻ row: "sandbox auto-recovered
  — this chat is still attached"). Sessions live on disk — no cold boot.
  3 deaths inside 10 min trips a crash-loop guard that stops the cycle
  and says so.
- BLACK BOX: every server death writes files/sandbox-diag.log (timestamp
  · exit code · last output · free memory). Settings → keep alive →
  "Sandbox incident log" shows it in one tap.
- SEND TIMEOUTS CAN'T KILL A THINKING RUN: 15-minute read budget, a
  timeout is soft-landed ("still watching the run"), the SSE feed keeps
  rendering, the run is never blindly re-POSTed (no double token burn),
  broken pipes get human wording, raw java.net text is banned from chat.
- THE Σ PILL EXPLAINS ITSELF: the top counter is the chat's cumulative
  token + cost sum (every turn re-sends the conversation — that's why it
  only climbs). Tap it → breakdown + context depth + verdict, and at
  ≥50k depth a "＋ Fresh chat" button that resets per-turn cost in one
  tap (old chat stays in Sessions).
- 14 new JVM regression tests (47 total) pin the timeout classifier,
  crash-streak guard, backoff, diag format and Σ formatting.
- CARRIED FROM P17: edit shower, screenshot vision (◉ chip + free-model
  ladder + image bubbles), cool idle, dark icon, Zen ≠ Go keys, LIVE
  Files, DeX.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p18-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p18-v0.18.0-debug.apk"
cp -f "$STAGE/opencode-p18-kit.tar.gz" "$DL/opencode-p18-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p18-v0.18.0-debug.apk \
          opencode-p18-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
