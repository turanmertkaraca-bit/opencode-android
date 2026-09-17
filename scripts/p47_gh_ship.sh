#!/bin/bash
# p47_gh_ship.sh — commit the P47 tree, push, create Release v0.47.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 200,
# APK round-trip hash). Pattern proven by p46_gh_ship.sh.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo
DL=/tmp/my-project/download
WORK=/home/z/my-project/p47-ship
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
grep -q "versionCode 49" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.47.0-p47" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.47.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.47.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P47 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.47.0-p47: the live-token release — the streaming cure found where it actually lives: a timestamped probe (mock 30 ms/token provider against the real opencode v1.18.25 server) proved /event carries the per-token increments as separate tiny message.part.delta frames in real time while the big message.part.updated full-text snapshots fire only at part BOUNDARIES (created-empty → final) — the app consumed only the snapshots, so the screen showed nothing while the model spoke and the whole reply materialized at once (the field's 'it just appears instantly'); P47 consumes the deltas: RunHub.onEvent routes message.part.delta (field=text, guard on messageID/partID/delta) into the new applyDelta → deltaAppendTx which appends to the row keyed exactly like applyPart keys parts (messageID|partID) — create-on-demand for a swallowed created-empty frame (assistant-text only, user-role and snapshot-owned rows guarded at the entry point), boundary snapshots keep reconciling via mergeText's growth rule (stale prefix can never roll a row back, final snapshot lands exactly once); delta frames count as runHadOutput (the stream-flake one-shot retry stays honest) and touchRun keeps liveness/wake-lock fresh; PartGovernor corrected where the old wrong diagnosis lived and now documented as the snapshot backstop — delta frames pass through untouched by construction (offer gates partKey by the message.part.updated type), pinned; ChatActivity.touchViewInner gains the P47 in-place fast path — once a streamed row's view exists, further growth hands to the smoother and the ticker paints in place (no per-token view rebuild; generalizes the P20 collapsed-thought fast path; the ticker is a text painter, not an animation, so motion-off devices stream too); git shim: after a successful FUSE clone/init (the P46 --separate-git-dir branch) the fresh repo is TUNED for its FUSE worktree — core.filemode=false, core.trustctime=false, core.checkstat=minimal, core.untrackedcache=false, core.fsmonitor=false, gc.auto=0, all stamped into the PRIVATE gitdir config on ext4 (no exec before the stamps; clone failure propagates rc; private-dest and explicit-flag paths unstamped, verified behaviorally against real git); P47Test pins the release (13: delta passthrough + scan-gate, snapshot throttle around a delta stream, append/create-on-demand/user-guard/snapshot-owned-guard/blank-guard, onEvent wiring via the package-visible HUB, full snapshot→deltas→snapshot turn, shim tuning, version); 503 JVM tests green (13 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.47.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p47.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.47.0",
    "target_commitish": "main",
    "name": "v0.47.0-p47 — the live-token release: true token-by-token streaming (the delta stream was on the wire all along), FUSE-repo git tuning",
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

upload "$DL/opencode-p47-v0.47.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p47-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 200 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p47-v0.47.0-debug.apk opencode-p47-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.47.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p47-v0.47.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p47.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.47.0/opencode-p47-v0.47.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p47.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P47 SHIP COMPLETE"
