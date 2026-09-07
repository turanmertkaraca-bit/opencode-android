#!/bin/bash
# p33_package.sh — ship v0.33.0-p33: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p33-kit-stage

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
# opencode-android v0.33.0-p33

Install `opencode-p33-v0.33.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.32.0 → installs as an UPDATE, no uninstall.

## What P33 is — the final version: themes land instantly, Graphite is the face, keys reach the sandbox, the picker tells the truth

THE THEME CHANGE IS INSTANT: tapping a palette in Settings used to call
recreate() — a whole-activity teardown + rebuild + window animation. The
theme now lands the same frame: save → apply → dismiss → rebuild the one
view tree in place (pinned by decor-identity test: the window survives).
The GitHub-token save had the same recreate disease — in place now too.

GRAPHITE IS THE DEFAULT FACE (the user picked it). Fresh installs come up
in Graphite; an explicit choice always wins; the picker's "· default"
label moved with the crown. An explicit pre-P31 "not AMOLED" choice still
keeps Midnight.

THE WHOLE-APP PALETTE SYNC ACTUALLY WORKS NOW: P32's syncIfNeeded compared
the pref against the process-global static — which Settings' own apply()
had already updated, so no other screen could ever be "stale" and the sync
never fired in the field. The palette id now rides a per-screen decor
stamp (Theme.stampApplied / appliedIdOf): every screen re-skins exactly
once when the pref moves and provably cannot loop.

KEYS REACH THE SANDBOX BY THEMSELVES ("the app thinks i have no api key
even tho it says i have it in api settings"): a CHANGED key now restarts
the sandbox (the old code only restarted for a FIRST-TIME key — an updated
key left the server serving the old value); the model sheet re-reads
auth.json at open; the "no API key yet" hint counts custom providers whose
key lives inline in opencode.json; imported auth.json and custom endpoints
apply the same way.

THE PICKER CONTRAST IS HONEST AGAIN ("p32 made everything low contrast
white"): when the running server didn't answer, the fetch marked EVERY
model catalog-only and the whole sheet went dim. The pure carryLive rule
keeps last-known live truth through a server blip — a model the server
served last time stays bright and selectable (and its provider reads
usable again).

THE PROJECT LONG-PRESS GREW UP ("still the same old android 4 style box"):
the actions menu is the app's own sheet — name + mono path header, glyph
rows (▸ Open · ✎ Rename · ⌦ Remove card · ✕ Delete project… in the danger
color), ripple + haptics, palette-owned end to end; the delete confirm
shows the exact path in a code well with Keep it / Delete forever pills;
rename matches. All P30 shields stay underneath.

291 JVM tests green (16 new).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p33-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p33-v0.33.0-debug.apk"
cp -f "$STAGE/../p33-kit.tar.gz" "$DL/opencode-p33-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.32.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p33-v0.33.0-debug.apk opencode-p33-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p33-v0.33.0-debug.apk` — the app (v0.33.0-p33). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p33-kit.tar.gz` — full source snapshot (add your own
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
echo "P33 PACKAGE OK"
