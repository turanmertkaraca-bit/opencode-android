#!/bin/bash
# p9_gh_ship.sh — refresh gh-repo staging from the P9 project, push,
# create Release v0.9.0 with APK + kit + binary tarball, verify.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
SRC=/home/z/my-project/opencode-mobile-p1
STAGE=/home/z/my-project/gh-repo
DL=/home/z/my-project/download

# --- refresh staging ---
cd "$STAGE"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git pull -q origin main 2>/dev/null || true

rm -rf "$STAGE/app" "$STAGE/scripts"
cp -r "$SRC/app" "$STAGE/app"
rm -rf "$STAGE/app/build" "$STAGE/app/.gradle"
rm -f  "$STAGE/app/src/main/assets/oc_pkg.bin"
cp -f "$SRC/build.gradle" "$SRC/settings.gradle" "$SRC/gradle.properties" \
      "$SRC/README.md" "$SRC/.gitignore" "$STAGE/"
mkdir -p "$STAGE/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p4_scan_binary.py \
         p4_scan_binary2.py p5_scan_binary.py p5_scan2.py p6_scan_binary.py \
         p6_scan2.py p6_build.sh p6_package.sh p7_package.sh p8_package.sh \
         p9_package.sh TestSandbox.java ElfGateTest.java TestTarGz.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/scripts/" 2>/dev/null || true
done

# paranoia (.git excluded: the push remote URL legitimately holds the token)
if grep -rIl --exclude-dir=.git "ghp_" "$STAGE" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
[ -e "$STAGE/app/src/main/assets/oc_pkg.bin" ] && { echo "oc_pkg staged — ABORT"; exit 1; }
BIG=$(du -ab "$STAGE" | awk '$1 > 50000000 {print $2}')
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.9.0-p9: sandbox package manager (pkg/apk, no proot) + realtime token-by-token chat + full model catalog (models.dev merge) + settings redesign + vertical page rail"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0])"

# --- release v0.9.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release9.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p9.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.9.0",
    "target_commitish": "main",
    "name": "v0.9.0-p9 — pkg package manager + realtime chat + full model catalog",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -o /tmp/gh_release9.json -w "%{http_code}" -X POST \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases" \
  --data-binary @/tmp/gh_release9.json.in)
echo "release create: HTTP $CODE"
RID=$(python3 -c "import json;print(json.load(open('/tmp/gh_release9.json')).get('id',''))")
if [ -z "$RID" ]; then echo "release id missing"; exit 1; fi

upload() { # name, path, ctype
  U=$(curl -sS -o /tmp/up.json -w "%{http_code}" -X POST \
    -H "Authorization: token $T" -H "Content-Type: $3" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/$RID/assets?name=$1" \
    --data-binary @"$2")
  echo "upload $1: HTTP $U"
  python3 - "$1" <<'PY'
import json,sys
d=json.load(open('/tmp/up.json'))
a=d.get('asset') or d
print('  state:', a.get('state'), 'size:', a.get('size'))
PY
}

upload opencode-p9-v0.9.0-debug.apk "$DL/opencode-p9-v0.9.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p9-kit.tar.gz       "$DL/opencode-p9-kit.tar.gz"       "application/gzip"
upload opencode-linux-arm64-android.tar.gz "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: release page + round-trip sha256 of the APK ---
curl -sS -o /dev/null -w "release page: HTTP %{http_code}\n" \
  "https://github.com/${OWNER}/${REPO}/releases/tag/v0.9.0"
SHA1=$(sha256sum "$DL/opencode-p9-v0.9.0-debug.apk" | cut -d' ' -f1)
curl -sSL -o /tmp/rt.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.9.0/opencode-p9-v0.9.0-debug.apk"
SHA2=$(sha256sum /tmp/rt.apk | cut -d' ' -f1)
echo "local  sha: $SHA1"
echo "remote sha: $SHA2"
[ "$SHA1" = "$SHA2" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo SHIP OK
