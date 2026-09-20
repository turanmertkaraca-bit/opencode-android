#!/usr/bin/env bash
# ============================================================================
# build.sh — opencode v1.18.25 for Android (bionic/arm64) WITH Bun --bytecode.
#
# Run this on a NORMAL Linux host or CI (x86_64 or arm64) that has Bun 1.4.0.
# It will NOT work inside the app's proot guest: Bun's isolated install +
# global cache breaks @babel/core's `gensync` require there (build-toolchain
# interop, unrelated to opencode).
#
# What it does, in order:
#   1. clones sst/opencode at the tag
#   2. installs deps (scripts skipped; the native node-gyp bits aren't needed)
#   3. installs every @opentui platform package so the bundler can resolve them
#   4. swaps the glibc libopentui.so for the Android (bionic) one in this dir
#   5. teaches @opentui/core that process.platform === "android" is linux
#   6. patches upstream script/build.ts to add an android target + bytecode
#   7. builds and packages the payload tar.gz
#
# Env: OPENCODE_TAG (default v1.18.25), WORK, OUT
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="${OPENCODE_TAG:-v1.18.25}"
WORK="${WORK:-$HERE/work}"
OUT="${OUT:-$HERE/opencode-linux-arm64-android-bytecode.tar.gz}"
# Pin the embedded version to the shipped one. Without this, building on a
# branch makes @opencode-ai/script emit a preview string (0.0.0-<branch>-<ts>)
# into the binary instead of 1.18.25.
export OPENCODE_VERSION="${OPENCODE_VERSION:-1.18.25}"

command -v bun >/dev/null 2>&1 || { echo "ERROR: install Bun 1.4.0 first (https://bun.sh)"; exit 1; }
echo "== bun $(bun --version) =="
[ -f "$HERE/libopentui-android.so" ] || { echo "ERROR: libopentui-android.so missing next to this script"; exit 1; }

rm -rf "$WORK"
echo "== clone sst/opencode $TAG =="
git clone --depth 1 --branch "$TAG" https://github.com/sst/opencode.git "$WORK"
cd "$WORK"

echo "== deps =="
bun install --ignore-scripts
bun install --os="*" --cpu="*" --ignore-scripts @opentui/core@0.4.5 @parcel/watcher@2.5.1

echo "== swap in the Android libopentui =="
PLAT="$(find node_modules/.bun -maxdepth 6 -path '*@opentui/core-linux-arm64/libopentui.so' | head -1)"
[ -n "$PLAT" ] || { echo "ERROR: @opentui/core-linux-arm64 platform lib not found"; exit 1; }
cp "$HERE/libopentui-android.so" "$PLAT"
echo "   $PLAT -> $(stat -c %s "$PLAT" 2>/dev/null || stat -f %z "$PLAT") bytes"

echo "== teach opentui that android is linux =="
python3 - <<'PY'
import glob
patched = 0
for f in glob.glob('node_modules/.bun/@opentui+core@*/node_modules/@opentui/core/chunk-*.js'):
    s = open(f, encoding='utf-8', errors='surrogateescape').read()
    o = s
    s = s.replace('platform: process.platform,',
                  'platform: process.platform === "android" ? "linux" : process.platform,')
    s = s.replace('if (process.platform === "linux") {',
                  'if (process.platform === "linux" || process.platform === "android") {')
    if s != o:
        open(f, 'w', encoding='utf-8', errors='surrogateescape').write(s)
        patched += 1
print("   opentui chunks patched:", patched)
assert patched, "opentui chunk not found / unchanged"
PY

echo "== patch upstream build.ts (android target + bytecode) =="
python3 - <<'PY'
f = 'packages/opencode/script/build.ts'
s = open(f).read()
o = s
s = s.replace('abi?: "musl"', 'abi?: "musl" | "android"')
s = s.replace('const skipEmbedWebUi = process.argv.includes("--skip-embed-web-ui")',
              'const skipEmbedWebUi = process.argv.includes("--skip-embed-web-ui")\n'
              'const androidFlag = process.argv.includes("--android")')
s = s.replace('const targets = singleFlag',
              'const targets = androidFlag ? '
              '[{ os: "linux", arch: "arm64" as const, abi: "android" as const }] : singleFlag')
s = s.replace('    compile: {\n      autoloadBunfig: false,',
              '    compile: {\n      bytecode: true,\n      autoloadBunfig: false,')
assert s != o, "build.ts unchanged"
open(f, 'w').write(s)
print("   build.ts patched")
PY

echo "== build (android + bytecode) =="
bun run packages/opencode/script/build.ts --android --skip-embed-web-ui --skip-install

BIN="packages/opencode/dist/opencode-linux-arm64-android/bin/opencode"
[ -f "$BIN" ] || { echo "ERROR: no binary produced"; exit 1; }
file "$BIN"
echo "   size: $(stat -c %s "$BIN" 2>/dev/null || stat -f %z "$BIN") bytes"

tar -C "$(dirname "$BIN")" -czf "$OUT" opencode
echo "== wrote $OUT =="
sha256sum "$OUT"
echo
echo "Install: replace the payload, bump EXPECT_SHA in scripts/build_apk.sh,"
echo "rebuild the APK. Test the bytecode vs non-bytecode binary on-device and"
echo "keep whichever boots faster."
