#!/bin/bash
# p21_package.sh — ship v0.21.0-p21: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
# Layout note: the project lives at /home/z/my-project/gh-repo.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p21-kit-stage

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
# opencode-android v0.21.0-p21

Install `opencode-p21-v0.21.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.20.0 → installs as an UPDATE, no uninstall.

## What P21 is — the stable one

- THE KEYBOARD STAYS IN THE CHAT BOX: the auto-scroll used Android's
  fullScroll(), which runs a FOCUS SEARCH and moved keyboard focus into
  the message list while the agent streamed — the IME kept detaching
  from the input. Scrolling is now focus-free (3 call sites) + a guard
  restores focus to the chat box if any rebuild takes it.
- EXIT FORENSICS: Diagnostics now shows "last exits — why Android
  stopped the app" from the system's own ApplicationExitInfo records —
  it NAMES the process killer (LOW MEMORY / ANR / NATIVE crash / signal /
  freezer), works retroactively for past deaths, no adb needed.
- SEND-CRASH HARDENING: resume replays no longer re-decode every image
  (up to 12 MB base64 → bytes → bitmap per image per return — gone);
  vision images reuse the cached decode; the settle rule no longer
  settles on a synthetic trailing message (caught by the new suite).
- TESTED BEFORE SHIP: a real opencode v1.18.25 server ran on the rig,
  a real free model completed a turn, and the REAL /session/{id}/message
  + 176 SSE events replay through the app's settle/replay logic in the
  JVM suite — 73 tests green. Part-id keying + time.completed verified
  against reality; reason codes pinned against the API-34 jar.
- CARRIED: P20 resume replay + token-by-token thinking ticker, P19
  crash killer (port freedom + orphan sweep + heartbeat), P18
  supervisor + graceful timeouts, the Σ pill, vision, cool idle.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug
The JVM tests (app/src/test) run standalone: gradle testDebugUnitTest.

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p21-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p21-v0.21.0-debug.apk"
cp -f "$STAGE/opencode-p21-kit.tar.gz" "$DL/opencode-p21-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.21.0-p21** (versionCode 23) — the stable one: the keyboard stays in
the chat box, Diagnostics now names the process killer (system exit
records), and the replay/settle logic is tested against a real server's
payloads (73 JVM tests).
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.21.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p21-v0.21.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p21-kit.tar.gz` | README + full source snapshot (`apk-gradle/`) + test fixtures. |
| `opencode-linux-arm64-android.tar.gz` | opencode v1.18.25 (arm64, Android build) — already bundled in the APK; kept for custom installs. |
| `archive-p0-p1.tar.gz` | Historical P0/P1 kits. |
| `SHA256SUMS.txt` | Checksums for all four artifacts. |
| `README.md` | This index. |

## Quick start

1. Install the APK (same signing key since v0.6.0 → updates in place).
2. Open → project deck → tap a card → chat. The server starts itself.
3. If anything ever dies: Diagnostics → "last exits" names the killer
   (paste it + the sandbox incident log) — no more guessing.
EOF

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p21-v0.21.0-debug.apk \
          opencode-p21-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
