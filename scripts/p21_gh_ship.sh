#!/bin/bash
# p21_gh_ship.sh — commit the P21 tree (the project IS gh-repo in this
# layout), push, create Release v0.21.0 with APK + kit + binary tarball,
# verify (assets listed, public HEAD 302, APK round-trip hash).
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
STAGE=/home/z/my-project/gh-repo
DL=/home/z/my-project/download

cd "$STAGE"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${T}@github.com/${OWNER}/${REPO}.git"

# --- pre-flight gates ---
if grep -rIln --exclude-dir=.git -E "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" "$STAGE" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
[ -e "$STAGE/app/src/main/assets/oc_pkg.bin" ] && { echo "oc_pkg staged — ABORT"; exit 1; }
BIG=$(find "$STAGE" -path "$STAGE/app/build" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 23" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.21.0-p21: the stable one — keyboard fix (fullScroll() runs a FOCUS SEARCH: every 24ms streaming autoscroll could move focus into the selectable message rows and detach the IME from the chat box; scrolling is now focus-free at 3 call sites + touchView/renderAll guards restore input focus if a row rebuild takes it); exit forensics (Diagnostics now lists the system's own ApplicationExitInfo records — LOW MEMORY / ANR / NATIVE / signal / freezer — naming the process killer retroactively for the P19/P20 field deaths, no adb needed; reason constants pinned against the API-34 android.jar via javap); send-crash hardening (resume replays no longer re-decode every image: cache-file reuse + LRU-hit repaint kills the 12MB×3-per-image per-return LMKD invitation; settle rule ignores synthetic trailing messages — caught by the new suite); replay-key drift guard (parent message id injected into parts); tested-before-ship: real opencode v1.18.25 server ran on the rig, a real free model completed a turn, the REAL /session messages + 176 SSE events replay through the settle/replay logic in the JVM suite (73 tests green, fixtures included)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.21.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release21.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p21.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.21.0",
    "target_commitish": "main",
    "name": "v0.21.0-p21 — the stable one: the keyboard stays in the chat box + the exit forensics that name the killer",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel21.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release21.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel21.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel21.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /tmp/up21.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up21.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p21-v0.21.0-debug.apk "$DL/opencode-p21-v0.21.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p21-kit.tar.gz       "$DL/opencode-p21-kit.tar.gz"       "application/gzip"
upload opencode-linux-arm64-android.tar.gz "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed, public HEAD 302, APK round-trip hash ---
curl -sS -m 30 -H "Authorization: token $T" \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('release:', d.get('name'), '| tag:', d.get('tag_name'), '| draft:', d.get('draft'))
for a in d.get('assets',[]):
    print('  asset:', a['name'], a['size'], 'bytes', str(a.get('digest',''))[:19])
"
for f in opencode-p21-v0.21.0-debug.apk opencode-p21-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.21.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /tmp/rt-p21.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.21.0/opencode-p21-v0.21.0-debug.apk"
sha256sum /tmp/rt-p21.apk "$DL/opencode-p21-v0.21.0-debug.apk"
echo SHIP-OK
