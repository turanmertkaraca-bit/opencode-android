#!/bin/bash
# p40_package.sh — ship v0.40.0-p40: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p40-kit-stage

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
# opencode-android v0.40.0-p40

Install `opencode-p40-v0.40.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.39.0 → installs as an UPDATE, no uninstall.

## What P40 is — the amnesia cured at its source

P39 made the chat amnesia observable and survivable. P40 ends it: the
rig (the exact bundled server under emulation, with a payload-logging
mock provider) reproduced the field bug end to end and found the real
mechanism.

THE MECHANISM, PROVEN: the server keeps every message in a SQLite store,
and opening a session paints the whole thread from it — that is why the
history was always visible on screen. But the payload the model receives
assembles from the LAST compaction root — a marker row a summarize run
leaves behind; everything before it is deliberately excluded. When the
compacting model returns an EMPTY reply, the marker is still written,
with no text. From then on every payload is system prompt + a dangling
prompt + the current turn: flat tokens, and the model answers as if the
chat were fresh. Kills, restarts, and re-compacting do not cause or cure
it; the API path cannot reach the old history through the poisoned root.

THE CURE AT THE SOURCE: the app stops the server, removes exactly the
poisoned marker rows (empty roots and their trigger prompts, by id,
bound-parameter deletes; healthy summaries and real turns untouched),
restarts, and verifies through the same API the model reads. The full
conversation goes back into the model's view.

PREVENTION: after a compact the app checks the summary that just landed;
an empty one is called out honestly and the repair runs before the
amnesia is ever felt. Session opens are checked too, so a session
poisoned long ago heals on its next open.

THE BRIDGE: while diagnosed and not yet repaired, the P39 Context repair
note rides sends; once the repair verifies, it stops.

399 JVM tests green (20 new).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p40-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p40-v0.40.0-debug.apk"
cp -f "$STAGE/../p40-kit.tar.gz" "$DL/opencode-p40-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.39.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p40-v0.40.0-debug.apk opencode-p40-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p40-v0.40.0-debug.apk` — the app (v0.40.0-p40). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p40-kit.tar.gz` — full source snapshot (add your own
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
echo "P40 PACKAGE OK"
