#!/bin/bash
# p24_package.sh — ship v0.24.0-p24: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p24-kit-stage

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
# opencode-android v0.24.0-p24

Install `opencode-p24-v0.24.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.23.0 → installs as an UPDATE, no uninstall.

## What P24 is — the flush surgeon

P23 made the app uncrashable (every send/chat/feed stage runs at
Throwable breadth; failures land in the guard trail + one chat line).
The field then showed the next layer ("doesn't crash but it still won't
work"): P23 guarded the whole PAINT BATCH with one guard, so ONE row
that could not paint aborted every row behind it — and the feed
re-dirtied that row on every streaming delta and file event, so every
80 ms flush re-failed: a wall of containment banners plus a frozen
transcript while the run itself stayed healthy.

- Each row now fails ALONE: the coalesced flush guards per row, so a
  poison row can no longer block its siblings (pinned by Robolectric
  tests that reproduce the field state inside the real ChatActivity).
- Repeat offenders are QUARANTINED: after 2 failed paints a row's
  content is swapped for a bounded, can't-fail fallback line — the part
  degrades visibly, the chat keeps flowing, no banner loops.
- Full re-renders (renderAll) now run at Throwable breadth too: an Error
  row becomes the fallback line instead of leaving the transcript
  half-built (the exact "survived but won't work" state).
- Repeated containment notes now CARRY their identity (class: message ·
  top app frame) inside the note — one screenshot from the field is a
  diagnosis, no Diagnostics trip needed.
- Null-safety hardening on the tool-row upsert (Objects.equals — the
  crash class the field keeps finding).
- 103 JVM tests green (10 new: flush shape, quarantine strikes, trace
  line bounds/frame, plus the Robolectric poison-row reproduction).

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug
The JVM tests (app/src/test) run standalone: gradle testDebugUnitTest.

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p24-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p24-v0.24.0-debug.apk"
cp -f "$STAGE/opencode-p24-kit.tar.gz" "$DL/opencode-p24-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.24.0-p24** (versionCode 26) — the flush surgeon: the transcript can
no longer be frozen by one bad part. Each chat row now fails ALONE (P23
guarded the whole paint batch, so a single poison row re-failed on every
streaming flush — banner wall + frozen chat while the run stayed
healthy). Repeat offenders quarantine into a can't-fail fallback line;
repeated containment notes carry their class + top frame, so one
screenshot is a diagnosis. 103 JVM tests.
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.24.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p24-v0.24.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p24-kit.tar.gz` | README + full source snapshot (`apk-gradle/`) + test fixtures. |
| `opencode-linux-arm64-android.tar.gz` | opencode v1.18.25 (arm64, Android build) — already bundled in the APK; kept for custom installs. |
| `archive-p0-p1.tar.gz` | Historical P0/P1 kits. |
| `SHA256SUMS.txt` | Checksums for all four artifacts. |
| `README.md` | This index. |

## Quick start

1. Install the APK (same signing key since v0.6.0 → updates in place).
2. Open → project deck → tap a card → chat. The server starts itself.
3. If anything misbehaves it degrades, never dies: one honest line in
   the chat, full trace in ⌘ → Logs & shell (Copy button). A REPEATING
   error now shows its class + top frame right in the chat line.
EOF

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p24-v0.24.0-debug.apk \
          opencode-p24-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
