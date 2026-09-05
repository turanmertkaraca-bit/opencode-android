#!/bin/bash
# p28_gh_ship.sh — commit the P28 tree, push, create Release v0.28.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash).
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/fresh-p28
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
grep -q "versionCode 30" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.28.0-p28" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.28.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.28.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must still be the P27 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.28.0-p28: the P27 field report, fixed — dead taps, drifted dots, big-file peek — file mentions TAP now (a ClickableSpan only fires through a movement method and the selectable transcript rows have none, so P27's accent+underline rendered while every tap fell through and died: taps are routed by hit-testing the span array at the touch point — genuine tap opens the EXISTING Files viewer, a scroll drag across a link never does (tap = down→up within 2x touch slop), selection + long-press copy keep their native handling, and glyph-boundary rounding cannot strand a tap one char away (±1 offset probe + equal-x tie walk in spanNear, the pure finder pinned against deterministic predicates — Robolectric's emulated font metrics measured degenerate for x→offset: tapX=6 resolved to offset 0, tapX=15 to offset 29)); the thinking dots are back ABOVE the composer (buildTyping parented via scroll.getParent()+indexOfChild(permSlot): the P27 transcript FrameLayout made that -1, Math.max clamped to 0 and the dots became a floating overlay INSIDE the transcript — the slot is now DECLARED in activity_chat.xml between permSlot and composerBar, cannot drift, pinned by test); the peek is big-file-proof + shows the edit point (the 11-line cap existed — the WORK could stutter: every debounced fs batch re-read + re-split up to 2MB of the selected file: now a (len,mtime) memo per path skips unchanged files, a streamed append with no edit-tool locator reads ONLY the last 8KB via RandomAccessFile + prefix newline count (honest line numbers, headCut marker, no '+N more' — ends at EOF), full reads are hard-capped 2MB even if the file grows mid-read, and the '▸ ' focus line is painted accent wash + bold via pure EditPulse.focusRange — 'shows what the AI is currently editing' is literal now; identical text no longer re-setTexts — SpannableString.equals is identity-based so the guard is String.contentEquals, the suite caught the naive version re-layouting on every hot poll); faster cold boot: the boot thread read + SHA-256'd the ~175MB binary on EVERY launch before the server could spawn — Binaries.sha256Cached memoizes by (len,mtime), one stat instead of 175MB of I/O, restamps when the binary changes so Diagnostics stays honest; binary + sandbox unchanged from P27 (upstream structural, rootfs trim + boot hygiene already shipped); 183 JVM tests green (17 new)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.28.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release28.json.in
import json, sys
with open('/home/z/my-project/gh-release-body-p28.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.28.0",
    "target_commitish": "main",
    "name": "v0.28.0-p28 — taps that tap, dots above the composer, a big-file-proof peek",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel28.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release28.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('/home/z/my-project/scripts/gh_rel28.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; cat /home/z/my-project/scripts/gh_rel28.json | head -5; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 600 -o /tmp/up28.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p28-v0.28.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p28-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p28-v0.28.0-debug.apk opencode-p28-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.28.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p28-v0.28.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 600 -o /tmp/rt-p28.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.28.0/opencode-p28-v0.28.0-debug.apk"
REMOTE_SHA=$(sha256sum /tmp/rt-p28.apk | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P28 SHIP COMPLETE"
