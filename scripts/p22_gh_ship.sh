#!/bin/bash
# p22_gh_ship.sh — commit the P22 tree, push, create Release v0.22.0 with
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
grep -q "versionCode 24" "$STAGE/app/build.gradle" || { echo "versionCode guard failed"; exit 1; }

git add -A
if git diff --cached --quiet; then
  echo "no source changes (unexpected)"; exit 1
fi
git commit -q -m "v0.22.0-p22: the native-layer audit — the bundled binaries were EXECUTED for the first time instead of trusted: BusyBox v1.36.1 ran under qemu-aarch64 (305 applets listed, every shim-critical command pattern green, the real applet list now a test fixture); the REAL debian:bookworm arm64 docker layer (digest db86109d, the exact 48MB blob the app downloads) was extracted with the app's own extractor and two hardlinks landed DANGLING (usr/bin/perl5.36.0, usr/bin/uncompress — hardlink targets are archive-root-relative, the handlers resolved relative targets against the link's dir) — fixed, re-extraction byte-exact vs ground truth (5237 files, 0 dangling, 0 escapes); latent pax parser bug fixed (path= substring-matched inside linkpath=, legal record order in docker layers would extract files under the link target's name — now record-exact); dead patch applet removed from the fallback list (this busybox has no patch; flag bumped so old installs drop the dead symlink, user imports untouched); proot toolkit ELF wiring verified (DT_NEEDED libtalloc.so.2 + libandroid-shmem.so + bionic libc/liblog all resolve from the app lib dir, SONAMEs match install layout); sandbox proxy capped at 64 concurrent connections (was unbounded thread-per-conn — an LMKD invitation); send double-tap latch (session setup + model validation run network I/O before busy set — double-tap queued two identical runs); Debian launcher now a REAL write-if-different (was rewrite + chmod subprocess per spawn); 81 JVM tests green (8 new: hardlink normalization, pax both orders, exact-key, GNU longlink, CORE_APPLETS vs the real 305-applet fixture)"
git push origin main 2>&1 | sed "s/x-access-token:[^@]*@/x-access-token:***@/g"
sleep 2
curl -sS -m 30 -H "Authorization: token $T" "https://api.github.com/repos/${OWNER}/${REPO}/commits/main" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('head:', d.get('sha','?')[:8], (d.get('commit') or {}).get('message','?').splitlines()[0][:120])"

# --- release v0.22.0 ---
python3 - "$T" <<'PY' > /tmp/gh_release22.json.in
import json, sys
token = sys.argv[1]
with open('/home/z/my-project/gh-release-body-p22.md') as f:
    body = f.read()
payload = {
    "tag_name": "v0.22.0",
    "target_commitish": "main",
    "name": "v0.22.0-p22 — the native-layer audit: the binaries were executed, the bugs they caught are fixed",
    "body": body,
    "draft": False,
    "prerelease": False,
}
print(json.dumps(payload))
PY

CODE=$(curl -sS -m 60 -o /tmp/gh_rel22.json -w '%{http_code}' \
  -H "Authorization: token $T" -H "Accept: application/vnd.github+json" \
  -d @/tmp/gh_release22.json.in \
  "https://api.github.com/repos/${OWNER}/${REPO}/releases")
echo "release create HTTP $CODE"
REL_ID=$(python3 -c "import json; d=json.load(open('/tmp/gh_rel22.json')); print(d.get('id',''))")
if [ -z "$REL_ID" ]; then cat /tmp/gh_rel22.json; exit 1; fi
echo "release id: $REL_ID"

upload() { # name, path, ctype
  local NAME="$1" PATH_="$2" CTYPE="$3"
  local CODE=$(curl -sS -m 600 -o /tmp/up22.json -w '%{http_code}' \
    -H "Authorization: token $T" -H "Content-Type: $CTYPE" \
    --data-binary @"$PATH_" \
    "https://uploads.github.com/repos/${OWNER}/${REPO}/releases/${REL_ID}/assets?name=${NAME}")
  local SIZE=$(python3 -c "import json; d=json.load(open('/tmp/up22.json')); print(d.get('size', d.get('message','?')))" 2>/dev/null || echo '?')
  echo "asset $NAME → HTTP $CODE ($SIZE bytes)"
}

upload opencode-p22-v0.22.0-debug.apk "$DL/opencode-p22-v0.22.0-debug.apk" "application/vnd.android.package-archive"
upload opencode-p22-kit.tar.gz       "$DL/opencode-p22-kit.tar.gz"       "application/gzip"
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
for f in opencode-p22-v0.22.0-debug.apk opencode-p22-kit.tar.gz opencode-linux-arm64-android.tar.gz; do
  H=$(curl -sS -o /dev/null -w '%{http_code}' -L --max-redirs 0 \
      "https://github.com/${OWNER}/${REPO}/releases/download/v0.22.0/$f" || true)
  echo "public HEAD $f → $H (302 expected)"
done
echo "round-trip check (downloaded APK sha256 vs local):"
curl -sSL -m 600 -o /tmp/rt-p22.apk "https://github.com/${OWNER}/${REPO}/releases/download/v0.22.0/opencode-p22-v0.22.0-debug.apk"
sha256sum /tmp/rt-p22.apk "$DL/opencode-p22-v0.22.0-debug.apk"
echo SHIP-OK
