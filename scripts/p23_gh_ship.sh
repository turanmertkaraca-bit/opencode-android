#!/bin/bash
# p23_gh_ship.sh — commit the P23 tree, push, create Release v0.23.0 with
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
grep -q "versionCode 25" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.23.0-p23: blast-radius zero — the on-send field crash was a real unhandled Java exception (exit reason CRASH, 1 kB crash file) that P21/P22 never actually fixed (P21 hardened resume-replay memory, P22 the double-tap window — neither was this); every audited stage had catch(Exception), and the hole was Errors + thread boundaries: send worker, model validation, session create, SSE feed, server drain, supervisor, every posted UI runnable (row adds, paints, stream ticker, watchdog, live card, busy flip), image decoders and the permission card now all run at Throwable breadth — a contained failure lands in a persistent guard trail, shows one honest chat line, and the run degrades instead of the app dying; Diagnostics gains the FULL last-crash.txt trace with Copy/Clear (the boot screen had it, warm launches skip it — why field reports could only paste 'crash file (1 KB)') plus the contained-errors view, and a crash now stamps its identity (class, message, thread, top frame) into sandbox-diag.log; send path no longer blocks on the models.dev HTTPS fetch (cold-process seconds + the biggest allocation burst per first send — background refresh instead, run-time self-heal still covers stale picks); 93 JVM tests green (12 new: guard contains OOM/linkage/StackOverflow, guardLine bounds + app-frame extraction, trail rotation, send-worker latch-release shape)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.23.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release23.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p23.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.23.0",
    "target_commitish": "main",
    "name": "v0.23.0-p23 — blast-radius zero: the app can no longer die from its own exceptions",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel23.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release23.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel23.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel23.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /tmp/up23.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up23.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p23-v0.23.0-debug.apk "$DL/opencode-p23-v0.23.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p23-kit.tar.gz       "$DL/opencode-p23-kit.tar.gz"       "application/gzip"
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
for f in opencode-p23-v0.23.0-debug.apk opencode-p23-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.23.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /tmp/rt-p23.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.23.0/opencode-p23-v0.23.0-debug.apk"
sha256sum /tmp/rt-p23.apk "$DL/opencode-p23-v0.23.0-debug.apk"
echo SHIP-OK
