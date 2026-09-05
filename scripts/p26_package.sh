#!/bin/bash
# p26_package.sh — ship v0.26.0-p26: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p26-kit-stage

[ -f "$OUT" ] || { echo "APK missing — build first"; exit 1; }

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/" 2>/dev/null || true
mkdir -p "$STAGE/apk-gradle/scripts"
for f in "$PROJ"/scripts/*; do
  cp -f "$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.26.0-p26

Install `opencode-p26-v0.26.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.25.0 → installs as an UPDATE, no uninstall.

## What P26 is — the evergreen release

The live edit tree moved OUT of the transcript into a pinned footer
above the composer: always visible while the agent works (P25 buried it
above the fold within seconds), gone when the run settles. The flashing
live symbol is gone (static dot). Back walks backward — Files goes up
one directory per press, chat's system back mirrors ‹. The chat re-syncs
on EVERY resume (upserts in place, never wipes) and a re-pull that
raced the sandbox boot retries itself the moment the server turns
healthy — no more stale/loading screen after leaving and coming back.
Discovery-catalog models are selectable (try-anyway with automatic
fall-back). And the month/year audit: every growth path capped, session
token/cost totals delta-tracked and O(1), proven by a 12k-part soak.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug
The JVM tests (app/src/test) run standalone: gradle testDebugUnitTest.

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p26-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p26-v0.26.0-debug.apk"
cp -f "$STAGE/opencode-p26-kit.tar.gz" "$DL/opencode-p26-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.26.0-p26** (versionCode 28) — the evergreen release: the live edit
tree is now a PINNED FOOTER (always visible while the agent works,
instead of buried in the transcript), the flashing live symbol is gone,
back navigates backward (Files walks up the directory tree, chat goes to
the deck), the chat re-syncs on every resume with a boot-race retry (no
more stale/loading screen on return), catalog models are selectable
with automatic fall-back, and every memory-growth path is capped for
month/year runs (12k-part soak test). 143 JVM tests.
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.26.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p26-v0.26.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p26-kit.tar.gz` | README + full source snapshot (`apk-gradle/`) + test fixtures. |
| `opencode-linux-arm64-android.tar.gz` | opencode v1.18.25 (arm64, Android build) — already bundled in the APK; kept for custom installs. |
| `archive-p0-p1.tar.gz` | Historical P0/P1 kits. |
| `SHA256SUMS.txt` | Checksums for all four artifacts. |
| `README.md` | This index. |

## Quick start

1. Install the APK (same signing key since v0.6.0 → updates in place).
2. Open → project deck → tap a card → chat. The server starts itself.
3. While the agent works, the live tree above the composer shows every
   file it touches — tap one for a line-precise peek that follows the
   edits. Leave and come back whenever; the chat is always current.
4. If anything misbehaves it degrades, never dies: one honest line in
   the chat, full trace in ⌘ → Logs & shell (Copy button).
EOF

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p26-v0.26.0-debug.apk \
          opencode-p26-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
