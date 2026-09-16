#!/bin/bash
# p44_package.sh — ship v0.44.0-p44: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 7 files
# (the 6 release files + HANDOFF.md, which never enters the repo).
set -e
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p44-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

# sanity: version + the bundled server binary must be the pinned one
grep -q "versionCode 46" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.44.0-p44" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
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
# opencode-android v0.44.0-p44

Install `opencode-p44-v0.44.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.43.0 → installs as an UPDATE, no uninstall.

## What P44 is — the quiet-collapse release

The v0.43.0 field run caught the agent confessing the whole chain live:
"the app collapsed my earlier context … I lost the thread and got stuck
in an HTTPS retry loop … the stray hello came from that amnesia". P44
strikes at that root and re-faces the surfaces the user asked for:

1. THE POST-COMPACTION ANCHOR (the money-loop cure): the server's
   compaction summary thins the OLDEST turns — the original task — and
   can summarize away the sandbox ground rules that ride the
   session-start note; the model then re-probes, refills, and the cycle
   re-bills the whole payload. Now, the moment a summary lands, the
   session is armed, and its next real send rides a "Session context"
   block that re-names the thread (opening request, latest real user
   message, last assistant reply), re-states the ground rules (TLS is
   provisioned, raw DNS is dead by design, a probe that fails twice is
   reported and dropped — never re-probed), names an active window cap,
   and instructs continuation — never restart. Armed on live landings
   AND on reopens that discover an older summary; consumed once per
   summary; never wasted on cache beats; kept if the POST fails.
2. THE COLLAPSE IS EXPLAINED WHEN IT HAPPENS: the honest compaction
   note now names an active window cap in the same breath ("a window
   cap of 64.0k tokens is active … summarizing will keep firing near
   that size until the cap is raised or cleared") — the slider
   override was the one trigger a user could set without connecting it
   to the "random" collapses.
3. THE CLAUDE FACE (new default palette): warm paper (#FAF9F5) +
   terracotta (#D97757), light surfaces, Material-3 shapes. Devices
   riding the old Graphite default migrate once (theme_migrated_p44);
   every explicit theme choice stands, and Graphite is still one tap
   away in Settings → Theme. The launcher icon follows the theme, so
   there is a seventh alias (warm paper icon with the terracotta
   prompt).
4. MATERIAL-3 SHAPE REFRESH: corner radius tokens bumped app-wide
   (cards 18dp, sheets 24dp, rows 14dp, chips 22dp, wells 12dp), user
   bubbles 18–22dp with the soft tail, tool/thinking/error cards 16dp —
   the Claude/Google softness on every palette.
5. CALM COLD-BOOT: the "starting sandbox" veil gains a quieter
   hierarchy — medium-weight title, breathing accent dot, the live
   elapsed line, and a softer hint line.

457 JVM tests green (11 new: the anchor branches, the cap-note shape,
the pending-set semantics, the display-strip cross-pin, and the Claude
palette table/alias pins).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p44-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 7 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p44-v0.44.0-debug.apk"
cp -f "$STAGE/../p44-kit.tar.gz" "$DL/opencode-p44-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.42.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p44-v0.44.0-debug.apk opencode-p44-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz HANDOFF.md > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p44-v0.44.0-debug.apk` — the app (v0.44.0-p44). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p44-kit.tar.gz` — full source snapshot (add your own
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
echo "P44 PACKAGE OK"
