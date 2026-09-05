#!/bin/bash
# p27_gh_ship.sh — commit the P27 tree, push, create Release v0.27.0 with
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
# oc_pkg.bin is build-only (gitignored) — drop it so nothing huge lingers
if [ -e "$STAGE/app/src/main/assets/oc_pkg.bin" ]; then
  echo "oc_pkg present — removing (gitignored, build-only)"
  rm -f "$STAGE/app/src/main/assets/oc_pkg.bin"
fi
BIG=$(find "$STAGE" -path "$STAGE/app/build" -prune -o -type f -size +50M -print)
[ -n "$BIG" ] && { echo "TOO BIG: $BIG — ABORT"; exit 1; }
grep -q "versionCode 29" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }
grep -q "0.27.0-p27" "$STAGE/app/build.gradle" || { echo "versionName guard failed"; exit 1; }
# ground truth: remote HEAD must still be the P26 commit (nobody moved it)
REMOTE_SHA=$(git ls-remote origin refs/heads/main | cut -f1)
LOCAL_SHA=$(git rev-parse HEAD)
[ "$REMOTE_SHA" = "$LOCAL_SHA" ] || { echo "remote moved: $REMOTE_SHA vs local $LOCAL_SHA — ABORT"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.27.0-p27: stable taps + resume-current + curated rootfs + AMOLED + tappable file mentions — the live card stopped fighting the finger (syncLiveFooter rebuilt the card on EVERY fs batch: removeAllViews + full buildLiveView, so taps died between touch-down and touch-up or landed on a different row that had replaced theirs; the card is now a persistent skeleton built once per run — header texts set in place, tree rows diffed BY KEY so a view's listener never changes identity while its file stays in the feed, peek slot rebuilt only on selection change, and a scheduled auto-collapse that lands mid-gesture retries 150ms later instead of restructuring under the finger); tap the head to PIN (RunHub.toggleLivePin: auto→pinned→auto, accent dot + '· pinned' summary), selection pins too and the peek stays open + live-updating; pin + selection live in the hub so they survive every repaint and rebind, and settle still clears them (the card vanishes when the run ends); the stale-on-return chat is dead at the root — rows mutated while no view was bound fired notifyRow into an EMPTY sink and the resume re-pull then saw identical parts and correctly stayed silent, so NOTHING told the screen to redraw (only activity recreation did — the drawer path — while the home-button path stayed stale): every fresh bindUi now fires one targeted hubReset, a full repaint of the model as it is (event-driven, zero polling, never re-POSTs, never restarts a healthy stream); the Σ pill flicker (47% then back to 8%) was a DENOMINATOR flip — the model's context window came from whichever source populated the last fetch (server entry vs models.dev catalog disagree for some models): the merge is now deterministic (catalog limit wins), the last-known window is stashed per model as a 64-entry LRU and stands in when a fetch knows nothing, and mid-run the meter holds the run high-water (uneven per-turn cache-read accounting made the raw number jump down and up inside one run); money check answered with numbers: cache.read tokens tracked per message + session total surfaced in the Σ popover ('caching is on and working'), $ meter untouched on the server's authoritative per-message cost; curated rootfs — DebianTrim (pure, JVM-pinned) trims /usr/share/doc+man+info+groff, legacy zoneinfo (UTC + 20 real zones stay), locale archives, and perl at install (~50+ MB, logged to trim-report.txt, Settings-gated 'Curated rootfs') while boot hygiene sweeps /root/.npm + apt lists + .deb cache + /root scratch FILES Java-side after the health gate (~108 MB/session, directories never touched — the seeded clone survives); cold-boot budget (spawn→healthy + process→healthy) written to the incident log on every first healthy; server binary ships once (gzipped tar, STORED 0%, never duplicated) — upstream size/RSS documented as structural; Diagnostics reports the live app + server RSS budget; AMOLED design system — Theme is the single source now (mutable palette applied at process start + on the new 'Pure black (AMOLED)' toggle, default ON): true #000000 base, hairline-raised surfaces (no shadows), ONE accent family ~#7C9CFF replacing the ten-hue tool rainbow + violet cards + indigo→violet bubble gradient + blue-gray chips, one themed sheet presentation app-wide (bg_sheet + 200ms slide-up/180ms down via alertDialogTheme — the flat gray system boxes are gone), every inline hex moved to tokens; clipping audit as a repo checklist (docs/clipping-audit.md) with the field repros fixed: deck cards measure NATURALLY first and the shared height grows to fit (the 'last opened · 2 min / Open →' footer no longer clips when the path wraps — and the cap uses the incoming spec height, not getHeight() which is 0 on first measure: caught by the new pin), the '↓ latest' pill moved INSIDE the transcript FrameLayout (it can never float over the composer text again), model-picker price gets its own column (it collided with the ellipsized provider path), picker hint line replaces the 3-line toast wall, Files icons are one quiet family, letterspaced labels carry end padding; tappable file mentions — Mentions (pure, JVM-pinned: fenced code inert, inline code spans linkable, bare relative + project-absolute candidates, URL/dir-stub rejection, existence gate resolves against the serving dir incl. sandbox-relative forms) rendered by Markdown through a MentionResolver (accent + subtle underline, selection outside spans untouched), tapping opens the EXISTING Files viewer via a deep-link extra (lands on the folder, preview up, back returns to the chat at the same scroll, deleted file → quiet toast), tool cards for edit/write/read/patch grow an '↗ open in Files' chip; long-session: 500-message synthetic soak (1000 parts, ~2.6KB bodies) pinned rows-inside-the-wall + memory-flat + pill-O(1); 166 JVM tests green (23 new)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.27.0 ---
python3 - "$T" <<'PY' > /home/z/my-project/scripts/gh_release27.json.in
import json, sys
with open('/home/z/my-project/gh-release-body-p27.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.27.0",
    "target_commitish": "main",
    "name": "v0.27.0-p27 — stable taps, resume-current, AMOLED, tappable files",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /home/z/my-project/scripts/gh_rel27.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/home/z/my-project/scripts/gh_release27.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
RELEASE_ID=$(python3 -c "import json; print(json.load(open('/home/z/my-project/scripts/gh_rel27.json')).get('id',''))")
[ -n "$RELEASE_ID" ] || { echo "no release id"; cat /home/z/my-project/scripts/gh_rel27.json | head -5; exit 1; }
echo "release id $RELEASE_ID"

upload() {
  local file="$1" ctype="$2"
  local up="https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${RELEASE_ID}/assets?name=$(basename "$file")"
  local c=$(curl -sS -m 600 -o /tmp/up27.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $ctype" \
    --data-binary @"$file" "$up")
  echo "upload $(basename "$file") HTTP $c"
}

upload "$DL/opencode-p27-v0.27.0-debug.apk" "application/vnd.android.package-archive"
upload "$DL/opencode-p27-kit.tar.gz"        "application/gzip"
upload "$DL/opencode-linux-arm64-android.tar.gz" "application/gzip"

# --- verify: assets listed + public HEAD 302 ---
curl -sS -m 30 "https://api.github.com/repos/${OWNER}/${REPO}/releases/latest" \
  | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('latest release:', d.get('tag_name'))
for a in d.get('assets',[]): print(' asset:', a['name'], a['size'], 'bytes')"
for a in opencode-p27-v0.27.0-debug.apk opencode-p27-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -I -L --max-redirs 1 \
    "https://github.com/${OWNER}/${REPO}/releases/download/v0.27.0/$a")
  echo "public $a → $code"
done

# --- round-trip: re-download the APK and compare hashes ---
LOCAL_SHA=$(sha256sum "$DL/opencode-p27-v0.27.0-debug.apk" | cut -d' ' -f1)
curl -sSL -m 600 -o /tmp/rt-p27.apk \
  "https://github.com/${OWNER}/${REPO}/releases/download/v0.27.0/opencode-p27-v0.27.0-debug.apk"
REMOTE_SHA=$(sha256sum /tmp/rt-p27.apk | cut -d' ' -f1)
echo "roundtrip local  $LOCAL_SHA"
echo "roundtrip remote $REMOTE_SHA"
[ "$LOCAL_SHA" = "$REMOTE_SHA" ] && echo "ROUND-TRIP OK" || { echo "ROUND-TRIP MISMATCH"; exit 1; }
echo "P27 SHIP COMPLETE"
