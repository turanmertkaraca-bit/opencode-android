#!/bin/bash
# p29_package.sh — ship v0.29.0-p29: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo-p29
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p29-kit-stage

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
# opencode-android v0.29.0-p29

Install `opencode-p29-v0.29.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.28.0 → installs as an UPDATE, no uninstall.

## What P29 is — the double-open, the photo tray, the price tag

The model sheet can't double-open anymore (in-flight gate + instant open
from the cached catalog + refresh-in-place + a "loading models…" chip).
Photos are messenger-style now: multi-select, a thumb tray above the
composer with an ✕ on each, and ONE message carrying every image as file
parts (free-vision-model fallback per image). The cost hint above the
input prices the NEXT send before you commit (text + images + the context
re-read, worst case; "compact to pay less" at ≥50% window). /compact
lives in the ⌘ palette and the Σ popover — POST /session/{id}/summarize
(route verified in the bundled binary's OpenAPI). Terse replies (the
community i-have-adhd / caveman token-saver) toggle a managed block into
the project's AGENTS.md. Haptic ticks on the commit-y actions; the input
well gained the one hairline every other raised element already had.
199 JVM tests green (13 new).

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:

    cd apk-gradle && gradle assembleDebug

The JVM suite (199 tests) runs with `gradle testDebugUnitTest`.
docs/clipping-audit.md is the repeatable UI clipping checklist.
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p29-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz \
      "$DL"/opencode-p9-v0.9.0-debug.apk "$DL"/opencode-p9-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p29-v0.29.0-debug.apk"
cp -f "$STAGE/../p29-kit.tar.gz" "$DL/opencode-p29-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p29-v0.29.0-debug.apk opencode-p29-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p29-v0.29.0-debug.apk` — the app (v0.29.0-p29). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p29-kit.tar.gz` — full source snapshot (add your own
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
echo "P29 PACKAGE OK"
