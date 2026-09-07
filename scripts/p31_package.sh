#!/bin/bash
# p31_package.sh — ship v0.31.0-p31: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p31-kit-stage

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
# opencode-android v0.31.0-p31

Install `opencode-p31-v0.31.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.30.0 → installs as an UPDATE, no uninstall.

## What P31 is — parallel chats, favorites, a credit limit, the canvas, themes, sleep

PARALLEL SESSIONS: runs are tracked per chat — a script streams in one
session while you keep working in another (cap 3). Sessions → green
RUNNING NOW badge → long-press → Stop the run; ■ answers only the chat
on screen; the subtitle announces background runs. ★ FAVORITES:
long-press a model → pinned shelf at the top of the picker (cap 8,
rotation-proof). CREDIT LIMIT: Settings → Safety — a dollar cap that
refuses every send past it, honest line, manual counter reset. INTERACTIVE
CANVAS: ⌘ → "✦ Interactive canvas…" — the agent writes a self-contained
HTML page; a ▶ chip opens it in a sandboxed viewer (JS on, file/content
access off, never auto-opens). RESET SANDBOX ENVIRONMENT: wipes the
extracted tooling only — keys, GitHub token, projects, chats, settings
survive. AUTO-HIBERNATE: app backgrounded + no runs + nothing waiting →
the sandbox stops itself after a quiet while and reopening drops you
straight into the chat you left (default on, 5/10/15/30 min).
SIX THEMES: OLED black (default), Midnight blue, Graphite, Ember, Forest,
Paper (light) — dialogs, status bars and the static XML colors remap at
runtime. Plus: long-press on a project card finally opens the actions
menu (the clickable-card touch-stream bug, fixed at the root + UI-pinned),
Share chat as Markdown, Find in chat.
258 JVM tests green (41 new).

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:

    cd apk-gradle && gradle assembleDebug

The JVM suite (258 tests) runs with `gradle testDebugUnitTest`.
docs/clipping-audit.md is the repeatable UI clipping checklist.
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p31-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p31-v0.31.0-debug.apk"
cp -f "$STAGE/../p31-kit.tar.gz" "$DL/opencode-p31-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.30.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p31-v0.31.0-debug.apk opencode-p31-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p31-v0.31.0-debug.apk` — the app (v0.31.0-p31). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p31-kit.tar.gz` — full source snapshot (add your own
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
echo "P31 PACKAGE OK"
