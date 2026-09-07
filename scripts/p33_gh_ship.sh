#!/bin/bash
# p33_gh_ship.sh — commit the P33 tree, push, create Release v0.33.0 with
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
if grep -rIln --exclude-dir=.git "$(cat /home/z/my-project/.gh_token)" "$PROJ" 2>/dev/null; then echo "TOKEN LEAK IN TREE — ABORT"; exit 1; fi
# oc_pkg.bin is build-only (gitignored) — keep the tree clean anyway
if [ -e "$PROJ/app/src/main/assets/oc_pkg.bin" ]; then
  echo "oc_pkg present — removing (gitignored, build-only)"
  rm -f "$PROJ/app/src/main/assets/oc_pkg.bin"
fi
BIG=$(find "$PROJ" -path "$PROJ/app/build" -prune -o -path "$PROJ/.gradle" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 35" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.33.0-p33" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.33.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.33.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must still be the P32 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.33.0-p33: the final version — themes land instantly (the picker tap used to call recreate(): whole-activity teardown + window animation, the field's 'takes a while and feels bad'; the theme now lands the same frame — save → apply → dismiss → rebuild the one view tree in place, pinned by decor-identity test so the window provably survives), Graphite is the default face (the user picked it; fresh/pref-less devices come up in Graphite, an explicit choice always wins, the picker's '· default' label moved with the crown, and the legacy amoled carve-out is honored via defaultId(!amoled)), the whole-app palette sync actually works now (P32's syncIfNeeded compared the pref against the PROCESS-GLOBAL static which Settings' own apply() had already updated — no other screen could ever be stale and the sync never fired in the field; the palette id now rides a per-screen decor stamp, Theme.stampApplied/appliedIdOf, restamped before recreate so a refused recreate cannot loop, pinned: fresh-stamp equality, stale-on-pref-move, one-shot restamp), keys reach the sandbox by themselves (the field's 'app thinks i have no api key even tho it says i have it in api settings' — a CHANGED key now restarts the sandbox: the old code only restarted for a FIRST-TIME key so an updated key left the running server serving the old value while API settings showed the new one saved; the model sheet re-reads auth.json at open so a saved key can never be called missing by a stale fetch; hasAnyKey also counts custom providers whose key lives inline in opencode.json via configHasEmbeddedKey; imported auth.json and custom endpoints apply the same way), the picker contrast is honest again ('p32 made everything low contrast white': when the server didn't answer, the fetch marked EVERY model catalog-only and the whole sheet went dim; the pure carryLive rule keeps last-known live truth through a server blip — never invents, never clears, provider usable follows, arg-order guarded), the project long-press grew up (the actions menu was the last framework list box — 'the same old android 4 style box'; it is the app's own sheet now: name + mono path header, glyph rows ▸ Open · ✎ Rename · ⌦ Remove card · ✕ Delete project… in the danger color, ripple + haptics, palette-owned end to end via Theme.skin; the delete confirm shows the exact path in a code well with Keep it / Delete forever pills and rename matches — all P30 shields stay underneath), and the smoothness sweep (the GitHub-token save recreate is gone — in-place row rebuild; nothing new was added: polish only, as asked); 291 JVM tests green (16 new: carryLive rules, embedded-key detection, the graphite default, per-screen stamp + one-shot reskin, the in-place theme change pinned by decor identity, and the model-sheet auth re-read)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.33.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release33.json.in
import json, sys
with open('/home/z/my-project/gh-release-body-p33.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.33.0",
    "target_commitish": "main",
    "name": "v0.33.0-p33 — the final version: themes land instantly, Graphite is the face, keys reach the sandbox, the picker tells the truth",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel33.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release33.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('/home/z/my-project/scripts/gh_rel33.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; cat /home/z/my-project/scripts/gh_rel33.json | head -5; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 600 -o /tmp/up33.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p33-v0.33.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p33-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p33-v0.33.0-debug.apk opencode-p33-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.33.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p33-v0.33.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 600 -o /tmp/rt-p33.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.33.0/opencode-p33-v0.33.0-debug.apk"
REMOTE_SHA=$(sha256sum /tmp/rt-p33.apk | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P33 SHIP COMPLETE"
