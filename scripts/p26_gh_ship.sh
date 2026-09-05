#!/bin/bash
# p26_gh_ship.sh — commit the P26 tree, push, create Release v0.26.0 with
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
grep -q "versionCode 28" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.26.0-p26" "$STAGE/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# ground truth: remote HEAD must still be the P25 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.26.0-p26: the evergreen release — the live edit tree left the transcript (a row inserted at run start was buried above the fold within seconds as tool/text rows streamed in below it, so the field never saw the files) and is now a PINNED FOOTER above the composer: always visible while the agent works, immune to scroll fights, gone the moment the run settles (settleBusyUi clears selection + feed hides it; the flashing pulse dot and the hot expand/collapse strobe died with it — static dot, stable expanded tree); back navigation walks backward (FilesActivity.onBackPressed goes UP one directory per press until the project root, ChatActivity system back mirrors the ‹ button to the deck); the stale-on-return chat fixed at both roots — onResume now ALWAYS reconcileOnBind (upserts in place, never the loadSession swap that emptied the displayed transcript and raced the sandbox boot into a silent dead end), and a re-pull that fires before the server answers plants replayNeeded which the next ST_HEALTHY flip re-runs (event-driven, zero polling) — the chat is current on every return; discovery-catalog models are SELECTABLE (try-anyway: Models.save(forced=true), validateSelectedModel keeps forced picks via the pinned pure rule, the P25-fixed model-not-found self-heal covers a real refusal — one honest note, no dead end); the month/year audit capped every growth path — per-message bookkeeping is a 400-entry LRU whose session token/cost totals move by delta (O(1) pill reads forever, exact under eviction and correction), the pid-less part counter only counts what it disambiguates and carries a 1024 wall, edit-focus snippets cap at 200 (eldest evicted), paint-fault/quarantine maps cap at 512 — under the existing self-healing chain (SSE reconnect, supervisor, orphan sweep, heartbeat, crash capture, run-state recovery); 143 JVM tests green (9 new: sums-vs-eviction, delta corrections, counter gating, focus cap, replay retry flag, keepPick rule, forced prefs round-trip, 12k-part soak inside every wall)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.26.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release26.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p26.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.26.0",
    "target_commitish": "main",
    "name": "v0.26.0-p26 — the evergreen release: see the edits live, navigate back, resume current, run for a year",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel26.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release26.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/home/z/my-project/scripts/gh_rel26.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /home/z/my-project/scripts/gh_rel26.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /home/z/my-project/scripts/up26.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/home/z/my-project/scripts/up26.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p26-v0.26.0-debug.apk "$DL/opencode-p26-v0.26.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p26-kit.tar.gz       "$DL/opencode-p26-kit.tar.gz"       "application/gzip"
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
for f in opencode-p26-v0.26.0-debug.apk opencode-p26-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.26.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /home/z/my-project/scripts/rt-p26.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.26.0/opencode-p26-v0.26.0-debug.apk"
sha256sum /home/z/my-project/scripts/rt-p26.apk "$DL/opencode-p26-v0.26.0-debug.apk"
echo SHIP-OK
