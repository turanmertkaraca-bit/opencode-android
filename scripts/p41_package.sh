#!/bin/bash
# p41_package.sh — ship v0.41.0-p41: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p41-kit-stage

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
# opencode-android v0.41.0-p41

Install `opencode-p41-v0.41.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.40.0 → installs as an UPDATE, no uninstall.

## What P41 is — the memory the meter couldn't see

The field: a long agentic session where the agent kept re-exploring the
same project folder it had already checked many times, burning real
money, while the Σ pill read "16% — light". P39's detector and P40's
cure never fired — because this failure mode is neither flat totals nor
an empty summary.

THE MECHANISM, READ FROM THE SERVER'S OWN SOURCE (opencode 1.18.25):
when a turn's billed tokens reach the context window THE SERVER
believes, it compacts silently — the thread is summarized and the
model's working set becomes that summary plus at most 2k–15k tokens of
recent turns kept verbatim. The on-screen store keeps the full history,
so the human sees everything while the model holds a paragraph. A fast
model writes shallow summaries, the agent re-explores, the payload
refills, the server compacts again — and each cycle invalidates the
provider prompt cache, re-billing the whole payload at full input
price. That is where the tokens and the money went.

THE FIX AT THE SOURCE (no detectors):
1. The app pins compaction.preserve_recent_tokens into the sandbox's
   own opencode.json at every boot — 30% of the model's usable window,
   clamped 8k–60k, write-if-absent, a user's own value always winning.
   After any compaction the model keeps tens of thousands of tokens of
   real recent turns instead of 15k at best.
2. The Σ meter's denominator is now the LIVE SERVER's window — the
   exact number the sandbox enforces — with the catalog only as
   fallback. The popover names both numbers when they disagree beyond
   ~20% and warns at 70% of the enforced window.
3. A compaction summary landing live in the open chat announces itself
   in one honest line instead of painting as an ordinary message.

412 JVM tests green (13 new).
EOF

# --- assemble the kit tarball ---
tar -czf "$STAGE/../p41-kit.tar.gz" -C "$STAGE" .

# --- download/: exactly 6 files, replace in place ---
rm -f "$DL"/opencode-p*-v0.*-debug.apk "$DL"/opencode-p*-kit.tar.gz 2>/dev/null || true
cp -f "$OUT" "$DL/opencode-p41-v0.41.0-debug.apk"
cp -f "$STAGE/../p41-kit.tar.gz" "$DL/opencode-p41-kit.tar.gz"
# the server tarball stays the same artifact (same upstream binary,
# sha256-verified identical to the v0.40.0 release asset before use)
cp -f "$PROJ/app/src/main/assets/oc_pkg.bin" "$DL/opencode-linux-arm64-android.tar.gz"

# --- SHA256SUMS ---
cd "$DL"
sha256sum opencode-p41-v0.41.0-debug.apk opencode-p41-kit.tar.gz \
          opencode-linux-arm64-android.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

# --- download README (refresh in place) ---
cat > "$DL/README.md" <<'EOF'
# opencode-android — downloads

- `opencode-p41-v0.41.0-debug.apk` — the app (v0.41.0-p41). Sideload; same
  signing key since v0.6.0, so it installs OVER any earlier version.
- `opencode-p41-kit.tar.gz` — full source snapshot (add your own
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
echo "P41 PACKAGE OK"
