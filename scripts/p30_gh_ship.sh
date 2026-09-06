#!/bin/bash
# p30_gh_ship.sh — commit the P30 tree, push, create Release v0.30.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash).
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download

cd "$PROJ"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${T}@github.com/${OWNER}/${REPO}.git"

# --- pre-flight gates ---
if grep -rIln --exclude-dir=.git -E "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" "$PROJ" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
# oc_pkg.bin is build-only (gitignored) — keep the tree clean anyway
if [ -e "$PROJ/app/src/main/assets/oc_pkg.bin" ]; then
  echo "oc_pkg present — removing (gitignored, build-only)"
  rm -f "$PROJ/app/src/main/assets/oc_pkg.bin"
fi
BIG=$(find "$PROJ" -path "$PROJ/app/build" -prune -o -path "$PROJ/.gradle" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 32" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.30.0-p30" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.30.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.30.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must still be the P29 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.30.0-p30: the setting that listens, the honest price line, the delete button — terse replies work MID-conversation now (the field's exact diagnosis: opencode reads AGENTS.md once per session, so P29's managed block did nothing until the next session AND leaked a chat style into git diffs; the toggle is now a pure app preference and the preference reaches the model as a one-line <system-reminder> riding the NEXT message in THAT session — TerseMode.wrap/needsInject pure, RunHub.styleTold a 64-entry per-session LRU, marked only on a successful POST so a failed send never loses the announcement, re-told automatically after /compact, UI says 'applies from your next message', first boot strips P29's old block from every known AGENTS.md byte-preserving user content and logs it to the incident log); the cost hint stopped clipping (gravity=end + maxLines=1 with no ellipsize HARD-clipped the line at the far edge — left-aligned with the input well, format shortened to '≈ 2k new · next \$0.0500 · ctx 48k', the ≥50% nudge names the real button '/compact saves', a 56-char worst-case width bound pinned by test, ellipsize=end as the never-say-never net, the protected Σ pill untouched); projects DELETE for real (long-press → Delete project…: confirm dialog with the exact path and the irreversibility, then ProjectDelete — a pure guarded recursive delete that refuses roots/mount points/sdcard/the app dir and every ANCESTOR of it, canonicalizes symlinks before checking, counts the tree BEFORE touching a file and aborts past 20k, unlinks symlinks without following them, stops the server first when the target is the project being served, runs off-thread, and 'Remove card' keeps its old unpin-only semantics); deck long-press gains a haptic tick; 217 JVM tests green (18 new)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.30.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release30.json.in
import json, sys
with open('/home/z/my-project/gh-release-body-p30.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.30.0",
    "target_commitish": "main",
    "name": "v0.30.0-p30 — the setting that listens, the honest price line, the delete button",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel30.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release30.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('/home/z/my-project/scripts/gh_rel30.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; cat /home/z/my-project/scripts/gh_rel30.json | head -5; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 600 -o /tmp/up30.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p30-v0.30.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p30-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p30-v0.30.0-debug.apk opencode-p30-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.30.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p30-v0.30.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 600 -o /tmp/rt-p30.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.30.0/opencode-p30-v0.30.0-debug.apk"
REMOTE_SHA=$(sha256sum /tmp/rt-p30.apk | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P30 SHIP COMPLETE"
