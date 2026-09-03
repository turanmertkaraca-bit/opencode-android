#!/bin/bash
# p14_package.sh — ship v0.14.0-p14: replace p13 APK/kit in download/, refresh
# SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p14-kit-stage

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/" 2>/dev/null || true
mkdir -p "$STAGE/apk-gradle/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p6_build.sh \
         p12_rehearse_debian.sh p13-H.java p13-V.java \
         p14_package.sh p14_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$PROJ/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null \
    || cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.14.0-p14

Install `opencode-p14-v0.14.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.13.0 → installs as an UPDATE, no uninstall.

## What P14 is — the field-report killer (bash shim + picker + spend + unattended)

- BASH SHIM, FIXED FOR REAL: the P13 shim generator wrote the Debian
  branch test as TWO lines (`if [ -x … ]` newline `&& [ -f … ]; then`).
  mksh ends the command at the newline — a leading `&&` is a SYNTAX
  ERROR, the shim died before exec, and EVERY bash tool call failed.
  The condition is now a single line, the generator REFUSES to ever
  write a shim containing a line starting with `&&`/`||`, and JVM
  regression tests pin it. Updating repairs the shim automatically.
- MODEL PICKER, MERGE FIXED: /config/providers responses no longer
  short-circuit the models.dev catalog — server models and the FULL
  catalog always merge (31 free zen models visible even offline via
  the bundled snapshot). Provider headers show key state from the
  app's own auth.json; a provider marked "(add API key)" now OPENS
  API KEYS on a single tap. Free models are badged ⟨free⟩; paid rows
  show $ in/out per Mtok.
- SPEND PILL: the ⇅ tokens + $ cost moved out of the one-line subtitle
  (they were the first characters ellipsized away — "cut out cuz there
  is no space") into their own monospace pill in the chat header,
  always fully visible.
- UNATTENDED MODE (auto-allow): ⌘ → "Turn ON unattended (auto-allow)"
  or Settings → agent → Unattended mode. Every tool-approval request
  is answered "always" automatically — leave the agent running
  hands-free. A green "unattended" pill replaces the approval card;
  tap it to turn off. A failed reply falls back to the normal card.
- LONG-OUTPUT JANK, FIXED: tool input/output blocks no longer use
  selectable spans (the "chat bugs out when long commands fill it"
  report); long-press a block to copy the full text; oversized output
  is capped with a "+N more chars" tail.
- MODEL SHEET REBUILT: 88%-height bottom sheet, weight-based list
  (no fixed 430dp box), recycled row views (thousands of rows scroll
  without churn), source line shows provider/model/free counts.
- OPencode ZEN vs GO, CLARIFIED: same provider row, same key
  (console.opencode.ai). Free models need NO key; 401/402 = that
  model is paid and your plan doesn't cover it.
- GitHub agent token row surfaced in Settings → agent (exports
  GH_TOKEN into Debian shells; the AI can apt install git and push).

Same zero-dependency, framework-only codebase. P15 = the UI upgrade.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p14-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clear ANY stale P-numbered sets) ---
for f in "$DL"/opencode-p*-debug.apk "$DL"/opencode-p*-kit.tar.gz; do
  [ -e "$f" ] && rm -f "$f"; done
cp -f "$OUT" "$DL/opencode-p14-v0.14.0-debug.apk"
cp -f "$STAGE/opencode-p14-kit.tar.gz" "$DL/opencode-p14-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p14-v0.14.0-debug.apk \
          opencode-p14-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
