#!/bin/bash
# p43_gh_ship.sh — commit the P43 tree, push, create Release v0.43.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash). Pattern proven by p42_gh_ship.sh.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
WORK=/home/z/my-project/p43-ship
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
grep -q "versionCode 45" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.43.0-p43" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.43.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.43.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P43 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.43.0-p43: the realtime-feel release — the field report was about feel, and each complaint traced to a real source: ONE, the token stream flashed in bursts (the old reveal rule was a catch-up rule, 3+remaining/8 chars per 24 ms tick, which emptied each multi-kilobyte provider burst in ~0.5 s of rapid repaints then froze until the next — StreamPacer replaces it with a pacing rule: an EWMA over observed arrival drives the reveal rate (1.2x, 260 chars/s floor) and a 2.2 s lag ceiling drains any backlog as a fast readable glide, so a 4 kB burst finishes flowing ~2 s after its last byte instead of in one frame); TWO, the collapsed THINKING card showed one single line flickering too fast to read (now a three-line tail-anchored window of the freshest raw reasoning — never a summary, the open card still streams the full thought — thinkWindow widened 110-240 and the live TextView setMaxLines(3)); THREE, the keyboard still opened by itself in half the app (every sheet window is pinned SOFT_INPUT_STATE_ALWAYS_HIDDEN and Sheet.focus no longer raises the IME — model picker, new chat, API keys, file rename, palette, settings; the keyboard appears only when a field is tapped, and the mid-typing focus restoration in the chat is untouched); FOUR, idle sessions paid the cold-cache tax (the cachebeat idea, in-app: after 4 quiet minutes one tiny automated beat message — a system-reminder instructing 'do not run tools, reply with exactly: still here' — re-reads the prefix at the cached rate and restarts the provider TTL clock, max 6 beats per quiet stretch, real activity resets the stretch, never while a run streams, exponential backoff on failure, a Cache beats switch in Settings-keep-alive, the chat paints each beat as one visible '♡ cache beat' line via a NoteStrip signature that renders the label instead of vanishing, and the sigma popover reports cache health beside it); FIVE, long sessions lost their older tool cards silently (a reopened chat rendered only the last 80 stored messages and live trim cut at 450 rows — reopen render raised to 600 with an honest one-line note when the cap bites on a fresh bind, live trim raised to 1200/900, money ledger untouched since it always walked the full store); SIX, grounding: a bare greeting mid-task got a fresh-chat 'Hello! What would you like help with?' back despite the turn billing the full history (GreetGuard rides a Session context reminder on bare-greeting sends into deep sessions — thread opening request, latest real user message, last assistant reply, and an instruction to answer as the assistant that has been there all along; fresh chats and real instructions never match the classifier), a folder touch in the live card printed a raw EISDIR exception ((folder — no file to preview) now, TOCTOU-safe in the catch), the sigma pill's middle-ellipsize hid the window and percent leaving a cost tail that read like a mystery fraction (the pill now paints a compact depth+percent meter, the full form stays in the popover), and long clones re-ran from scratch after a mid-transfer drop (the env note teaches --depth 1 and git fetch resume, the Debian rootfs .gitconfig gains lowSpeedLimit/lowSpeedTime/postBuffer so dead links give up in 30 s instead of hanging); NoteStrip gains the Cache beat and Session context signatures with a cross-pinned test, CacheBeat.popoverLine surfaces the feature where cache health is discussed, and the whole reveal/policy/classifier surface is pinned in P43Test; 446 JVM tests green (21 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.43.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p43.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.43.0",
    "target_commitish": "main",
    "name": "v0.43.0-p43 — the realtime-feel release: a stream that glides, a thought you can watch, a cache that stays warm",
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

upload "$DL/opencode-p43-v0.43.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p43-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p43-v0.43.0-debug.apk opencode-p43-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.43.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p43-v0.43.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p43.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.43.0/opencode-p43-v0.43.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p43.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P43 SHIP COMPLETE"
