#!/bin/bash
# p42_gh_ship.sh — commit the P42 tree, push, create Release v0.42.0 with
# APK + kit + binary tarball, verify (assets listed, public HEAD 302,
# APK round-trip hash). Pattern proven by p41_gh_ship.sh.
# REQUIRES: a VALID token in /home/z/my-project/.gh_token (the previous
# one was auto-revoked — verified HTTP 401 before this run).
set -e
T=$(tr -d '[:space:]' < /home/z/my-project/.gh_token)
OWNER=turanmertkaraca-bit
REPO=opencode-android
PROJ=/home/z/my-project/gh-repo-fresh
DL=/home/z/my-project/download
WORK=/home/z/my-project/p42-ship
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
grep -q "versionCode 44" "$PROJ/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.42.0-p42" "$PROJ/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# never reuse a release number
REL=$(curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/releases/tags/v0.42.0" | python3 -c "import json,sys; d=json.load(sys.stdin); print('EXISTS' if isinstance(d,dict) and d.get('id') else 'FREE')")
[ "$REL" = "FREE" ] || { echo "v0.42.0 already exists — ABORT"; exit 1; }
# ground truth: remote HEAD must be either this exact commit (nothing
# staged yet) or its parent (the P42 commit already made locally) —
# anything else means somebody moved main while this tree worked
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
PARENT_SHA=$(git rev-parse HEAD~1 2>/dev/null || echo "")
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || [ "$REMOTE_SHA" = "$PARENT_SHA" ] || \
  { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if ! git diff --cached --quiet; then
git commit -q -m "v0.42.0-p42: the honesty release — the field evidence (hours of session time burned by the agent 'fixing its environment', https dead inside its shell, deleted watcher rows reading as 20000-plus days, and meters that could not be fully trusted after restarts) traced to four sources and fixed at each: ONE, the environment — no CA bundle was ever exported anywhere in the app (Android's system trust store sits at a path Linux-compiled tools never look at, the shipped alpine rootfs bundle was never pointed at, the debian one only existed after a bootstrap that itself needed the network), so every curl/git-https died and the env note the agent reads at session start actively lied to it (a Debian guest it was not in, a GH_TOKEN that was not exported there, and a literal recommendation to run apt-get install ca-certificates — the exact loop the money went into); the app now builds one merged PEM bundle from the real system store plus the shipped Mozilla set at every spawn and exports the standard variables every tool family honors (SSL_CERT_FILE, SSL_CERT_DIR, CURL_CA_BUNDLE, REQUESTS_CA_BUNDLE, GIT_SSL_CAINFO, NODE_EXTRA_CA_CERTS, NPM_CONFIG_CAFILE, PIP_CERT), preseeds the same bundle into the debian rootfs before the first apt call (write-if-absent — apt's own newer bundle wins later), exports it in the alpine wrapper prolog and the git shim, and rewrites the env note mode-aware and honest (native/alpine/debian as the shell actually lands, certificates provisioned and cert-fixing named as a non-step, raw DNS impossible by design with the one real remedy named, and the money saver in plain words: if a probe fails twice, report it and move on — re-probing this intentionally minimal sandbox wastes the user's money); a project-root fallback now announces itself through the same note instead of letting the agent discover it by ENOENT (ServerService.servingFallback); TWO, the meter and the money — replays booked only the last 80 stored messages so a reopened long session painted the cost of its last 40 turns (accounting now walks the FULL store, the 80-window stays a rendering cap, MsgInfo cap raised 400-2000 so eviction cannot re-book an evicted message from zero), all-time spend booked live events only so money billed while the app was dead never reached the credit counter (a persisted per-message cost ledger in told-state.json books every message exactly once, live or replayed, unseen sessions seed silently so history that predates the counter is never re-booked, the Safety reset clears the ledger with the counter), a compaction summary message re-billed the whole payload and its token count stayed as the run high-water (the depth inputs now reset so the next real turn reports the honest post-compaction window), a model switch left the pill on the old denominator until the next hub event (refresh now, plus the pinned compaction floor MOVES with the model via CompactionPolicy.mergeOrMovePreserve — app-owned values tracked by AuthStore's ownership pref move, user-edited values never do), four surfaces mixed rounding with flooring so the warning could lag the number shown (Resilience.pctFloor is the one rule, contextMeter and CostMath.windowPct both floor now), and the token total prefers the server's own tokens.total when present (fixture-pinned equal today, drift-proof tomorrow); THREE, the context-limit slider — sigma pill, a Window cap sheet: a real SeekBar plus presets that writes the picked model's limit.context in opencode.json (the exact models.dev shape the bundled server parses natively, verified in the binary; ContextPolicy bounds 64k-2M, quantizes to 1k, clears and tidies, and never touches user junk nodes), apply offers a sandbox restart so the meter denominator and the compaction trigger follow at once, the cap is reachable on a fresh chat and the popover states it plainly when in force; FOUR, the felt stuff — the keyboard no longer opens by itself (the list takes the initial focus via focusable-in-touch-mode, input focus drops on pause, and only tapping the input opens the IME, while the mid-stream re-grab guards keep working for typing that is already happening), the back button is a fixed 40dp with a minimum touch target and the sigma pill caps its own width with middle ellipsis so a long meter line cannot crush the title (the squish report), the startup veil trades its static cold-start line for a live elapsed counter, fades out over 150 ms instead of popping, and no longer re-runs its entry animation on restart flaps, and the 20000-days class of bug is structurally impossible: bindFileRow passed an AGE in seconds where relTime expected an EPOCH in seconds (diff became the event's own epoch, ~20090 days — deleted rows were simply the ones old enough to show it), the sessions sheet fed epoch milliseconds to the same helper (everything 'just now' forever), and FilesActivity painted lastModified()==0 the same way — one unit-explicit JVM-tested Resilience.ago(epochMs, nowMs) now renders all of it, '—' for unknown time; P42Test pins the time contract (units, scale, unknown-time dash, clock-skew), the shared percent rule and the meter's floor, the window-cap bounds/quantize/merge/clear/junk-handoff table, and the floor's move-only-what-you-own rule; 423 JVM tests green (11 new)"
fi
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.42.0 (payload via python file — no heredoc stdin traps) ---
python3 - "$T" > "$WORK/release.json.in" <<'PY'
import json, sys
with open('/home/z/my-project/gh-release-body-p42.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.42.0",
    "target_commitish": "main",
    "name": "v0.42.0-p42 — the honesty release: real TLS trust in the agent shell, meter and money that survive restarts, a context-limit slider",
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

upload "$DL/opencode-p42-v0.42.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p42-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p42-v0.42.0-debug.apk opencode-p42-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.42.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p42-v0.42.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 900 -o "$WORK/rt-p42.apk" \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.42.0/opencode-p42-v0.42.0-debug.apk"
REMOTE_SHA=$(sha256sum "$WORK/rt-p42.apk" | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P42 SHIP COMPLETE"
