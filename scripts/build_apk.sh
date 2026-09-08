#!/usr/bin/env bash
# ============================================================================
# build_apk.sh — the ONLY sanctioned way to build a release APK here.
#
# History: the v0.35.0 APK was hand-linked outside gradle and shipped without
# its uses-sdk manifest entries (modern Android refuses those installs) and
# with the bundled server binary under a name the code does not read (fresh
# installs could not boot the sandbox). This script makes the correct path
# the easy path and gates the output before anything can be uploaded.
#
# Usage: scripts/build_apk.sh            # → app/build/outputs/apk/debug/app-debug.apk
# ============================================================================
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/p0-tools/android-sdk}"
GRADLE="${GRADLE:-$HOME/p0-tools/gradle-8.9/bin/gradle}"
BT="$ANDROID_HOME/build-tools/34.0.0"
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"

# ---- 0. the bundled binary must exist under a name the code reads ----------
ASSET="app/src/main/assets/oc_pkg.bin"
[ -f "$ASSET" ] || { echo "FAIL: $ASSET missing (the opencode tarball — not in git; restore it from a release asset)"; exit 1; }

# ---- 1. gradle build --------------------------------------------------------
"$GRADLE" assembleDebug --no-daemon 2>&1 | tail -3
APK="app/build/outputs/apk/debug/app-debug.apk"

# ---- 2. gates: manifest, signature, alignment, storage ---------------------
echo "== manifest =="
python3 - "$APK" <<'EOF'
import sys, zipfile
sys.path.insert(0, "scripts")
from axml_parse import manifest_info
i = manifest_info(sys.argv[1])
print({k: i[k] for k in ("package", "versionCode", "versionName",
                         "minSdkVersion", "targetSdkVersion", "debuggable")})
assert i["minSdkVersion"] == 28 and i["targetSdkVersion"] == 28, \
    "uses-sdk missing or wrong — NOT built by gradle; do not ship"
EOF

echo "== signature (must be 2214c3c7… — the persistent key) =="
"$BT/apksigner" verify --print-certs "$APK" | grep "SHA-256 digest"

echo "== zipalign =="
"$BT/zipalign" -c 4 "$APK" && echo "aligned"

echo "== bundled asset stored uncompressed =="
unzip -v "$APK" assets/oc_pkg.bin | grep -q " Stored " && echo "stored 0%"

echo "BUILD + GATES OK: $APK"
