#!/bin/bash
# p20_package.sh — ship v0.20.0-p20: replace the APK/kit in download/,
# refresh SHA256SUMS + download README. download/ stays at exactly 6 files.
# Layout note (P20): the project lives at /home/z/my-project/gh-repo.
set -e
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p20-kit-stage

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
# opencode-android v0.20.0-p20

Install `opencode-p20-v0.20.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.19.0 → installs as an UPDATE, no uninstall.

## What P20 is — the background survivor

- THE EMPTY THOUGHT BUBBLE IS DEAD: the chat unsubscribes from the event
  feed while paused, and onResume only refetched an EMPTY list — every
  part that fired while you were away was lost forever (a THINKING card
  born right before the pause stayed empty). Every resume now replays
  the session from the server's message store: known parts update in
  place, missed parts append in order, ancient trimmed rows are never
  re-appended, and a run that FINISHED while away settles the chat with
  a one-line note instead of "working…" forever.
- THINKING STREAMS TOKEN-BY-TOKEN: the P9 smoothing ticker (24 ms,
  adaptive char-steps, live caret) now drives reasoning rows too. A
  collapsed thinking card grows a live one-line ticker of the FRESHEST
  thought; an open card streams its body with the caret and finalizes
  into the calm italic view in one rebuild.
- NO DEAD THINKING CARDS: a reasoning part born empty that never got
  text is hidden at settle instead of sitting as an unopenable card.
- CARRIED FROM P19: port freedom + orphan sweep + listen-banner health
  (the cold-boot crash class is structurally dead), 30 s incident-log
  heartbeat, the un-murdered live-edit shower. From P18: supervisor
  auto-restart + crash-loop guard, graceful send timeouts, the Σ pill.
- 9 new JVM regression tests (67 total): stable part keys, think-window
  edges, settle-only-when-finished.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> ANDROID_HOME=<sdk> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p20-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clears ANY previous pN names) ---
rm -f "$DL"/opencode-p*-v*-debug.apk "$DL"/opencode-p*-kit.tar.gz
cp -f "$OUT" "$DL/opencode-p20-v0.20.0-debug.apk"
cp -f "$STAGE/opencode-p20-kit.tar.gz" "$DL/opencode-p20-kit.tar.gz"

# --- refresh the download index ---
cat > "$DL/README.md" <<'EOF'
# OpenCode Android — Downloads

**v0.20.0-p20** (versionCode 22) — the background survivor: the empty
thought bubble is dead, thinking streams token-by-token, and the chat
settles itself when a run ended in the background.
Get it from GitHub (fast, no auth): https://github.com/turanmertkaraca-bit/opencode-android/releases/tag/v0.20.0

## Files

| File | What it is |
|------|-----------|
| `opencode-p20-v0.20.0-debug.apk` | The app. Agent binary bundled, zero setup, chat-first UI. **Install this.** |
| `opencode-p20-kit.tar.gz` | README + full source snapshot (`apk-gradle/`). |
| `opencode-linux-arm64-android.tar.gz` | opencode v1.18.25 (arm64, Android build) — already bundled in the APK; kept for custom installs. |
| `archive-p0-p1.tar.gz` | Historical P0/P1 kits. |
| `SHA256SUMS.txt` | Checksums for all four artifacts. |
| `README.md` | This index. |

## Quick start

1. Install the APK (same signing key since v0.6.0 → updates in place).
2. Open → project deck → tap a card → chat. The server starts itself.
3. ⌘ = Ctrl+P (sessions / models / keys / logs / shell); Build / Plan
   chip = Tab; ✦ Thinking and tool cards collapse; ■ aborts the agent.
4. Background-proof: leave mid-run, come back — the chat replays
   everything you missed; thinking cards stream token-by-token.
5. If anything EVER dies: Settings → keep alive → "Sandbox incident
   log" has the evidence (P19 heartbeat), and the chat says
   "♻ sandbox auto-recovered".
EOF

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p20-v0.20.0-debug.apk \
          opencode-p20-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
