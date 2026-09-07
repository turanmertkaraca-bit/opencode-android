#!/bin/bash
# p32_gh_ship.sh — commit the P32 tree, push, create Release v0.32.0 with
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
grep -q "versionCode 34" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.32.0-p32" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.32.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.32.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must still be the P31 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.32.0-p32: the final polish — the theme crash, fixed; every screen follows the palette (the field crash was a DEAD CAST: pickTheme inflated android.R.layout.simple_list_item_1 — a TextView — and cast it to LinearLayout, ClassCastException on every tap of the Theme row before the sheet ever opened, and the line was unused so no logic test could see it; the dead cast is gone, the whole picker is contained (failure = one honest toast + incident-log line, never the app), and P32UiTest now performs the exact tap under Robolectric and asserts the sheet opens — pinned at the UI layer where the bug actually lived); whole-app palette sync (a theme switch used to re-skin Settings and nothing else — every other open screen kept the old palette until the process died; Theme.isStale pure core + syncIfNeeded at the top of every activity's onResume: detect → apply → recreate, loop-proof because the applied id equals the pref right after, contained because a recreate nit can never take a screen down); no more frozen colors (the P10-era XML drawables froze colors at build time and retint could only remap OLED-table SOLIDS: code/tool wells #0D1017, error cards #2A151C, Deny/Always-allow/Allow permission pills, system pills #141824, thinking cards, suggestion chips, user-bubble rim — all rebuilt as palette-owned Theme builders (codeWell/errCard/toolCard/sysPill/denyPill/outlinePill/allowPill/suggestPill/thoughtCard/userBubbleTail) so Paper stops wearing near-black blocks; card ink (PROJECT tag, mono path, dividers, hero brand, restart button, cardBg rim, ghost card) now derives from ON_CARD via Theme.onCard — BYTE-IDENTICAL to the old hexes on the default oled palette (pinned by test, zero visual change where the user actually is) and real ink on Paper; the sandbox veil follows SURFACE via Theme.surfaceScrim instead of a hardcoded midnight wash; the empty-state hero disc lost the last P27-killed violet gradient for a flat accent disc; retint re-skins the 1dp hairline strokes of the static-XML chips/cards/composer — the one thing retint could never reach — guarded so only build-time-frozen OLED solids can fire it); the picker improved (each theme row shows three live swatches bg·surface2·accent straight from PALETTE_DATA, haptic on select — see what you choose before you commit); 275 JVM tests green (17 new: legacy-hex reproduction, alpha/RGB preservation per palette, paper ink darkness, veil light/dark polarity, isStale null/unknown-id safety, swatch non-degeneracy, plus the Robolectric crash pin + pick-applies + sync-idempotence + garbage-pref no-loop)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.32.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release32.json.in
import json, sys
with open('/home/z/my-project/gh-release-body-p32.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.32.0",
    "target_commitish": "main",
    "name": "v0.32.0-p32 — the final polish: the theme crash, fixed; every screen follows the palette",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel32.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release32.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('/home/z/my-project/scripts/gh_rel32.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; cat /home/z/my-project/scripts/gh_rel32.json | head -5; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 600 -o /tmp/up32.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p32-v0.32.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p32-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p32-v0.32.0-debug.apk opencode-p32-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.32.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p32-v0.32.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 600 -o /tmp/rt-p32.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.32.0/opencode-p32-v0.32.0-debug.apk"
REMOTE_SHA=$(sha256sum /tmp/rt-p32.apk | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P32 SHIP COMPLETE"
