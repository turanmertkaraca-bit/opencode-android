#!/bin/bash
# p24_gh_ship.sh — commit the P24 tree, push, create Release v0.24.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash).
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
STAGE=/home/z/my-project/gh-repo
DL=/home/z/my-project/download

cd "$STAGE"
git config user.name  "$OWNER"
git config user.email "259321061+$OWNER@users.noreply.github.com"
git remote set-url origin "https://x-access-token:${T}@github.com/${OWNER}/${REPO}.git"

# --- pre-flight gates ---
if grep -rIln --exclude-dir=.git -E "ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}" "$STAGE" 2>/dev/null; then echo "SECRET FOUND — ABORT"; exit 1; fi
[ -e "$STAGE/app/src/main/assets/oc_pkg.bin" ] && { echo "oc_pkg staged — ABORT"; exit 1; }
BIG=$(find "$STAGE" -path "$STAGE/app/build" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 26" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.24.0-p24: the flush surgeon — the field proved P23's containment stopped the crash but not the freeze ('doesn't crash but it still won't work'): the whole paint batch ran under ONE guard, so a single row that could not paint aborted every row behind it, and the feed re-dirtied that row on every streaming delta/file event, so every 80 ms coalesced flush re-failed — banner wall + frozen transcript while the run stayed healthy; each row now fails ALONE (per-row Resilience.guard in the flush), repeat offenders quarantine after 2 strikes (content swapped for a bounded can't-fail fallback line, key skipped forever, credit restored on success and per session), renderAllInner runs at Throwable breadth (an Error row becomes the fallback line instead of leaving the list half-built after removeAllViews), repeating containment notes carry their identity in-chat (Resilience.traceLine: class: message · top app frame — one screenshot is a diagnosis), tool upsert null-hardened (Objects.equals for status/title); 103 JVM tests green (10 new: flush shape with poison in the middle, quarantine strikes + skip-forever + sibling survival, traceLine bounds/frame/newlines, guard-holds-Errors pin, and 3 Robolectric tests that reproduce the field state inside the real ChatActivity via the paintRowOnce seam)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.24.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release24.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p24.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.24.0",
    "target_commitish": "main",
    "name": "v0.24.0-p24 — the flush surgeon: one bad part can no longer freeze the chat",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel24.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release24.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel24.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel24.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /tmp/up24.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up24.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p24-v0.24.0-debug.apk "$DL/opencode-p24-v0.24.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p24-kit.tar.gz       "$DL/opencode-p24-kit.tar.gz"       "application/gzip"
upload opencode-linux-arm64-android.tar.gz "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed, public HEAD 302, APK round-trip hash ---
curl -sS -m 30 -H "Authorization: token $T" \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('release:', d.get('name'), '| tag:', d.get('tag_name'), '| draft:', d.get('draft'))
for a in d.get('assets',[]):
    print('  asset:', a['name'], a['size'], 'bytes', str(a.get('digest',''))[:19])
"
for f in opencode-p24-v0.24.0-debug.apk opencode-p24-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.24.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /tmp/rt-p24.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.24.0/opencode-p24-v0.24.0-debug.apk"
sha256sum /tmp/rt-p24.apk "$DL/opencode-p24-v0.24.0-debug.apk"
echo SHIP-OK
