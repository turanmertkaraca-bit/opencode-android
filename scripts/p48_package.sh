#!/bin/bash
# p48_package.sh — ship v0.48.0-p48: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. Same layout rules as p47.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p48-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

# sanity: version + the bundled server binary must be the pinned one
grep -q "versionCode 50" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.48.0-p48" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
ASSET_SHA=$(sha256sum "$PROJ/app/src/main/assets/oc_pkg.bin" | cut -d' ' -f1)
case "$ASSET_SHA" in 6384e745*) ;; *) echo "oc_pkg sha mismatch: $ASSET_SHA"; exit 1;; esac

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
# opencode-android v0.48.0-p48

Install `opencode-p48-v0.48.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.47.0 → installs as an UPDATE, no uninstall.

## What P48 is — the smooth release

1. CAPPED REVEAL RATES: the pacer's profiles put a hard ceiling on the
   glide — answers ≤900 chars/s, thinking rows a calm ≤170 chars/s
   (faster than reading speed, followable, no strobe). The thinking
   row settles in one paint the moment the answer starts.
2. FIXED-LENGTH THINKING WINDOW: the collapsed thought card is exactly
   three lines (minLines = maxLines) — the sliding reasoning window
   can no longer change the card's height and bounce the list.
3. ANTI-JITTER SCROLL PIN: the app's own corrective scrolls no longer
   flip the pin (a bracket the listener ignores); an upward drag always
   unpins; corrections run after layout.
4. BACKGROUND SNAP: the paint ticker stops on pause; on resume,
   everything that streamed while away lands SETTLED (no replay glide).
5. 516 JVM tests green (13 new P48 pins).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p48-kit.tar.gz" -C "$STAGE" .

# --- download/: replace the p47 payload in place ---
cp -f "$OUT" "$DL/opencode-p48-v0.48.0-debug.apk"
cp -f "$STAGE/../p48-kit.tar.gz" "$DL/opencode-p48-kit.tar.gz"
# the server tarball stays the same pinned artifact
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p48-v0.48.0-debug.apk opencode-p48-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz HANDOFF.md > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p48-v0.48.0-debug.apk` — the app (v0.48.0-p48). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p48-kit.tar.gz` — full source snapshot (add your own
  oc_pkg.bin asset to build; see kit README).
- `opencode-linux-arm64-android.tar.gz` — the upstream opencode server
  binary tarball (also what the APK bundles as oc_pkg.bin).
- `archive-p0-p1.tar.gz` — the P0/P1 rig archive (history).
- `HANDOFF.md` — continuity notes for the next AI session (contains a
  token; keep it private, never commit it).
- `SHA256SUMS.txt` — checksums.

Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases
EOF

ls -la "$DL" | rg "p48|tar.gz$|README|SHA256|HANDOFF"
echo "P48 PACKAGE OK"
