#!/bin/bash
# p31_gh_ship.sh — commit the P31 tree, push, create Release v0.31.0 with
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
grep -q "versionCode 33" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.31.0-p31" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.31.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.31.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must still be the P30 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.31.0-p31: parallel chats + the favorites shelf + a credit limit + the interactive canvas + six themes + a sandbox that sleeps — the long-press fix first (a project card is a clickable child so it consumed the whole touch stream and the deck's GestureDetector never saw a stationary hold; the P30 haptic sat on a callback that could not fire for touches that START on a card, and on release the card's own click opened the chat — the long-press now lives ON THE CARD as a native long-click that consumes the gesture and suppresses the release-click, deck callback kept as the gap fallback, pinned by a Robolectric test that performs the long click and asserts the actions menu opens); runs are tracked PER SESSION (RunHub.runs map + RunBook pure claim/re-arm rules: a script streams in one chat while another keeps working, cap 3, ■ answers only the displayed chat, Sessions gains RUNNING NOW badges + long-press Stop, subtitle announces background runs, the quiet-end watchdog + P19 part re-arm + archive eviction all per-run aware — a clean idle can never be resurrected by a stale part and a background mid-run Tx can never be evicted, flake retry targets the erroring session's own last text); model FAVORITES (long-press a model → ★ shelf at the top of the picker, ordered JSON in prefs, cap 8, rotation-proof: a favorite missing from the catalog hides without deletion so the selection self-heal never punishes pins); CREDIT LIMIT (CreditLimit pure parse/verdict/block lines, an all-time delta-accumulated counter that only books LIVE assistant messages so replays never double-count, persisted, enforced in send/sendImage/sendWithAttachments/compact, ⚠ subtitle nudge at ≥80%, cap state in the Σ popover, manual counter reset); INTERACTIVE CANVAS (CanvasDoc pure prompt/guards + CanvasActivity: ⌘ command asks the agent for a self-contained page to canvas.html, write/edit tool cards and Files long-press grow a ▶ interactive chip, sandboxed WebView — JS+DOM storage ON, file/content access OFF, string-loaded so no origin, external navigation refused, 3 MB guard, nothing auto-opens); RESET SANDBOX ENVIRONMENT (EnvironmentReset pure plan: wipes debian/alpine/wrappers/shims/bin/caches inside canonicalized app dirs ONLY, keeps auth.json+opencode.json+gh_token+projects+chats+settings, honest both-sides dialog, optional immediate Debian reinstall); AUTO-HIBERNATE (Hibernate pure rule + App.bgSince + the supervisor watcher: backgrounded + no run in ANY chat + no pending permission + past the quiet threshold → the sandbox stops itself and reopening routes straight back into the stamped chat from disk, Resume.parseLastScreen pure, 5/10/15/30 min, never while work is in flight); SIX THEMES (Theme.PALETTE_DATA table: OLED black pinned default + Midnight + Graphite + Ember + Forest + Paper light, per-palette sheet styles applied via theme.applyStyle, runtime remap of the build-time-frozen XML colors, palette-owned card gradients + user-bubble rim + icon discs + ripple + status-bar icons); Share chat as Markdown (EXTRA_TEXT, no file URIs) + Find in chat; 258 JVM tests green (41 new)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.31.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release31.json.in
import json, sys
with open('/home/z/my-project/gh-release-body-p31.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.31.0",
    "target_commitish": "main",
    "name": "v0.31.0-p31 — parallel chats, model favorites, a credit limit, the interactive canvas",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel31.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release31.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('/home/z/my-project/scripts/gh_rel31.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; cat /home/z/my-project/scripts/gh_rel31.json | head -5; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 600 -o /tmp/up31.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p31-v0.31.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p31-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p31-v0.31.0-debug.apk opencode-p31-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.31.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p31-v0.31.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 600 -o /tmp/rt-p31.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.31.0/opencode-p31-v0.31.0-debug.apk"
REMOTE_SHA=$(sha256sum /tmp/rt-p31.apk | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P31 SHIP COMPLETE"
