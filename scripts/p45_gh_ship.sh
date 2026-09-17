#!/bin/bash
# p45_gh_ship.sh — commit the P45 tree, push, create Release v0.45.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash). Pattern proven by p44_gh_ship.sh.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token.
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo
DL=/home/z/my-project/download
WORK=/home/z/my-project/p45-ship
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
grep -q "versionCode 47" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.45.0-p45" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.45.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.45.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P45 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.45.0-p45: the act-normal release — the P44 field verdict executed in full; ONE, auto-compaction is OFF at the source: the app must never silently summarize a chat's memory (the server's own escape hatch, compaction.auto=false in opencode.json — found in the bundled binary's embedded config schema, its overflow check short-circuits to do-not-compact and an overflow turn errors instead of summarizing) — pinned at every server boot beside the P41 floor with hands-off ownership at boot and forced write-through on the explicit toggle (CompactionPolicy.mergeAuto + AuthStore.ensureCompactionAuto/setCompactionAuto/compactionAuto, pref compact_auto default OFF, ownership compaction_auto_app), Settings → Essentials gains the Auto-compact context card + switch (write-through + restart-to-apply toast + honest two-mode dialog), the Σ popover's hot-context note is mode-aware (riskNote(depth, limit, auto): summarizing-close when on, honest overflow-error warning when off), and RunHub.err appends the one honest line to overflow errors — start a fresh chat or clear the window cap; TWO, Graphite is the default face again: the warm-paper claude palette is REMOVED outright (PALETTES/PALETTE_DATA/GRAD_DATA rows, paletteName, lightPalette, LauncherIcon alias, manifest alias + the four icon resources die), DEFAULT_PALETTE=graphite, and the one-time theme_migrated_p45 migration carries devices the p44 push had dragged onto claude back to Graphite while every other explicit choice stands (a removed id reads as unknown everywhere — index floor + name fallback, pinned); THREE, the chat wears the Claude-Android LAYOUT on the graphite tokens: user messages sit in fully rounded neutral 22dp pills (no tail — Theme.userBubble rebuilt, userBubbleTail deleted), assistant responses are flat full-width text under a small accent ✦ marker (K_ASSISTANT child 0; bodyByKey maps untouched), the empty chat greets by time of day (heroTitle/heroSub ids + syncEmpty recompute — Good evening / How can I help you today?), and the composer is ONE unified pill (composerCard inside composerBar, backgrounded at runtime from the live palette via Theme.composerWell radius 26: borderless input inside, vision + palette on the left, Build/Plan + model chips and a flat 40dp send circle on the right; bg_composer/bg_input drawables deleted, DeX wide-layout padding kept on the outer bar); FOUR, cold boot lands on the project deck ALWAYS: MainActivity.routeByLastScreen no longer reopens the last chat — the deck is the only cold-open destination, opening a project is the user's explicit act like the opencode TUI, and the last-project sandbox pre-warm stays (pure infrastructure, no session, no run, no screen); README install section refreshed to P45 with the new release section; test pins updated for the new reality (P31Test claude-removed + six palettes, P32Test, P33Test graphite-again, P33UiTest picker pins, P36Test six aliases, ChatLayoutTest composer-pill structure, P24ChatTest/P37Test assistant-row children) and P45Test pins the whole release surface (18 new: the merge-auto table, two-mode risk notes, the p45 migration, the AuthStore round-trip); 475 JVM tests green (18 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.45.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p45.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.45.0",
    "target_commitish": "main",
    "name": "v0.45.0-p45 — the act-normal release: auto-compact off at the source, Graphite again, the Claude chat layout",
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

upload "$DL/opencode-p45-v0.45.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p45-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p45-v0.45.0-debug.apk opencode-p45-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.45.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p45-v0.45.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p45.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.45.0/opencode-p45-v0.45.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p45.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P45 SHIP COMPLETE"
