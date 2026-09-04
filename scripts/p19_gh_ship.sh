#!/bin/bash
# p19_gh_ship.sh — refresh gh-repo staging from the project root, push,
# create Release v0.19.0 with APK + kit + binary tarball, verify.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
SRC=/home/z/my-project
STAGE=/home/z/my-project/gh-repo
DL=/home/z/my-project/download

# --- refresh staging: align HARD with the remote before committing ---
cd "$STAGE"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${T}@github.com/${OWNER}/${REPO}.git"
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
         p16_package.sh p16_gh_ship.sh p17_package.sh p17_gh_ship.sh \
         p18_toolchain_fix.sh p18_package.sh p18_gh_ship.sh \
         p19_package.sh p19_gh_ship.sh \
         ElfGateTest.java TestTarGz.java; do
  cp -f "$SRC/scripts/$f" "$STAGE/scripts/" 2>/dev/null \
    || cp -f "/tmp/my-project/scripts/$f" "$STAGE/scripts/" 2>/dev/null || true
done
# keep the historical P9 ship scripts on main if the snapshot still has them
for f in p9_package.sh p9_gh_ship.sh; do
  cp -f "/tmp/my-project/scripts/$f" "$STAGE/scripts/" 2>/dev/null || true
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
git commit -q -m "v0.19.0-p19: the crash killer — port freedom (the app asks the kernel for a bindable port before every spawn: 4096 when free, kernel-assigned the moment it isn't, so a wedged orphan can never dead-lock the sandbox into a cold boot again; opencode's --port 0 verified NOT ephemeral on the rig), orphan sweep (exact-binary /proc cmdline match, kill -9 before every spawn), health gated on the child's own listen banner (never mistake a wedged ghost on 4096 for healthy), 30s diag heartbeat (whole-process deaths leave evidence now), live-edit shower un-murdered (watchdog quiet 3.5s → 10min: bash runs ARE silent; busy self-heals when parts arrive for the session; card restyled onto the thought-card surface, exists from run start, disappears when a run produced zero edits); upstream v1.18.25 stress-tested standalone (burst + storm + kill -9 respawn: survived everything — the killer was device-level process death); P19 JVM tests (58 total green)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0])"

# --- release v0.19.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release19.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p19.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.19.0",
    "target_commitish": "main",
    "name": "v0.19.0-p19 — the crash killer: port freedom + orphan sweep + the edit shower that actually shows",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel19.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release19.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel19.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel19.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /tmp/up19.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up19.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p19-v0.19.0-debug.apk "$DL/opencode-p19-v0.19.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p19-kit.tar.gz       "$DL/opencode-p19-kit.tar.gz"       "application/gzip"
upload opencode-linux-arm64-android.tar.gz "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed, public HEAD 302, APK round-trip hash ---
curl -sS -m 30 -H "Authorization: token $T" \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('release:', d.get('name'), '| tag:', d.get('tag_name'), '| draft:', d.get('draft'))
for a in d.get('assets',[]):
    print('  asset:', a['name'], a['size'], 'bytes', a.get('digest',''))
"
for f in opencode-p19-v0.19.0-debug.apk opencode-p19-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.19.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /tmp/rt-p19.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.19.0/opencode-p19-v0.19.0-debug.apk"
sha256sum /tmp/rt-p19.apk "$DL/opencode-p19-v0.19.0-debug.apk"
echo SHIP-OK
