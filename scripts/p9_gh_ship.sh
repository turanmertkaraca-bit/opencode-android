#!/bin/bash
# p9_gh_ship.sh — refresh gh-repo staging from the P9 project, push,
# create Release v0.9.0 with APK + kit + binary tarball, verify, round-trip.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
SRC=/home/z/my-project/opencode-mobile-p1
STAGE=/home/z/my-project/gh-repo
DL=/home/z/my-project/download

# --- sanity: v0.9.0 must not already exist ---
CODE=$(curl -sS -m 30 -o /tmp/rel9_check.json -w '%{http_code}' \
  -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.9.0")
if [ "$CODE" = "200" ]; then echo "v0.9.0 ALREADY EXISTS — ABORT"; exit 1; fi
echo "v0.9.0 free (check HTTP $CODE)"

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
      "$SRC/README.md" "$SRC/.gitignore" "$STAGE/" 2>/dev/null || true
mkdir -p "$STAGE/scripts"
for f in p0_setup_toolchain.sh p0_rehearse_x86.sh p4_scan_binary.py \
         p4_scan_binary2.py p5_scan_binary.py p5_scan2.py p6_scan_binary.py \
         p6_scan2.py p6_build.sh p6_package.sh p7_package.sh p8_package.sh \
         p9_package.sh p9_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$SRC/scripts/$f" "$STAGE/scripts/" 2>/dev/null \
    || cp -f "/home/z/my-project/scripts/$f" "$STAGE/scripts/" 2>/dev/null || true
done

# paranoia (.git excluded: the push remote URL legitimately holds the token)
if grep -rIl --exclude-dir=.git -e "ghp_" -e "github_pat_" "$STAGE" 2>/dev/null; then
  echo "SECRET FOUND — ABORT"; exit 1; fi
[ -e "$STAGE/app/src/main/assets/oc_pkg.bin" ] && { echo "oc_pkg staged — ABORT"; exit 1; }
BIG=$(du -ab "$STAGE" | awk '$1 > 50000000 {print $2}')
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.9.0-p9: graphite monochrome theme; model picker root-caused (unloaded providers) + opencode zen key row + inline key flow; actionable send errors; permission/stop on a control lane (actually work mid-turn); sandbox doctor fixed; pkg package manager (jq/fzf/yq/gh/shfmt/lazygit/glow); session cost meter; grounded compact chat; deck stride+fling fix+vertical rail; QA bridge"
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
    "name": "v0.9.0-p9 — graphite: mono theme, model fix, pkg, cost meter",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel9.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release9.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel9.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel9.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 900 -o /tmp/up9.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up9.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "upload $NAME → HTTP $CODE ($SIZE)"
}

upload opencode-p9-v0.9.0-debug.apk "$DL/opencode-p9-v0.9.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p9-kit.tar.gz       "$DL/opencode-p9-kit.tar.gz"       "application/gzip"
upload opencode-linux-arm64-android.tar.gz "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- public availability + round-trip checksum ---
sleep 3
for A in opencode-p9-v0.9.0-debug.apk opencode-p9-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L -m 30 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.9.0/${A}")
  echo "public HEAD $A → $H"
done
curl -sS -L -m 600 -o /tmp/rt9.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.9.0/opencode-p9-v0.9.0-debug.apk"
echo "round-trip sha256:"; sha256sum /tmp/rt9.apk; sha256sum "$DL/opencode-p9-v0.9.0-debug.apk"
rm -f /tmp/rt9.apk
echo SHIPPED
