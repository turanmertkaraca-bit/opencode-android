#!/bin/bash
# p30_package.sh — ship v0.30.0-p30: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p30-kit-stage

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
# opencode-android v0.30.0-p30

Install `opencode-p30-v0.30.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.29.0 → installs as an UPDATE, no uninstall.

## What P30 is — the setting that listens, the honest price line, the delete button

Terse replies (the i-have-adhd token saver) now work MID-conversation:
the toggle is a pure app preference (nothing written into your projects,
no git leaks — the P29 AGENTS.md block is stripped on first boot, user
content preserved), and the preference reaches the model as a one-line
<system-reminder> riding your NEXT message in THAT chat (no extra turn;
re-announced after /compact). The cost hint above the input stopped
clipping: left-aligned with the input well, shorter format
("≈ 2k new · next $0.0500 · ctx 48k"), width-bound by test, ellipsize
safety net, and the ≥50% nudge names the button: "/compact saves".
Long-press a project card → Delete project: a confirm dialog with the
exact path, then a guarded delete (roots/mount points/app dir/ancestors
refused, >20k entries abort BEFORE touching a file, symlinks unlinked
never followed, the serving project's server stops first). Haptic tick
on the deck long-press and the delete confirm.
217 JVM tests green (18 new).

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:

    cd apk-gradle && gradle assembleDebug

The JVM suite (217 tests) runs with `gradle testDebugUnitTest`.
docs/clipping-audit.md is the repeatable UI clipping checklist.
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p30-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz \
      "$DL"/opencode-p9-v0.9.0-debug.apk "$DL"/opencode-p9-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p30-v0.30.0-debug.apk"
cp -f "$STAGE/../p30-kit.tar.gz" "$DL/opencode-p30-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.29.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p30-v0.30.0-debug.apk opencode-p30-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p30-v0.30.0-debug.apk` — the app (v0.30.0-p30). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p30-kit.tar.gz` — full source snapshot (add your own
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
echo "P30 PACKAGE OK"
