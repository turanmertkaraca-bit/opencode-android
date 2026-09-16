#!/bin/bash
# p43_package.sh — ship v0.43.0-p43: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p43-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

# sanity: version + the bundled server binary must be the pinned one
grep -q "versionCode 45" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.43.0-p43" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
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
# opencode-android v0.43.0-p43

Install `opencode-p43-v0.43.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.42.0 → installs as an UPDATE, no uninstall.

## What P43 is — the realtime-feel release

The v0.42.0 field run was stable but felt wrong in six ways. P43 fixes
each at its source:

1. THE STREAM GLIDES: providers (and the background-optimized SSE
   reader) deliver text in multi-kilobyte bursts, and the old reveal
   rule emptied each burst in ~0.5 s of rapid repaints, then froze —
   "it thinks it's realtime streaming but it's just a burst". A new
   StreamPacer estimates the arrival rate and reveals just behind it,
   with a lag ceiling: bursts become a continuous readable glide that
   still finishes ~2 s after the last byte.
2. THE THOUGHT IS WATCHABLE: the collapsed thinking card used to show
   one flashing line; it now grows a three-line, tail-anchored window
   of the freshest raw reasoning (never a summary), streaming via the
   same pacer. Open the card and the full thought still streams.
3. KEYBOARD NEVER AUTO-OPENS — EVERYWHERE: every sheet window is pinned
   STATE_ALWAYS_HIDDEN and the sheet focus helper no longer raises the
   IME (model picker, new chat, keys, rename, filters, settings — all
   of them). The keyboard appears only when a field is tapped.
4. CACHE BEATS (the cachebeat idea, in-app): a session idle past the
   provider's cache TTL re-bills the whole chat cold on the next
   message (~10x a warm read). After 4 quiet minutes the app fires one
   tiny automatic beat that re-reads the prefix at the cached rate and
   restarts the TTL clock — max 6 per quiet stretch, never while a run
   streams, backoff on failure, a Settings switch, and a visible
   "♡ cache beat" line in the chat. Cache health now also shows in the
   Σ popover.
5. NO MORE VANISHING HISTORY: a reopened long chat rendered only the
   last 80 stored messages and live sessions trimmed at 450 rows —
   hours of tool cards silently disappeared. Rendering now keeps 600
   stored messages on reopen and trims at 1200/900 live, and when the
   cap bites it SAYS so instead of amputating quietly.
6. GROUNDING: a mid-task bare greeting ("hello world") no longer gets
   "Hello! What would you like help with?" back — deep sessions ride a
   session-context reminder so the answer stays inside the thread; a
   folder touch in the live card prints "(folder)" instead of a raw
   EISDIR exception; the Σ pill reads "211k · 39% · $0.39" (the old
   middle-ellipsized form looked like a mystery fraction); the env note
   teaches --depth 1 clones and git-fetch resume, and the Debian
   .gitconfig drops dead links after 30 s instead of hanging forever.

446 JVM tests green (21 new).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p43-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p43-v0.43.0-debug.apk"
cp -f "$STAGE/../p43-kit.tar.gz" "$DL/opencode-p43-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.42.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p43-v0.43.0-debug.apk opencode-p43-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p43-v0.43.0-debug.apk` — the app (v0.43.0-p43). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p43-kit.tar.gz` — full source snapshot (add your own
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
echo "P43 PACKAGE OK"
