#!/bin/bash
# p15_package.sh — ship v0.15.0-p15: replace p14 APK/kit in download/, refresh
# SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p15-kit-stage

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
         p15_package.sh p15_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$PROJ/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null \
    || cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.15.0-p15

Install `opencode-p15-v0.15.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0–v0.14.0 → installs as an UPDATE, no uninstall.

## What P15 is — the P12 picker restored + the proot fixes the agent asked for + the UI rework

- MODEL PICKER = THE FIRST P12 AGAIN (the headline fix): P14 let every
  catalog row SAVE, then the server answered "Model not found" — the
  picker looked broken a third time. Forensics on the actual P12a
  release source recovered the missing piece: `Mdl.live`. The picker now
  (again) shows bright rows for models the RUNNING server serves right
  now, dims + tags everything else "· catalog", and REFUSES a dead pick
  with a plain-language toast instead of a runtime error. Send-path
  self-heal (validateSelectedModel) works again because available()
  requires live. Usable providers sort first, live models first within
  each provider — the exact P12a feel.
- PROOT/DEBIAN DIRS, INITIALIZED ON FIRST LAUNCH (the agent's own field
  report, implemented 1:1): `ensureDirs()` creates files/debian,
  files/debian/tmp (the PROOT_TMP_DIR target proot mkdtemps inside —
  missing it was the fresh-install killer), files/home and rootfs/tmp
  BEFORE install/probe/every guest run. Environment-variable preloader
  was already exporting PROOT_TMP_DIR; now the path always exists.
- ENVIRONMENT DETECTION + WELCOME MESSAGE: every chat now opens with a
  one-shot environment row — kernel, arch, user, cwd, OS, available
  tools (apt/git/python3), Download-bind reachability and the project
  path — gathered INSIDE Debian when active, host-side otherwise. Also
  written to files/debian/env.txt so the agent itself can read it. ⌘ →
  "Sandbox environment" re-runs it any time; Settings → environment →
  Environment check shows the full audit (with the dir checks).
- FILES — the visual project file manager: ⌘ → "Project files →" or
  Settings → sandbox → Project files. Breadcrumb chips, gradient discs
  for folders, type glyphs for files, size · age meta, staggered
  entrances. Tap a file → preview sheet (20k chars, mono, copy-all).
  Long-press → rename / delete / copy path. ＋ new folder / ＋ new file.
  Project-scoped by design — it cannot navigate outside the project.
- CHAT FLUIDITY (the "not snappy" fix): one busy turn fired dozens of
  SSE merges a second and each rebuilt its row = a full relayout of the
  whole list, fighting long outputs. Merge-path repaints are now
  COALESCED (one flush per 80 ms) — bursts cost one relayout, not N.
  User sends, expand/collapse and error rows stay instant.
- Settings → sandbox gained the Project files row; environment section
  gained Environment check; version row updated.

Also still in: P14's shim single-line fix + generator guard, spend pill,
unattended mode, long-output caps, ⟨free⟩ badges + $/Mtok.

Same zero-dependency, framework-only codebase.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p15-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ (clear ANY stale P-numbered sets) ---
for f in "$DL"/opencode-p*-debug.apk "$DL"/opencode-p*-kit.tar.gz; do
  [ -e "$f" ] && rm -f "$f"; done
cp -f "$OUT" "$DL/opencode-p15-v0.15.0-debug.apk"
cp -f "$STAGE/opencode-p15-kit.tar.gz" "$DL/opencode-p15-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p15-v0.15.0-debug.apk \
          opencode-p15-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
