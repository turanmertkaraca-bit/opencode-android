#!/bin/bash
# p28_package.sh — ship v0.28.0-p28: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/fresh-p28
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p28-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
rm -f  "$STAGE/apk-gradle/local.properties"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/" 2>/dev/null || true
mkdir -p "$STAGE/apk-gradle/scripts" "$STAGE/apk-gradle/docs"
for f in "$PROJ"/scripts/*; do cp -f "$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true; done
for f in "$PROJ"/docs/*; do cp -f "$f" "$STAGE/apk-gradle/docs/" 2>/dev/null || true; done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.28.0-p28

Install `opencode-p28-v0.28.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.27.0 → installs as an UPDATE, no uninstall.

## What P28 is — the P27 field report, fixed

Tappable file mentions actually TAP now (P27 rendered them; nothing fired
them — no movement method on selectable rows; taps are routed by hit-testing
so selection/copy/scroll drags keep their native handling). The thinking
dots are back ABOVE the composer (the P27 transcript frame broke the old
runtime parenting; the slot is declared in the layout now). The live-card
peek is big-file-proof: (len,mtime) memo, 8 KB tail reads for streamed
appends with honest line numbers, 2 MB hard cap, and the line the agent is
editing is highlighted in accent. Faster cold boot: the 175 MB binary is no
longer read+hashed on every launch (fingerprint memoized by len+mtime).
Binary and sandbox unchanged from P27 (upstream structural; rootfs trim +
boot hygiene already shipped). 183 JVM tests green.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:

    cd apk-gradle && gradle assembleDebug

The JVM suite (183 tests) runs with `gradle testDebugUnitTest`.
docs/clipping-audit.md is the repeatable UI clipping checklist.
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p28-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL/opencode-p27-v0.27.0-debug.apk" "$DL/opencode-p27-kit.tar.gz" \
      "$DL/opencode-p9-v0.9.0-debug.apk" "$DL/opencode-p9-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p28-v0.28.0-debug.apk"
cp -f "$STAGE/../p28-kit.tar.gz" "$DL/opencode-p28-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p28-v0.28.0-debug.apk opencode-p28-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p28-v0.28.0-debug.apk` — the app (v0.28.0-p28). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p28-kit.tar.gz` — full source snapshot (add your own
  oc_pkg.bin asset to build; see kit README).
- `opencode-linux-arm64-android.tar.gz` — the upstream opencode server
  binary tarball (also what the APK bundles as oc_pkg.bin).
- `archive-p0-p1.tar.gz` — the P0/P1 rig archive (history).
- `SHA256SUMS.txt` — checksums.

Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases
EOF

COUNT=$(ls -1 "$DL" | wc -l)
echo "download/ file count: $COUNT (must be 6)"
[ "$COUNT" -eq 6 ] || { echo "FILE COUNT WRONG"; exit 1; }
ls -la "$DL"
echo "P28 PACKAGE OK"
