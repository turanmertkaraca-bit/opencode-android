#!/bin/sh
# P4 packaging: replace-in-place in download/ (policy: never add files).
set -e
DL=/home/z/my-project/download
PRJ=/home/z/my-project/opencode-mobile-p1
STAGE=/home/z/my-project/p4-work/pkg
KIT=$STAGE/opencode-p4-kit

rm -rf "$STAGE"; mkdir -p "$KIT/apk-gradle/app"

# --- kit source (build trees excluded) ---
cp "$PRJ/settings.gradle" "$PRJ/build.gradle" "$PRJ/gradle.properties" "$KIT/apk-gradle/"
cp "$PRJ/app/build.gradle" "$KIT/apk-gradle/app/"
cp -r "$PRJ/app/src" "$KIT/apk-gradle/app/src"
cp "$PRJ/app/build/outputs/apk/debug/app-debug.apk" "$KIT/opencode-p4-v0.4.0-debug.apk"

# --- kit README ---
cat > "$KIT/README.md" <<'EOF'
opencode-p4-kit — v0.4.0-p4 (versionCode 6)
============================================

What P4 fixes
  1. Sandbox commands now WORK. proot builds its "glue rootfs" in
     PROOT_TMP_DIR (falls back to TMPDIR); /tmp is not writable on Android,
     so the old shim's `export TMPDIR=/tmp` killed every guest command with
     "can't create temporary directory: Permission denied". All proot
     invocations (bash shim, git shim, tool installer, new install-time
     self-test) now point PROOT_TMP_DIR at the app cache dir. Install now
     FAILS LOUDLY if a proot self-test (`echo oc-proot-ok` inside the
     rootfs) does not pass — see sandbox/proot.log.

  2. Permission prompts fixed end-to-end (verified against the v1.18.25
     binary itself):
       - event   = permission.asked, properties ARE the request
                   {id, sessionID, permission:"bash", patterns:[...], metadata}
       - reply   = POST /permission/{requestID}/reply  {"reply":"once|always|reject"}
                   (the old client sent {"response":...} → silent 400 stall;
                    legacy /session/{sid}/permissions/{pid} kept as fallback)
       - new     = GET /permission seed on every SSE (re)connect recovers
                   asks that fired while disconnected
       - new     = permission.replied events drop answered dialogs
     On-device test: ask the agent to run `uname -a` → dialog appears →
     Allow once → command runs inside Alpine.

  3. Tool/file activity is now VISIBLE in chat: every tool call renders as
     its own monospace card — "[bash] running · $ npm install",
     "[read] completed · src/App.tsx" — including in reloaded history, with
     error lines on failure. Plus a session.status "agent ● working…"
     typing indicator.

  4. UI refresh: deeper Pixel-dark palette, header divider, refined
     bubbles/input/send button.

Install
  adb install -r opencode-p4-v0.4.0-debug.apk   (or sideload; upgrades in
  place over v0.3.x, keeps the imported binary/credentials)

After upgrading
  - Re-run "Add tools" on the main screen ONCE (nodejs/npm/python3/git
    install via apk inside the sandbox; needs network).
  - Stop & start the server so the new shims take effect.

Build from source
  cd apk-gradle && gradle assembleDebug   (AGP 8.5.2, compileSdk 34,
  minSdk=targetSdk 28 DELIBERATE — the Termux exec trick; zero deps)
EOF

# --- tarball + APK into download/ (replace, not add) ---
tar czf "$DL/opencode-p4-kit.tar.gz" -C "$STAGE" opencode-p4-kit
cp "$PRJ/app/build/outputs/apk/debug/app-debug.apk" "$DL/opencode-p4-v0.4.0-debug.apk"
rm -f "$DL/opencode-p3-v0.3.1-debug.apk" "$DL/opencode-p3-kit.tar.gz"

# --- sums + index ---
cd "$DL"
sha256sum opencode-linux-arm64-android.tar.gz opencode-p4-v0.4.0-debug.apk \
          opencode-p4-kit.tar.gz archive-p0-p1.tar.gz > SHA256SUMS.txt

cat > README.md <<'EOF'
OpenCode Mobile — downloads
===========================

  opencode-linux-arm64-android.tar.gz   the opencode binary (extract → import
                                        the 175 MB 'opencode' ELF into the app)

  opencode-p4-v0.4.0-debug.apk          the app (P4: sandbox commands fixed
                                        via PROOT_TMP_DIR, permission dialog
                                        fixed for v1.18.x API, tool/file
                                        activity visible, UI refresh)

  opencode-p4-kit.tar.gz                app source kit (Gradle project + README)

  archive-p0-p1.tar.gz                  superseded P0/P1 kits + historical
                                        checksums (kept for the record only)

  SHA256SUMS.txt                        checksums for the files above
EOF

echo "--- download/ now: ---"; ls -la "$DL"
echo "--- sums: ---"; cat SHA256SUMS.txt
