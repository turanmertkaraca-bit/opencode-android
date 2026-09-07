#!/bin/bash
# p32_package.sh — ship v0.32.0-p32: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p32-kit-stage

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
# opencode-android v0.32.0-p32

Install `opencode-p32-v0.32.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.31.0 → installs as an UPDATE, no uninstall.

## What P32 is — the final polish: the theme crash, fixed; every screen follows the palette

THE CRASH: P31's theme picker dead-cast android.R.layout.simple_list_item_1
(a TextView!) to LinearLayout — ClassCastException on EVERY tap of the
Theme row, before the sheet ever opened. The dead cast is gone, the whole
picker is contained (a failure costs one toast + an incident-log line,
never the app), and a Robolectric test now performs the exact tap and
asserts the sheet opens — pinned at the UI layer.

EVERY SCREEN FOLLOWS THE PALETTE: a theme switch used to re-skin Settings
only — every other open screen kept the old palette until the process
died. All activities now sync on resume (Theme.syncIfNeeded: detect →
apply → recreate, loop-proof, contained).

NO MORE FROZEN COLORS: the P10-era XML cards/pills froze colors at build
time — code wells (#0D1017), error cards, Deny/Always-allow pills, system
pills, thinking cards, suggestion chips, the user-bubble rim — none of
them followed the palette (near-black blocks on Paper). They are now
palette-owned drawables built from the live tokens. Card ink (PROJECT
tag, path, dividers, hero brand, restart button, card rims, ghost card)
derives from the ON_CARD token — on the default OLED palette the results
are BYTE-IDENTICAL to the old hexes (zero visual change); Paper gets real
ink. The empty-state hero disc lost the last P27-killed violet gradient.
The sandbox veil follows SURFACE instead of a hardcoded midnight wash.
And retint() now re-skins the 1dp hairline strokes of the static-XML
chips/cards/composer that retint could never reach before.

THE PICKER, IMPROVED: each theme row now shows three live swatches
(bg · surface2 · accent) straight from the palette table, haptic on
select — see what you choose before you commit.
275 JVM tests green (17 new), including the crash pin.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:

    cd apk-gradle && gradle assembleDebug

The JVM suite (275 tests) runs with `gradle testDebugUnitTest`.
docs/clipping-audit.md is the repeatable UI clipping checklist.
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p32-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p32-v0.32.0-debug.apk"
cp -f "$STAGE/../p32-kit.tar.gz" "$DL/opencode-p32-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.31.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p32-v0.32.0-debug.apk opencode-p32-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p32-v0.32.0-debug.apk` — the app (v0.32.0-p32). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p32-kit.tar.gz` — full source snapshot (add your own
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
echo "P32 PACKAGE OK"
