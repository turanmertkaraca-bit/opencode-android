#!/bin/bash
# p50_gh_ship.sh — commit the P50 tree on the clean rewritten history,
# push, create Release v0.50.0 with APK + kit + binary tarball, verify
# (assets listed, public HEAD 302, APK round-trip hash).
# Pattern proven by p45..p49 ship scripts.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/p50-work/repo
DL=/home/z/my-project/download
WORK=/home/z/my-project/p50-ship
mkdir -p "$WORK"

# --- token must be alive before anything touches git ---
CODE=$(curl -sS -m 30 -o "$WORK/user.json" -w '%{http_code}' \
  -H "Authorization: token $T" https://api.github.com/user)
[ "$CODE" = "200" ] || { echo "token rejected (HTTP $CODE)"; exit 1; }
echo "token OK"

cd "$PROJ"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${T}@github.com/${OWNER}/${REPO}.git"

# --- pre-flight gates ---
if grep -rIln --exclude-dir=.git -E "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" "$PROJ" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
if grep -rIln --exclude-dir=.git "$(cat /home/z/my-project/.gh_token)" "$PROJ" 2>/dev/null; then echo "TOKEN LEAK IN TREE — ABORT"; exit 1; fi
if [ -e "$PROJ/app/src/main/assets/oc_pkg.bin" ]; then
  echo "oc_pkg present — removing (gitignored, build-only)"
  rm -f "$PROJ/app/src/main/assets/oc_pkg.bin"
fi
BIG=$(find "$PROJ" -path "$PROJ/app/build" -prune -o -path "$PROJ/.gradle" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 52" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.50.0-p50" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.50.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.50.0 already exists — ABORT"; exit 1; }
# the remote must be exactly at our rewritten HEAD (it was force-pushed)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.50.0-p50: the background release — background working made certain and the question tool answerable in place. BACKGROUND: auto-hibernate default OFF (the P31 RAM-saver was the self-stopper behind the field's background deaths — hibernation is the opt-in now), a persisted WANT flag + allow-while-idle watchdog chain resurrects the service after an OS/OEM process kill (WatchdogReceiver, ~4 min cadence, self-perpetuating, keepalive pref default ON), onTaskRemoved re-arms instead of dying, the battery-exemption one-tap rides the persistent notification (Allow background run) until granted, Cool idle applies LIVE (the pref used to be read once at spawn — flips were silent no-ops until a restart), a pending question counts as work-in-flight in every sleep decision. QUESTIONS: RunHub stores question.asked/v2 asks per session and removes them on replied/rejected, the chat pins an interactive card — header, prompt, options as chips (radio / multi-toggle), one Answer + one Skip — replying through POST /api/session/{sid}/question/{rid}/reply with one label array per question (Questions.answersJson keeps indexes aligned, empty array = Unanswered, the server's own echo rule), HTTP backfill of pending asks on chat open (fetchPendingQuestions), the notification flips to question-needs-answer and back, hibernate never fires while an ask waits. HYGIENE: README rewritten (one professional page — no verbatim user quotes anywhere), every commit message in the published history rewritten clean (force-pushed main + all tags), code/test comments paraphrased (the leak scan ends at zero). P50Test pins the release (14: hibernate default, watchdog decision, question parse against the exact field shape incl. garbage tolerance, reply-body shape + quote escaping, the echo rule, v2 event aliases, the store round-trip through the real hub listener, version); JVM suite green"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.50.0 ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p50.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.50.0",
    "target_commitish": "main",
    "name": "v0.50.0-p50 — the background release: background working made certain, the question tool answerable",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o "$WORK/rel.json" -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @"$WORK/release.json.in" \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('$WORK/rel.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; head -5 "$WORK/rel.json"; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 900 -o "$WORK/up.json" -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p50-v0.50.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p50-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p50-v0.50.0-debug.apk opencode-p50-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.50.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p50-v0.50.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p50.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.50.0/opencode-p50-v0.50.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p50.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P50 SHIP COMPLETE"
