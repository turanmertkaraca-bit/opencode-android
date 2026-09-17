#!/bin/bash
# p48_gh_ship.sh — commit the P48 tree, push, create Release v0.48.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 200,
# APK round-trip hash). Pattern proven by p47_gh_ship.sh.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
WORK=/home/z/my-project/p48-ship
mkdir -p "$WORK"

# --- token must be alive before anything touches git ---
CODE=$(curl -sS -m 30 -o "$WORK/user.json" -w '%{http_code}' \
  -H "Authorization: token $T" https://api.github.com/user)
[ "$CODE" = "200" ] || { echo "token rejected (HTTP $CODE) — paste a fresh one into .gh_token"; exit 1; }
echo "token OK"

cd "$PROJ"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${T}@github.com/${OWNER}/${REPO}.git"

# --- pre-flight gates ---
if grep -rIln --exclude-dir=.git -E "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" "$PROJ" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
if grep -rIln --exclude-dir=.git "$(cat /home/z/my-project/.gh_token)" "$PROJ" 2>/dev/null; then echo "TOKEN LEAK IN TREE — ABORT"; exit 1; fi
# oc_pkg.bin is build-only (gitignored) — drop the 60 MB asset before the size gate
if [ -e "$PROJ/app/src/main/assets/oc_pkg.bin" ]; then
  echo "oc_pkg present — removing (gitignored, build-only; tarball already in download/)"
  rm -f "$PROJ/app/src/main/assets/oc_pkg.bin"
fi
BIG=$(find "$PROJ" -path "$PROJ/app/build" -prune -o -path "$PROJ/.gradle" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 50" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.48.0-p48" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.48.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.48.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P48 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.48.0-p48: the smooth release — the field verdict on P47's true token streaming was 'it glitches the ui, the tokens come and go so fast, it goes up and down' and the thinking bubble needed a fixed length revealing the thought a bit slower (faster than reading speed, nothing still moving when the answer lands) — three oscillators found and damped, wire layer untouched; ONE, the reveal pacer now carries PROFILES with a hard MAX_RATE (the P43 lag ceiling allowed unbounded drain: a 4 kB burst revealed at ~1800 chars/s and fast providers faster still): answers cap at 900 chars/s (StreamPacer.forAnswer), THINKING rows reveal on a calm ≤170 chars/s profile with a 30 s lag ceiling instead of the aggressive 2.2 s drain (StreamPacer.forThinking — the backlog never buys speed), the legacy no-arg constructor keeps the exact uncapped P43 contract (P43 pins unchanged), ChatActivity.pacerFor is kind-aware (reasoning rows get the thought profile, a kind flip re-creates the pacer), and the tick applies a no-regression clamp (a fresh pacer after a snap/resume takes an opening bite — the VIEW never goes backwards); TWO, the collapsed thinking card is a FIXED-LENGTH stage — setMinLines(3) beside the existing setMaxLines(3), so the sliding think-window can no longer change line count per reveal and bounce the whole list (the 'goes up and down'); THREE, the scroll pin stopped fighting itself: the scroll-changed listener used to fire for the app's OWN corrective scrolls too (between a paint and its scrollToEnd the view reads not-at-bottom → pin flips off → drift → re-pin → oscillation) — now a progScroll bracket makes our scrolls invisible to the listener (scrollToEnd brackets synchronously, the pill's smoothScrollTo brackets through a 450 ms flight, a finger on the list cancels the bracket on ACTION_DOWN so the user's drag always wins), an upward drag ALWAYS unpins (scrollY decreasing) while downward motion re-pins only at the true bottom, and the ticker's correction is painted-gated and POSTED so it targets the freshly laid-out height instead of chasing a stale one every 24 ms; FOUR, background sessions made certain: the run always lived in the hub (unchanged), but the paint ticker kept firing into the detached view tree every 24 ms while paused — battery burn and a mid-glide replay glitch on return; onPause stops the smoother, onResume runs snapArrived (every row the hub holds behind the view snaps to settled full text, pacers dropped, one coalesced repaint — only deltas arriving WHILE WATCHING glide); P48Test pins the release (10: think-profile bite/calm-cap/no-flash-drain-at-100k/stall-floor, answer cap, legacy uncapped contract, profile flags, monotonic-bounded, steady-stream keep-up, snapArrived settling, resume-snap through the real ChatActivity pause→stream→resume, fixed three-line window via viewByKey, version); P47's version pin relaxed to a floor (the P46 move); 513 JVM tests green (10 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.48.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p48.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.48.0",
    "target_commitish": "main",
    "name": "v0.48.0-p48 — the smooth release: capped reveal rates, a fixed-length thinking window, an anti-jitter scroll pin, background-arrival snap",
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

upload "$DL/opencode-p48-v0.48.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p48-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 200 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p48-v0.48.0-debug.apk opencode-p48-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.48.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p48-v0.48.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p48.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.48.0/opencode-p48-v0.48.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p48.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P48 SHIP COMPLETE"
