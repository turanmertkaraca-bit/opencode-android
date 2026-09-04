#!/bin/bash
# p22_package.sh — ship v0.22.0-p22: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p22-kit-stage

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
# opencode-android v0.22.0-p22

Install `opencode-p22-v0.22.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.21.0 → installs as an UPDATE, no uninstall.

## What P22 is — the native-layer audit

The native layer (5 bundled ELF binaries + rootfs extraction) was
EXECUTED on the build rig for the first time, and what it caught was
fixed:

- bundled BusyBox v1.36.1 executed under qemu-aarch64: 305 applets,
  every shim-critical command pattern green; the real applet list ships
  as a fixture and pins the app's fallback list (which had a dead
  `patch` entry — removed).
- the REAL debian:bookworm arm64 docker layer (the exact 48 MB blob the
  app downloads) extracted with the app's own extractor: two hardlinks
  landed DANGLING (perl5.36.0, uncompress) — root-relative hardlink
  targets are now normalized; re-extraction is byte-exact vs ground
  truth (5237 files, 0 dangling, 0 escapes).
- latent pax-header bug: `path=` substring-matched inside `linkpath=`
  (both orders are legal in docker layers) — now record-exact.
- proot toolkit ELF wiring verified (DT_NEEDED/SONAME vs the lib dir).
- sandbox proxy: unbounded thread-per-connection now capped at 64.
- send double-tap latch (pre-busy network window queued duplicate runs).
- 81 JVM tests green (8 new P22 pins).

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug
The JVM tests (app/src/test) run standalone: gradle testDebugUnitTest.

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p22-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p22-v0.22.0-debug.apk"
cp -f "$STAGE/opencode-p22-kit.tar.gz" "$DL/opencode-p22-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.22.0-p22** (versionCode 24) — the native-layer audit: the bundled
binaries were executed for the first time (busybox under ARM64 emulation,
the real Debian layer through the app's own extractor) and everything
they caught is fixed: dangling Debian hardlinks, a dead `patch` applet,
a latent pax parser bug, an unbounded proxy thread pool, a send
double-tap window. 81 JVM tests.
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.22.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p22-v0.22.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p22-kit.tar.gz` | README + full source snapshot (`apk-gradle/`) + test fixtures. |
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
sha256sum opencode-linux-arm64-android.tar.gz opencode-p22-v0.22.0-debug.apk \
          opencode-p22-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
