#!/bin/bash
# p11_package.sh — ship v0.11.0-p11: replace p9-named APK/kit in download/,
# refresh SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p11-kit-stage

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no big bundled assets) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/"
mkdir -p "$STAGE/apk-gradle/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p4_scan_binary.py \
         p4_scan_binary2.py p5_scan_binary.py p5_scan2.py p6_scan_binary.py \
         p6_scan2.py p6_build.sh p6_package.sh p7_package.sh p8_package.sh \
         p9_build.sh p10_package.sh p11_build.sh p11_package.sh TestP11.java \
         Compile.java ElfGateTest.java TestTarGz.java TestSandbox.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'P11EOF'
# opencode-android v0.11.0-p11

Install `opencode-p11-v0.11.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.10.0 → installs as an UPDATE, no uninstall.

## What P11 is — verified fixes for the latest field report

Every fix was verified against a REAL opencode v1.18.25 server (the same
binary the app embeds) before shipping — bug reproduced live, fix
validated live:

- "SELECTING FREE MODELS SAYS IT COULDN'T FIND THAT MODEL" — FIXED,
  ROOT-CAUSED. The server's free-model catalog ROTATES (P7-era picks like
  gpt-5-nano are gone; today's zen free list is nemotron / mimo / ling /
  muse / big-pickle) and the app kept sending the stale saved pick on
  every send → "Model not found" forever. The app now:
    (a) validates the saved pick against the server's live catalog before
        each send and clears it when no longer offered,
    (b) detects "Model not found" at run-time (the server fires it as a
        session.error with HTTP 200!) and self-heals + retries once with
        the server default,
    (c) chat model picks are PER-CHAT again — the old code also wrote them
        into the server-wide default config, poisoning future sessions.
- REQUEST FALLBACK no longer silently drops your model: retry order is
  model+agent → model → agent → bare, and if the server ignores the pick
  you get a visible note instead of a mystery model answering.
- ZEN FREE MODELS NEED NO KEY (probed live: they run with zero auth) —
  the model sheet marks the OpenCode Zen provider "free · no key needed",
  and the API-keys screen explains you only need a zen key for PAID zen
  models (console at opencode.ai/zen).
- STREAM FLAKE RETRY: zen streams die with "[504] Upstream idle timeout"
  on mobile networks (reproduced live); when nothing was rendered yet the
  app retries ONCE and tells you, instead of dumping a raw session error.
- SANDBOX DOCTOR: fixed the "checking…" forever bug (setView() after
  show() is ignored on Android). It now updates in place, per check, and
  mentions the keyless free models.
- All P9/P10 goodness stays: Alpine pkg package manager (pkg install
  python3 git nodejs …), realtime token-by-token streaming, redesigned
  chat blocks, deck fling fix, stacked cards, permission pool fix.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
P11EOF

tar -czf "$STAGE/opencode-p11-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p11-v0.11.0-debug.apk"
cp -f "$STAGE/opencode-p11-kit.tar.gz" "$DL/opencode-p11-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p11-v0.11.0-debug.apk \
          opencode-p11-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
