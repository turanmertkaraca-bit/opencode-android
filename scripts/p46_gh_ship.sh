#!/bin/bash
# p46_gh_ship.sh — commit the P46 tree, push, create Release v0.46.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 200,
# APK round-trip hash). Pattern proven by p45_gh_ship.sh.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
WORK=/home/z/my-project/p46-ship
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
grep -q "versionCode 48" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.46.0-p46" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.46.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.46.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P46 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.46.0-p46: the keep-it-alive-and-honest release — the field report executed point by point; ONE, real-time token streaming cured: the /event feed re-sends the WHOLE cumulative part text on every delta (opencode semantics), so on a phone the oc-sse thread drowned in quadratic JSON parses on thinking-model runs and the UI burst-fed (~15 s waits, the exact field report) — the new PartGovernor pre-screens each raw frame with a plain indexOf scan (no JSON parse for held frames), keys text/reasoning parts by sessionID|messageID|prt-id, applies at most one frame per part per 250 ms, holds only the latest raw snapshot in between (lossless: every frame IS the full state), force-flushes on message.updated/session.idle/session.error/stream-end/overflow, passes through unknown shapes and >256 KB frames — wired into ServerService.startSse between the SSE reader and ingest; TWO, git clone fixed where FUSE breaks it: shared storage cannot take git's atomic tmp+rename dance (the field proof: clone works in the private rootfs /tmp, dies in the project folder), so (a) new projects DEFAULT TO PRIVATE app storage — Projects.seed plants the Playground under files/projects (ext4, fast, no permission dance) and HomeActivity.pickBase opens the folder picker there with an honest hint, ↑ .. still reaches /sdcard on purpose, and (b) the git shim detects clone/init whose destination lives on FUSE (pure fusePath rule mirrored 1:1 into the generated script via the FUSE_CASE constant) and injects --separate-git-dir so the real .git lands in files/home/.sep-git/<hash>.git and the worktree gets the .git FILE pointer — every later commit/pack/lock rename happens on ext4, not just the initial clone; explicit --separate-git-dir/--bare/-C are never touched, missing git still prints the import hint; THREE, the live file watcher verified path-driven (P16 inotify stack follows the project's real root — private ext4 is native inotify territory); FOUR, long-session stability sweep: seenPermIds hard-capped at 256 like answeredPermIds, governor pending set bounded at 64 keys / 256 KB per frame, lastApplied map capped at 512; P46Test pins the release (12: scan, state machine, storm economics — 300 deltas at 20 ms apply in bounded work and never lose the final snapshot, fuse rule, shim injection incl. mksh safety + TLS pin, version via SettingsActivity.VERSION_TAG); 487 JVM tests green (12 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.46.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p46.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.46.0",
    "target_commitish": "main",
    "name": "v0.46.0-p46 — the keep-it-alive-and-honest release: real-time streaming cured, git clone fixed on shared storage, private project storage",
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

upload "$DL/opencode-p46-v0.46.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p46-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 200 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p46-v0.46.0-debug.apk opencode-p46-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.46.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p46-v0.46.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p46.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.46.0/opencode-p46-v0.46.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p46.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P46 SHIP COMPLETE"
