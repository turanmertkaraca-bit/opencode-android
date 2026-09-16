#!/bin/bash
# p42_package.sh — ship v0.42.0-p42: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p42-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

# sanity: version + the bundled server binary must be the pinned one
grep -q "versionCode 44" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.42.0-p42" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
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
# opencode-android v0.42.0-p42

Install `opencode-p42-v0.42.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.41.0 → installs as an UPDATE, no uninstall.

## What P42 is — the honesty release

The v0.41.0 field session ran for hours, but money went into loops that
had nothing to do with the task. P42 fixed each at its source:

1. ENVIRONMENT LOOPS: the agent shell had NO CA bundle exported (every
   https died) and the env note actively told the agent to run
   "apt-get install ca-certificates" — the exact loop. Now: a merged CA
   bundle (Android system store + shipped Mozilla set) is exported via
   SSL_CERT_FILE / CURL_CA_BUNDLE / GIT_SSL_CAINFO / REQUESTS_CA_BUNDLE /
   PIP_CERT / NODE_EXTRA_CA_CERTS / NPM_CONFIG_CAFILE on every spawn,
   preseeded into the Debian rootfs before the first apt call, exported
   in the alpine wrappers and the git shim; the env note is mode-aware
   and honest (certs are provisioned, raw DNS cannot work by design,
   "if a probe fails twice, report it and move on — re-probing wastes
   the user's money"); a sandbox-root fallback now announces itself.
2. HONEST METER + MONEY: replays book the FULL store (the last-80 cap
   was a rendering cap, not an accounting cap); a persisted per-message
   ledger books all-time cost exactly once (money billed while the app
   was closed no longer vanishes); a compaction no longer leaves the
   meter painted at the pre-compaction depth; model switches repaint
   the denominator and MOVE the app-owned compaction floor (a user's
   own value is never touched); one percent rule (floor) everywhere;
   the server's own tokens.total is preferred when present.
3. CONTEXT-LIMIT SLIDER: Σ pill → ◈ Window cap — a slider + presets
   that writes the picked model's limit.context (models.dev shape,
   verified natively in the bundled server) into opencode.json; apply
   offers a sandbox restart so the meter and the compaction trigger
   follow at once; Clear follows the model's own window again.
4. UI: the keyboard only opens when the input is tapped (never on
   open/resume/repaint); the back button has a fixed 40dp floor and the
   Σ pill caps its own width (no more squished title on narrow
   screens); the startup veil shows a live elapsed counter, fades out,
   and no longer strobes on restart flaps; watcher rows, stale file
   listings and the sessions sheet render time through one
   unit-explicit helper — "20000 days ago" is structurally impossible.

423 JVM tests green (11 new).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p42-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p42-v0.42.0-debug.apk"
cp -f "$STAGE/../p42-kit.tar.gz" "$DL/opencode-p42-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.41.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p42-v0.42.0-debug.apk opencode-p42-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p42-v0.42.0-debug.apk` — the app (v0.42.0-p42). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p42-kit.tar.gz` — full source snapshot (add your own
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
echo "P42 PACKAGE OK"
