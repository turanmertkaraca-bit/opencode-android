#!/bin/bash
# p29_gh_ship.sh — commit the P29 tree, push, create Release v0.29.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash).
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo-p29
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
grep -q "versionCode 31" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.29.0-p29" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.29.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.29.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must still be the P28 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.29.0-p29: the picker can't double-open, photos like a chat app, a price tag on every send — model sheet idiot-proofed at three layers (AtomicBoolean in-flight gate: a second tap during the catalog load pops the chip and queues NOTHING; instant open from the in-memory lastFetch so the sheet appears NOW with a background refresh updating the OPEN sheet in place via updateOpenModels; showModels refuses to build a second dialog while one is showing — the field's 'click again → popup twice' cannot survive its own race, and the chip pulses 'loading models…' so silence is never the feedback); the vision flow is a real attachment tray (ACTION_GET_CONTENT + EXTRA_ALLOW_MULTIPLE, ClipData walked item by item, each Uri downscaled off-thread with its REAL post-downscale dims decoded for pricing, 64dp thumb + ✕ badge in a tray above the composer, cap 6, dedupe by file, send ships ONE message with N file parts through buildMultiImageBodies — pure, JVM-pinned, falls back to buildImageBodies/buildBodies at 1/0 images — and the free-vision-model ladder describes EACH photo when the server refuses pixels); the cost hint prices the next send BEFORE commit (CostMath pure: ASCII ~4 chars/token + CJK ~1/char text estimate, pixels/750 clamped [450,4000] image estimate from actual decoded dims, worst-case (ctx+new)×input price since the model re-reads the whole window and caching can only shrink the bill, free models say so, ≥50% window appends 'compact to pay less' — a new quiet mono line above the input fed by a TextWatcher + hubSpend, the protected Σ pill format and \$ meter untouched); /compact shipped (POST /session/{id}/summarize — route grepped in the bundled binary's OpenAPI, body ladder mirrors send, in ⌘ palette + Σ popover, summary lands through the existing upsert pipeline, busy-guarded); terse replies (the web-researched i-have-adhd/caveman token saver, 40-65% output cut) toggled as a MANAGED BLOCK in the project's AGENTS.md — merge/strip pure and idempotent, user content byte-preserved, zero per-message overhead; feel pass — Theme.haptic ticks on send/chips/vision/sessions/Σ/suggestions/attach/permission buttons (independent of the motion toggle), and the input well gained the 1dp hairline every other raised element already had (the only naked element in the language = the 'something feels off' candidate); 199 JVM tests green (13 new)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.29.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release29.json.in
import json, sys
with open('/home/z/my-project/gh-release-body-p29.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.29.0",
    "target_commitish": "main",
    "name": "v0.29.0-p29 — no more double-open, a real photo tray, a price tag on every send",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel29.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release29.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('/home/z/my-project/scripts/gh_rel29.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; cat /home/z/my-project/scripts/gh_rel29.json | head -5; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 600 -o /tmp/up29.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p29-v0.29.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p29-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p29-v0.29.0-debug.apk opencode-p29-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.29.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p29-v0.29.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 600 -o /tmp/rt-p29.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.29.0/opencode-p29-v0.29.0-debug.apk"
REMOTE_SHA=$(sha256sum /tmp/rt-p29.apk | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P29 SHIP COMPLETE"
