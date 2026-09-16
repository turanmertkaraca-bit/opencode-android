#!/bin/bash
# p41_gh_ship.sh — commit the P41 tree, push, create Release v0.41.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash via python urllib — no heredoc stdin traps).
# REQUIRES: a VALID token in /home/z/my-project/.gh_token (the previous
# one was revoked — verified "Bad credentials" before this run).
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
WORK=/home/z/my-project/p41-ship
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
# oc_pkg.bin is build-only (gitignored) — the package step already copied
# the server tarball into download/; drop the 60 MB asset before the size gate
if [ -e "$PROJ/app/src/main/assets/oc_pkg.bin" ]; then
  echo "oc_pkg present — removing (gitignored, build-only; tarball already in download/)"
  rm -f "$PROJ/app/src/main/assets/oc_pkg.bin"
fi
BIG=$(find "$PROJ" -path "$PROJ/app/build" -prune -o -path "$PROJ/.gradle" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 43" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.41.0-p41" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.41.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.41.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P41 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.41.0-p41: the memory the meter couldn't see — the field report (a long session re-exploring the same folder, real money spent, the meter reassuring 16 percent) finally traced into the bundled server's own source, opencode 1.18.25, read line by line: when a turn's billed tokens reach the context window the SERVER believes, it compacts silently — the thread is summarized and the model's working set becomes that summary plus at most 2k-15k tokens of recent turns kept verbatim (the server's own default clamp), while the on-screen store keeps the full history so the human sees everything the model no longer does; shallow summaries from a fast model lose the map of the project, the agent re-explores, the payload refills, the server compacts again, and every cycle invalidates the provider prompt cache so the whole payload re-bills at full input price — the P39 detector never fired (totals grow, they do not flatten) and the P40 cure never fired (the summary was non-empty, only shallow), so detection was the wrong shape and the governance was wrong at the source; the fix rides the server's own config: every boot the app pins compaction.preserve_recent_tokens into the sandbox's opencode.json — 30 percent of the picked model's usable window clamped 8k-60k, never written for unknown or tiny windows, write-if-absent so a user's own value always wins, and provably below the usable space for every window 64k-2M so a compaction always frees something while the tail stays real history; the sigma meter's denominator now comes from the LIVE server's limit (the exact number the overflow logic enforces) with the catalog only as fallback, the popover names both numbers when they disagree beyond 20 percent, warns from 70 percent of the enforced window that compaction is close, and a summary landing live in the open chat announces itself in one honest line instead of painting as an ordinary message; P41Test pins the floor table and its safety property, the write-if-absent merge (user keys, even malformed ones, never touched), the server-first window precedence and every note text; 412 JVM tests green (13 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.41.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p41.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.41.0",
    "target_commitish": "main",
    "name": "v0.41.0-p41 — the memory the meter couldn't see: the compaction floor pinned at the source, the meter on the server's own truth",
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

upload "$DL/opencode-p41-v0.41.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p41-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p41-v0.41.0-debug.apk opencode-p41-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.41.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p41-v0.41.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p41.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.41.0/opencode-p41-v0.41.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p41.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P41 SHIP COMPLETE"
