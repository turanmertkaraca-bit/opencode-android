#!/bin/bash
# p17_gh_ship.sh — refresh gh-repo staging from the P17 project, push,
# create Release v0.17.0 with APK + kit + binary tarball, verify.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
SRC=/home/z/my-project/opencode-mobile-p1
STAGE=/home/z/my-project/gh-repo
DL=/home/z/my-project/download

# --- refresh staging: align HARD with the remote before committing ---
cd "$STAGE"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${T}@github.com/${OWNER}/${REPO}.git"
git pull -q origin main 2>/dev/null || true
git fetch -q origin main 2>/dev/null || true
git reset -q --hard origin/main 2>/dev/null || true

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
         p9_package.sh p9_gh_ship.sh p16_package.sh p16_gh_ship.sh \
         p17_package.sh p17_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "/home/z/my-project/scripts/$f" "$STAGE/scripts/" 2>/dev/null || true
done

# paranoia (.git excluded: the push remote URL legitimately holds the token)
# real token shapes only — the app's example hint text must not trip it.
if grep -rIln --exclude-dir=.git -E "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" "$STAGE" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
[ -e "$STAGE/app/src/main/assets/oc_pkg.bin" ] && { echo "oc_pkg staged — ABORT"; exit 1; }
BIG=$(du -ab "$STAGE" | awk '$1 > 50000000 {print $2}')
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.17.0-p17: the edit shower — ONE slim live card in the chat while the agent works (auto-expands only on fresh edits, staggered slide-ins, tap a file for a ≤11-line peek around the EXACT edited line, settles into a one-line record; inotify only while busy); screenshot vision — ◉ chip, native file part first, FREE Zen vision-model ladder (kimi-k2.5-free → …) describes the shot for the agent when the server can't take pixels, image bubbles + full-screen viewer + history replay; cool idle — wake lock now exists ONLY while agent events flow (released on session.idle, Settings 'Cool idle' default ON) + killed the INFINITE veil-dot animator that pulsed a GONE view 24/7; new icon — dark blue-gray gradient, subtle blue chevron + glow, gray underscore; P17 JVM tests (33 green, caught the feed's evict-newest ts=0 bug)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0])"

# --- release v0.17.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release17.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p17.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.17.0",
    "target_commitish": "main",
    "name": "v0.17.0-p17 — the edit shower + eyes (vision) + cool idle + the dark icon",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel17.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release17.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel17.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel17.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /tmp/up17.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up17.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p17-v0.17.0-debug.apk "$DL/opencode-p17-v0.17.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p17-kit.tar.gz       "$DL/opencode-p17-kit.tar.gz"       "application/gzip"
upload opencode-linux-arm64-android.tar.gz "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets 201'd, public HEAD 302 ---
curl -sS -m 30 -H "Authorization: token $T" \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('release:', d.get('name'), '| tag:', d.get('tag_name'), '| draft:', d.get('draft'))
for a in d.get('assets',[]):
    print('  asset:', a['name'], a['size'], 'bytes')
"
for f in opencode-p17-v0.17.0-debug.apk opencode-p17-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.17.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo SHIP-OK
