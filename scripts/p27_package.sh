#!/bin/bash
# p27_package.sh — ship v0.27.0-p27: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p27-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/" 2>/dev/null || true
mkdir -p "$STAGE/apk-gradle/scripts" "$STAGE/apk-gradle/docs"
for f in "$PROJ"/scripts/*; do cp -f "$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true; done
for f in "$PROJ"/docs/*; do cp -f "$f" "$STAGE/apk-gradle/docs/" 2>/dev/null || true; done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.27.0-p27

Install `opencode-p27-v0.27.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.26.0 → installs as an UPDATE, no uninstall.

## What P27 is — the "it feels finished" release

Stable taps under streaming: the live edit tree is a persistent card that
updates its rows IN PLACE while the agent works — taps can no longer be
eaten by a rebuild. Tap the head to PIN it open; unpin returns to the
idle auto-collapse; selection pins too; the peek stays open and live-updates.
Resume-current: rows that arrived while the app was backgrounded are on
screen the moment you return (a full repaint on every fresh bind — the
home-button path included). Curated rootfs (~50 MB off the Debian layer
at install; npm/apt caches swept every boot; cold-boot budget now recorded
in the incident log). AMOLED design system: true-black base, one calm blue
accent family, hairline-raised surfaces, one sheet style app-wide — plus
tappable file mentions in the agent's answers (opens the existing Files
viewer; back returns to the same scroll position).

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:

    cd apk-gradle && gradle assembleDebug

The JVM suite (166 tests) runs with `gradle testDebugUnitTest`.
docs/clipping-audit.md is the repeatable UI clipping checklist.
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p27-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL/opencode-p9-v0.9.0-debug.apk" "$DL/opencode-p9-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p27-v0.27.0-debug.apk"
cp -f "$STAGE/../p27-kit.tar.gz" "$DL/opencode-p27-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p27-v0.27.0-debug.apk opencode-p27-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p27-v0.27.0-debug.apk` — the app (v0.27.0-p27). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p27-kit.tar.gz` — full source snapshot (add your own
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
echo "P27 PACKAGE OK"
