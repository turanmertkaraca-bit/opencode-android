#!/bin/bash
# p20_gh_ship.sh — commit the P20 tree (the project IS gh-repo in this
# layout), push, create Release v0.20.0 with APK + kit + binary tarball,
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
# real token shapes only — the app's example hint text must not trip it.
if grep -rIln --exclude-dir=.git -E "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" "$STAGE" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
[ -e "$STAGE/app/src/main/assets/oc_pkg.bin" ] && { echo "oc_pkg staged — ABORT"; exit 1; }
[ -e "$STAGE/app/build/outputs/apk/debug/app-debug.apk" ] && git check-ignore -q app/build/outputs/apk/debug/app-debug.apk || { echo "build output not ignored — ABORT"; exit 1; }
BIG=$(find "$STAGE" -path "$STAGE/app/build" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 22" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.20.0-p20: the background survivor — the empty thought bubble is dead (chat unsubscribes from the feed in onPause and onResume only refetched an EMPTY list, so every part that fired while away was lost forever: reasoning cards born before the pause stayed empty; now EVERY resume replays the session from the server's message store — known parts upsert in place, missed parts append in order, trimmed rows never resurrect, session switches fence the replay); return-from-background settles the truth (run finished while away → busy clears with a one-line note instead of 'working…' forever; run still going → P19 self-heal re-arms); dead THINKING cards purged at settle (empty-born reasoning parts hidden instead of unopenable forever); token-by-token streaming now covers THINKING (P9 ticker was assistant-only: collapsed card grows a live one-line ticker of the freshest thought via Resilience.thinkWindow, open card streams its body with the caret, snapped rows repaint immediately — no stale carets); 9 new JVM tests (67 total green)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.20.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release20.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p20.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.20.0",
    "target_commitish": "main",
    "name": "v0.20.0-p20 — the background survivor: the empty thought bubble is dead + thinking streams token-by-token",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel20.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release20.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel20.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel20.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /tmp/up20.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up20.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p20-v0.20.0-debug.apk "$DL/opencode-p20-v0.20.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p20-kit.tar.gz       "$DL/opencode-p20-kit.tar.gz"       "application/gzip"
upload opencode-linux-arm64-android.tar.gz "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed, public HEAD 302, APK round-trip hash ---
curl -sS -m 30 -H "Authorization: token $T" \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('release:', d.get('name'), '| tag:', d.get('tag_name'), '| draft:', d.get('draft'))
for a in d.get('assets',[]):
    print('  asset:', a['name'], a['size'], 'bytes', a.get('digest','')[:16])
"
for f in opencode-p20-v0.20.0-debug.apk opencode-p20-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.20.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /tmp/rt-p20.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.20.0/opencode-p20-v0.20.0-debug.apk"
sha256sum /tmp/rt-p20.apk "$DL/opencode-p20-v0.20.0-debug.apk"
echo SHIP-OK
