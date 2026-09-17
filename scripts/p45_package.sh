#!/bin/bash
# p45_package.sh — ship v0.45.0-p45: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 7 files
# (the 6 release files + HANDOFF.md, which never enters the repo).
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p45-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

# sanity: version + the bundled server binary must be the pinned one
grep -q "versionCode 47" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.45.0-p45" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
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
# opencode-android v0.45.0-p45

Install `opencode-p45-v0.45.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.44.0 → installs as an UPDATE, no uninstall.

## What P45 is — the act-normal release

1. AUTO-COMPACTION IS OFF, AT THE SOURCE: the sandbox never summarizes a
   chat's memory on its own. The server's own escape hatch
   (compaction.auto=false in opencode.json) is pinned at every boot;
   a genuinely full context errors with one honest overflow line instead
   of the model silently losing the thread. Settings → Essentials →
   Auto-compact context turns the old behavior back on explicitly.
2. GRAPHITE IS THE DEFAULT FACE AGAIN: the P44 warm-paper "claude"
   palette is removed outright (row, gradients, name, launcher alias);
   the one-time p45 migration carries p44-migrated riders back to
   Graphite; every other explicit theme choice stands.
3. THE CLAUDE CHAT LAYOUT (on the graphite tokens): fully rounded
   neutral user pills (no tail), flat assistant responses under a small
   ✦ marker, time-of-day empty-chat greeting ("Good evening / How can I
   help you today?"), and ONE unified composer pill — borderless input
   inside, attach + palette left, Build/Plan + model chips and the send
   circle right — painted from the live palette so it follows every
   theme.
4. COLD BOOT LANDS ON THE PROJECT DECK, ALWAYS: the app never reopens
   the last chat by itself anymore; opening a project is the user's
   explicit act, like the opencode TUI. The last-project sandbox
   pre-warm stays (pure infrastructure — no session, no run, no screen).

475 JVM tests green (18 new).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p45-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 7 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p45-v0.45.0-debug.apk"
cp -f "$STAGE/../p45-kit.tar.gz" "$DL/opencode-p45-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.42.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p45-v0.45.0-debug.apk opencode-p45-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz HANDOFF.md > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p45-v0.45.0-debug.apk` — the app (v0.45.0-p45). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p45-kit.tar.gz` — full source snapshot (add your own
  oc_pkg.bin asset to build; see kit README).
- `opencode-linux-arm64-android.tar.gz` — the upstream opencode server
  binary tarball (also what the APK bundles as oc_pkg.bin).
- `archive-p0-p1.tar.gz` — the P0/P1 rig archive (history).
- `HANDOFF.md` — continuity notes for the next AI session (contains a
  token; keep it private, never commit it).
- `SHA256SUMS.txt` — checksums.

Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases
EOF

COUNT=$(ls -1 "$DL" | wc -l)
echo "download/ file count: $COUNT (must be 7)"
[ "$COUNT" -eq 7 ] || { echo "FILE COUNT WRONG"; exit 1; }
ls -la "$DL"
echo "P45 PACKAGE OK"
