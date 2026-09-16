#!/bin/bash
# p44_gh_ship.sh — commit the P44 tree, push, create Release v0.44.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash). Pattern proven by p43_gh_ship.sh.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
WORK=/home/z/my-project/p44-ship
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
grep -q "versionCode 46" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.44.0-p44" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.44.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.44.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P44 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.44.0-p44: the quiet-collapse release — the field run caught the failure chain live (the agent's own confession: its context collapsed mid-task, it lost the thread, forgot its own greeting, and the sandbox re-probe loop returned), so P44 cures the root and re-faces the app; ONE, the post-compaction anchor: a compaction summary thins the OLDEST turns (the original task) and can summarize away the P42 sandbox ground rules that ride the session-start note — the model re-probes, refills, and re-bills the whole payload each cycle; now the moment a summary lands the session is armed and its next real send rides a Session context block that re-names the thread (opening request, latest real user message, last assistant reply), re-states the ground rules (TLS provisioned, raw DNS dead by design, a probe that fails twice is reported and dropped — never re-probed), names an active window cap, and instructs continuation never restart — armed on live landings and reopens that discover an older summary, consumed once per summary, never wasted on cache beats, kept when the POST fails so the retry still carries it, stripped on display like every ride-along (CompactionPolicy.anchorBlock + RunHub.compactAnchorPending/compactionAnchorBlockTx wired into both send paths); TWO, the collapse is explained when it happens: the honest compaction note now names an active window cap in the same breath (the slider override was the one trigger a user could set without connecting it to the random collapses) — CompactionPolicy.summaryNote(cap); THREE, the Claude face: a seventh palette — warm paper FAF9F5 + terracotta D97757, light surfaces — becomes the DEFAULT face, with a one-time theme_migrated_p44 migration moving old-default graphite riders while every explicit theme choice stands and Graphite stays one tap away (Theme.java PALETTE_DATA/GRAD_DATA/currentId, LauncherIcon gains the seventh alias, manifest alias + warm adaptive icon set); FOUR, Material-3 shape refresh: radius tokens bumped app-wide (cards 18dp, sheets 24dp, rows 14dp, chips 22dp, wells 12dp), user bubbles 18-22dp with the soft tail, tool/thinking/error cards 16dp; FIVE, calm cold-boot: the starting-sandbox veil gains a quieter hierarchy (medium-weight title, breathing accent dot, live elapsed line, softer hint); test pins updated for the new default face (P31Test/P32Test/P32UiTest/P33Test/P33UiTest/P36Test) and P44Test pins the whole anchor surface; 457 JVM tests green (11 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.44.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p44.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.44.0",
    "target_commitish": "main",
    "name": "v0.44.0-p44 — the quiet-collapse release: the amnesia loop cured at the anchor, the Claude face, Material-3 shapes",
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

upload "$DL/opencode-p44-v0.44.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p44-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p44-v0.44.0-debug.apk opencode-p44-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.44.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p44-v0.44.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p44.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.44.0/opencode-p44-v0.44.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p44.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P44 SHIP COMPLETE"
