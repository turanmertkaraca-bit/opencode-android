#!/bin/bash
# p23_package.sh — ship v0.23.0-p23: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p23-kit-stage

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
# opencode-android v0.23.0-p23

Install `opencode-p23-v0.23.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.22.0 → installs as an UPDATE, no uninstall.

## What P23 is — blast-radius zero

The field device died on message send with an unhandled Java exception
(exit reason CRASH, 1 kB crash file) while every audited send-path stage
had catch(Exception). The hole: catch(Exception) does NOT stop Errors
(OOM, linkage) and the app crosses many thread boundaries where an
uncaught Throwable kills the process. P23's contract:

- NOTHING on the send/chat/feed paths may kill the process — a Throwable
  is contained: logged to a persistent guard trail, surfaced as one chat
  line, run degrades.
- Diagnostics gains the FULL last-crash.txt trace with Copy/Clear + the
  contained-errors trail (the boot screen had it; warm launches skipped
  it — that's why field reports could only paste "crash file (1 KB)").
- A crash also stamps its identity into sandbox-diag.log (the incident
  log field reports already paste).
- Send path: model validation no longer blocks on the models.dev HTTPS
  fetch (cold-process seconds + the biggest allocation burst per first
  send); refreshes in the background instead.
- 93 JVM tests green (12 new P23 pins).

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug
The JVM tests (app/src/test) run standalone: gradle testDebugUnitTest.

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p23-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p23-v0.23.0-debug.apk"
cp -f "$STAGE/opencode-p23-kit.tar.gz" "$DL/opencode-p23-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.23.0-p23** (versionCode 25) — blast-radius zero: no Throwable on the
send/chat/feed paths can kill the app anymore (catch(Exception) left Errors
a clear shot — the on-send field crash). Contained failures land in a guard
trail shown in Diagnostics with the FULL last-crash trace + a copy button.
Send path no longer blocks on the models.dev fetch. 90 JVM tests.
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.23.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p23-v0.23.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p23-kit.tar.gz` | README + full source snapshot (`apk-gradle/`) + test fixtures. |
| `opencode-linux-arm64-android.tar.gz` | opencode v1.18.25 (arm64, Android build) — already bundled in the APK; kept for custom installs. |
| `archive-p0-p1.tar.gz` | Historical P0/P1 kits. |
| `SHA256SUMS.txt` | Checksums for all four artifacts. |
| `README.md` | This index. |

## Quick start

1. Install the APK (same signing key since v0.6.0 → updates in place).
2. Open → project deck → tap a card → chat. The server starts itself.
3. If anything misbehaves now it CONTAINS it: the chat shows one line and
   ⌘ → Logs & shell has the full trace (Copy trace button). Paste it.
EOF

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p23-v0.23.0-debug.apk \
          opencode-p23-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
