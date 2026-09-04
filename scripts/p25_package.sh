#!/bin/bash
# p25_package.sh — ship v0.25.0-p25: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p25-kit-stage

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
# opencode-android v0.25.0-p25

Install `opencode-p25-v0.25.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.24.0 → installs as an UPDATE, no uninstall.

## What P25 is — runs outlive the chat

A new RunHub (run engine) owns the transcript, busy state, send
orchestration, the SSE consumption and the live-edit watcher for the
whole process lifetime. The chat is a pure view: leaving to the deck
keeps the run streaming; coming back re-pulls the session from the
server API and shows it exactly where it is — never re-POSTs, never
restarts a healthy stream, and only the stop button aborts. A swipe-
kill mid-run recovers next launch: session intact, one honest
"run was interrupted" note, never a wedged state. The Σ pill is a
CONTEXT DEPTH meter now ("48k / 200k · 24%"); the $ meter is unchanged.
The edit shower is a compact live tree with a hard height cap, and the
peek live-updates while the run edits the selected file.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug
The JVM tests (app/src/test) run standalone: gradle testDebugUnitTest.

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p25-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p25-v0.25.0-debug.apk"
cp -f "$STAGE/opencode-p25-kit.tar.gz" "$DL/opencode-p25-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.25.0-p25** (versionCode 27) — runs outlive the chat: a new RunHub
owns the transcript + busy state + send orchestration + SSE consumption
for the whole process lifetime, so a running agent turn never depends on
the chat screen. Leaving keeps the run streaming; coming back re-pulls
the session (never re-POSTs); only the stop button aborts; a swipe-kill
recovers next launch with the session intact and one honest
"interrupted" note. Σ pill = context depth ("48k / 200k · 24%"), $ meter
unchanged. Edit shower = compact live tree with height cap; peek
live-updates during runs. 134 JVM tests.
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.25.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p25-v0.25.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p25-kit.tar.gz` | README + full source snapshot (`apk-gradle/`) + test fixtures. |
| `opencode-linux-arm64-android.tar.gz` | opencode v1.18.25 (arm64, Android build) — already bundled in the APK; kept for custom installs. |
| `archive-p0-p1.tar.gz` | Historical P0/P1 kits. |
| `SHA256SUMS.txt` | Checksums for all four artifacts. |
| `README.md` | This index. |

## Quick start

1. Install the APK (same signing key since v0.6.0 → updates in place).
2. Open → project deck → tap a card → chat. The server starts itself.
3. Leave the chat mid-run whenever you like — the run keeps streaming
   in the foreground service; come back and it's exactly where it was.
4. If anything misbehaves it degrades, never dies: one honest line in
   the chat, full trace in ⌘ → Logs & shell (Copy button).
EOF

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p25-v0.25.0-debug.apk \
          opencode-p25-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
