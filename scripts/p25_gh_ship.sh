#!/bin/bash
# p25_gh_ship.sh — commit the P25 tree, push, create Release v0.25.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash).
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
grep -q "versionCode 27" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.25.0-p25" "$STAGE/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# ground truth: remote HEAD must still be the P24 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.25.0-p25: runs outlive the chat — a new RunHub (run engine) owns the transcript, busy state, send orchestration, the SSE consumption and the live-edit watcher for the whole process lifetime; ServerService dropped every parsed event when no screen listened, and ChatActivity unsubscribed on pause, so leaving the chat orphaned the run's rendering state — now the chat is a pure view (bind on resume, unbind on pause, nothing else), the message POST runs on the hub's pool so the screen can close mid-turn, re-entering mid-run re-PULLs the session from the server API through the same upsert pipeline (never re-POSTs, never restarts a healthy stream), only the stop button aborts, and a swipe-kill mid-run recovers next launch via a persisted run-state file (session intact, one honest interrupted note, busy=false, orphan sweep + supervisor unchanged); transcripts are per-session (a run streaming into a background session can no longer leak into the displayed chat), unattended auto-allow answers with no screen open, the Σ pill reads as CONTEXT DEPTH vs the model window ('48k / 200k · 24%' from last-turn tokens + models.dev limit.context; the \$ meter untouched), the edit shower is a compact live tree (only-touched dirs, newest branch auto-expanded, ~200dp height cap, newest-event highlight) and the peek LIVE-UPDATES on every fs event for the selected file while the run is active; screen-by-screen polish (card rhythm, touch targets; Files/Settings/Diagnostics audited and left on their verified rhythm); 134 JVM tests green (31 new: context meter + window parse, tree grouping/caps, RunHub pipeline pins, run-state round-trip, interrupted note one-shot) — and the suite caught a real dormant bug: the run-time model-not-found matcher checked 'providedmodelnotfound' but the exception is ProviderModelNotFoundError, so that self-heal trigger could never fire"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.25.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release25.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p25.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.25.0",
    "target_commitish": "main",
    "name": "v0.25.0-p25 — runs outlive the chat: leave anytime, the agent keeps working",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel25.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release25.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/home/z/my-project/scripts/gh_rel25.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /home/z/my-project/scripts/gh_rel25.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /home/z/my-project/scripts/up25.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/home/z/my-project/scripts/up25.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p25-v0.25.0-debug.apk "$DL/opencode-p25-v0.25.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p25-kit.tar.gz       "$DL/opencode-p25-kit.tar.gz"       "application/gzip"
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
for f in opencode-p25-v0.25.0-debug.apk opencode-p25-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.25.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /home/z/my-project/scripts/rt-p25.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.25.0/opencode-p25-v0.25.0-debug.apk"
sha256sum /home/z/my-project/scripts/rt-p25.apk "$DL/opencode-p25-v0.25.0-debug.apk"
echo SHIP-OK
