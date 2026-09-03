#!/bin/bash
# p6_package.sh — ship v0.6.0-p6: replace p5 APK/kit in download/, refresh
# SHA256SUMS + README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p6-kit-stage

rm -rf "$STAGE"; mkdir -p "$STAGE/apk-gradle"

# --- kit source snapshot (lean: no build dirs, no 60 MB bundled asset) ---
cp -r "$PROJ/app" "$STAGE/apk-gradle/app"
rm -rf "$STAGE/apk-gradle/app/build" "$STAGE/apk-gradle/app/.gradle"
rm -f  "$STAGE/apk-gradle/app/src/main/assets/oc_pkg.bin"
cp -f "$PROJ/build.gradle" "$PROJ/settings.gradle" "$PROJ/gradle.properties" \
      "$PROJ/README.md" "$PROJ/.gitignore" "$STAGE/apk-gradle/"
mkdir -p "$STAGE/apk-gradle/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p4_scan_binary.py \
         p4_scan_binary2.py p5_scan_binary.py p5_scan2.py p6_scan_binary.py \
         p6_scan2.py p6_build.sh ElfGateTest.java TestTarGz.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.6.0-p6

Install `opencode-p6-v0.6.0-debug.apk` (sideload; allow unknown apps).

- The opencode agent binary (v1.18.25 arm64) is BUNDLED — first launch
  auto-unpacks it (~one-time), no SAF import needed. "Import opencode
  binary" remains as an advanced fallback.
- API keys are configured IN the app: main screen → "Connect API keys".
  Provider keys land in the app-private auth.json; custom
  OpenAI-compatible endpoints are supported. auth.json import still works.
- Chat now renders the full TUI experience: reasoning ("thinking") cards,
  collapsible tool call cards (input/output, diff coloring), token/cost
  footers, model picker with search across ALL providers/models.
- Sandbox (Alpine + proot, bundled) auto-installs after the server is up;
  "Add tools" is a one-tap extra.

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
Source snapshot: apk-gradle/ (add opencode tarball as
app/src/main/assets/oc_pkg.bin to rebuild with the bundled binary).

NOTE: this build is signed with a NEW persistent debug keystore — uninstall
any older OpenCode build before installing (one-time).
EOF

tar -czf "$STAGE/opencode-p6-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p5-v0.5.0-debug.apk" "$DL/opencode-p5-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p6-v0.6.0-debug.apk"
cp -f "$STAGE/opencode-p6-kit.tar.gz" "$DL/opencode-p6-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p6-v0.6.0-debug.apk \
          opencode-p6-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
