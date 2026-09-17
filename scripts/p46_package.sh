#!/bin/bash
# p46_package.sh — ship v0.46.0-p46: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 7 files
# (the 6 release files + HANDOFF.md, which never enters the repo).
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p46-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

# sanity: version + the bundled server binary must be the pinned one
grep -q "versionCode 48" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.46.0-p46" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
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
# opencode-android v0.46.0-p46

Install `opencode-p46-v0.46.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.45.0 → installs as an UPDATE, no uninstall.

## What P46 is — the keep-it-alive-and-honest release

1. REAL-TIME STREAMING, CURED: the event feed re-sends the whole
   cumulative part text on every delta; on a phone the SSE thread
   drowned in quadratic JSON parses and the UI burst-fed (~15 s waits).
   The new stream governor pre-screens frames with a cheap scan and
   applies at most one per part per 250 ms (lossless — every frame IS
   the full state; end-of-turn markers and stream end force-flush).
2. GIT CLONE WORKS EVERYWHERE: new projects default to PRIVATE app
   storage (ext4 — atomic renames, fast, no permission dance), and the
   git shim routes clone/init into shared (FUSE) storage through
   --separate-git-dir so the real .git lives on private ext4 — every
   later commit/pack/lock rename included, not just the first clone.
3. THE LIVE FILE WATCHER IS PATH-DRIVEN: it watches the project's real
   path wherever it lives; private ext4 is native inotify territory.
4. LONG-SESSION STABILITY: seen-permission id set hard-capped (P26
   rules); governor pending set byte-bounded; all 487 JVM tests green
   (12 new).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p46-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 7 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p46-v0.46.0-debug.apk"
cp -f "$STAGE/../p46-kit.tar.gz" "$DL/opencode-p46-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.42.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p46-v0.46.0-debug.apk opencode-p46-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz HANDOFF.md > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p46-v0.46.0-debug.apk` — the app (v0.46.0-p46). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p46-kit.tar.gz` — full source snapshot (add your own
  oc_pkg.bin asset to build; see kit README).
- `opencode-linux-arm64-android.tar.gz` — the upstream opencode server
  binary tarball (also what the APK bundles as oc_pkg.bin).
- `archive-p0-p1.tar.gz` — the P0/P1 rig archive (history).
- `HANDOFF.md` — continuity notes for the next AI session (contains a
  token; keep it private, never commit it).
- `SHA256SUMS.txt` — checksums.

Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases
EOF

COUNT=$(find "$DL" -maxdepth 1 -type f | wc -l)
echo "download/ top-level file count: $COUNT (must be 7; archive-history/ excluded)"
[ "$COUNT" -eq 7 ] || { echo "FILE COUNT WRONG"; exit 1; }
ls -la "$DL"
echo "P46 PACKAGE OK"
