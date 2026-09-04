#!/bin/bash
# p9_package.sh — ship v0.9.0-p9: replace p8 APK/kit in download/, refresh
# SHA256SUMS + kit README. download/ stays at exactly 6 files.
set -e
PROJ=/home/z/my-project/opencode-mobile-p1
DL=/home/z/my-project/download
OUT="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
STAGE=/home/z/my-project/p9-kit-stage

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
         p6_scan2.py p6_build.sh p6_package.sh p7_package.sh p8_package.sh \
         p9_package.sh TestSandbox.java ElfGateTest.java TestTarGz.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/apk-gradle/scripts/" 2>/dev/null || true
done

# --- kit README ---
cat > "$STAGE/README.md" <<'EOF'
# opencode-android v0.9.0-p9

Install `opencode-p9-v0.9.0-debug.apk` (sideload; allow unknown apps).
Same signing key as v0.6.0-v0.8.0 → installs as an UPDATE, no uninstall.

## What P9 is — the "package manager + realtime chat" release

- SANDBOX PACKAGE MANAGER (the big one): the app now ships the Alpine
  minirootfs (aarch64, ~4 MB) and a real package manager — `pkg` — with
  NO proot. Every Alpine binary runs through the musl dynamic loader
  directly from app-private storage (Termux-style exec, targetSdk 28).
  The agent can now do:  pkg update / pkg install python3 py3-pip git
  nodejs npm gcc make ripgrep jq curl openssh-client …
  Wrappers for every installed command are auto-linked onto PATH
  (pkg rehash is automatic after install/remove).
  Network for the Alpine world goes through the in-app CONNECT proxy
  (bionic DNS) exported as http(s)_proxy — git/pip/curl/apk all work.
  Package/index authenticity: apk RSA signatures (repo fetched over http,
  no CA bundle in minirootfs — signatures are the security boundary).
- REALTIME CHAT: token-by-token streaming feel — a 24 ms smoothing ticker
  paints adaptive char-steps with a live caret while the part streams,
  then finalizes with full markdown render ONCE. In-place TextView
  updates (no full-list re-render per frame). Long-press any message to
  copy.
- CHAT REDESIGN: gradient user bubbles (asymmetric tail), cleaner
  assistant blocks, THINKING cards, tool cards with status dots + friendly
  names (shell, read file, edit file…), error cards, centered system
  pills, hero empty state with tappable starter prompts, raised composer
  bar with ⌘ / Build-Plan / model chips and a gradient send FAB, sessions
  button in the header.
- FULL MODEL CATALOG: /config/providers (what is usable NOW) merged with
  the complete models.dev catalog (what EXISTS) — hundreds of models,
  search over everything, "(no key)" / "(add API key)" markers, disk
  cache so the sheet opens instantly.
- SETTINGS FROM ZERO: gradient hero server card (live status, sandbox
  root, restart), section cards with icon discs + chevrons, custom
  animated switch (no more Android 4 Switch), SANDBOX section with pkg
  status + install/repair + rehash + doctor.
- PROJECT DECK: the page indicator is now a VERTICAL rail on the right
  edge (the old horizontal dots read as left/right — fixed).
- Diagnostics gained pkg presets: pkg version / pkg list / pkg rehash.

## Build from source

apk-gradle/ is the full Gradle project (namespace ai.opencode.app,
minSdk=targetSdk 28 on purpose — the W^X exec allowance). Add the
opencode tarball as app/src/main/assets/oc_pkg.bin, then:
  JAVA_HOME=<jdk21> gradle assembleDebug

Full docs: https://github.com/turanmertkaraca-bit/opencode-android
EOF

tar -czf "$STAGE/opencode-p9-kit.tar.gz" -C "$STAGE" README.md apk-gradle

# --- replace-in-place in download/ ---
rm -f "$DL/opencode-p8-v0.8.0-debug.apk" "$DL/opencode-p8-kit.tar.gz"
cp -f "$OUT" "$DL/opencode-p9-v0.9.0-debug.apk"
cp -f "$STAGE/opencode-p9-kit.tar.gz" "$DL/opencode-p9-kit.tar.gz"

# --- checksums (4 artifacts) ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p9-v0.9.0-debug.apk \
          opencode-p9-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt
cat SHA256SUMS.txt

# --- file count guard ---
N=$(ls -A "$DL" | wc -l)
echo "download/ file count: $N (must be 6)"
[ "$N" -eq 6 ] || { echo "FILE COUNT VIOLATION"; exit 1; }
echo OK
