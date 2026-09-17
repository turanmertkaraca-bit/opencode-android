#!/bin/bash
# p47_package.sh — ship v0.47.0-p47: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. Same layout rules as p46.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/tmp/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p47-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

# sanity: version + the bundled server binary must be the pinned one
grep -q "versionCode 49" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.47.0-p47" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
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
# opencode-android v0.47.0-p47

Install `opencode-p47-v0.47.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.46.0 → installs as an UPDATE, no uninstall.

## What P47 is — the live token release

1. TRUE TOKEN STREAMING: the server was publishing every token as its
   own tiny message.part.delta frame all along, beside the boundary-only
   full-text snapshots the app consumed — so the reply materialized all
   at once at the end. P47 consumes the deltas (append straight onto the
   message row; snapshots reconcile; governor stays as bounded backstop)
   and paints growth in place instead of rebuilding the view per token.
2. GIT ON FUSE, TUNED: fresh FUSE-worktree repos are stamped in their
   private gitdir with filemode/trustctime/checkstat/untrackedcache/
   fsmonitor off + gc.auto=0 — no phantom changes, no full re-hashes,
   honest status/add/commit for the agent's follow-up work.
3. 503 JVM tests green (13 new P47 pins), incl. a behavioral shim run
   against real git.
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p47-kit.tar.gz" -C "$STAGE" .

# --- download/: replace the p46 payload in place ---
cp -f "$OUT" "$DL/opencode-p47-v0.47.0-debug.apk"
cp -f "$STAGE/../p47-kit.tar.gz" "$DL/opencode-p47-kit.tar.gz"
# the server tarball stays the same pinned artifact
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p47-v0.47.0-debug.apk opencode-p47-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz HANDOFF.md > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p47-v0.47.0-debug.apk` — the app (v0.47.0-p47). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p47-kit.tar.gz` — full source snapshot (add your own
  oc_pkg.bin asset to build; see kit README).
- `opencode-linux-arm64-android.tar.gz` — the upstream opencode server
  binary tarball (also what the APK bundles as oc_pkg.bin).
- `archive-p0-p1.tar.gz` — the P0/P1 rig archive (history).
- `HANDOFF.md` — continuity notes for the next AI session (contains a
  token; keep it private, never commit it).
- `SHA256SUMS.txt` — checksums.

Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases
EOF

ls -la "$DL" | rg "p47|tar.gz$|README|SHA256|HANDOFF"
echo "P47 PACKAGE OK"
