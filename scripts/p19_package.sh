#!/bin/bash
# p19_package.sh — ship v0.19.0-p19: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project
DL=$PROJ/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=$PROJ/p19-kit-stage

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/" 2>/dev/null || true
mkdir -p "$STAGE/apk-gradle/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p4_scan_binary.py \
         p4_scan_binary2.py p5_scan_binary.py p5_scan2.py p6_scan_binary.py \
         p6_scan2.py p6_build.sh p6_package.sh p7_package.sh p8_package.sh \
         p9_package.sh p9_gh_ship.sh p16_package.sh p16_gh_ship.sh \
         p17_package.sh p17_gh_ship.sh p18_toolchain_fix.sh \
         p18_package.sh p18_gh_ship.sh p19_package.sh p19_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$PROJ/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null \
    || cp -f "/tmp/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.19.0-p19

Install `opencode-p19-v0.19.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.18.0 → installs as an UPDATE, no uninstall.

## What P19 is — the crash killer

- THE COLD-BOOT CRASH IS STRUCTURALLY DEAD: the field crash (whole app
  process killed on-device; the orphaned server child kept holding port
  4096; every respawn then died EADDRINUSE until the phone was rebooted)
  cannot wedge the sandbox anymore. The supervisor now asks the kernel
  for a free port BEFORE every spawn (default 4096 when free, a fresh
  kernel-assigned port the moment it isn't), sweeps orphaned opencode
  processes by exact-binary match, and gates "healthy" on the child's
  own listen banner — so the app can never talk to a wedged ghost.
- NOTHING DIES SILENTLY ANYMORE: a 30 s heartbeat in sandbox-diag.log
  means even a whole-process kill leaves "when it stopped + what memory
  looked like" on disk. Settings → keep alive → "Sandbox incident log".
- LIVE EDITS ACTUALLY SHOW NOW: the P18 chat watchdog declared the run
  dead after 3.5 s of feed silence — but a bash/tool run IS silent for
  minutes. That tore down the live-edit watcher mid-run (every later
  edit invisible) and reverted the stop button. The quiet threshold is
  now 10 minutes; a run's real end is session.idle / session.error. The
  chat also re-arms itself when parts arrive for its session (open
  mid-run = full rendering, stop button, live edits).
- THE EDIT CARD IS PART OF THE AGENT'S BUBBLE FAMILY: restyled onto the
  ✦-thinking card surface, present from run start ("watching project
  files…", pulsing) instead of waiting for the first write, and gone
  entirely when a run produced zero edits (no empty records).
- VERIFIED ON THE RIG: upstream opencode v1.18.25 was stress-tested
  standalone (3 writes @1.5 s burst + multi-file storm + kill -9
  respawn): the server itself survived everything — the killer was the
  device killing the app PROCESS, which P19 now survives too.
- 11 new JVM regression tests (58 total): listen-banner parse, orphan
  cmdline match, port-freedom logic (incl. squatter case), quiet-end
  bounds, heartbeat format, edit-shower empty-settle rule.
- CARRIED FROM P18/P17: supervisor auto-restart + crash-loop guard,
  graceful send timeouts, the Σ pill, edit shower, screenshot vision,
  cool idle, dark icon, Zen ≠ Go keys, LIVE Files, DeX.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p19-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p19-v0.19.0-debug.apk"
cp -f "$STAGE/opencode-p19-kit.tar.gz" "$DL/opencode-p19-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.19.0-p19** (versionCode 21) — the crash killer: the sandbox can no
longer be wedged into a cold boot, live edits render reliably in chat,
and every death leaves evidence.
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.19.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p19-v0.19.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p19-kit.tar.gz` | README + full source snapshot (`apk-gradle/`). |
| `opencode-linux-arm64-android.tar.gz` | opencode v1.18.25 (arm64, Android build) — already bundled in the APK; kept for custom installs. |
| `archive-p0-p1.tar.gz` | Historical P0/P1 kits. |
| `SHA256SUMS.txt` | Checksums for all four artifacts. |
| `README.md` | This index. |

## Quick start

1. Install the APK (same signing key since v0.6.0 → updates in place).
2. Open → project deck → tap a card → chat. The server starts itself.
3. ⌘ = Ctrl+P (sessions / models / keys / logs / shell); Build / Plan
   chip = Tab; ✦ Thinking and tool cards collapse; ■ aborts the agent.
4. If anything EVER dies now: the chat says "♻ sandbox auto-recovered",
   and Settings → keep alive → "Sandbox incident log" has the evidence.
EOF

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p19-v0.19.0-debug.apk \
          opencode-p19-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
