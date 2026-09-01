# opencode-android

Native Android client for the [opencode](https://github.com/sst/opencode) CLI —
a Termux-style chat UI wired to an in-process opencode server, with an optional
agent-only Alpine Linux / proot sandbox. Zero external dependencies: every
class is framework-only Java, no Gradle deps, no WebView, no bridges.

Built and tested for a Samsung SM-G990E (Android 16 / API 36, arm64-v8a), but
it should run on any arm64 device with Android 9+ (minSdk 28).

## Install (no build needed)

Grab the APK from **Releases → v0.5.0** and sideload it:

1. Download `opencode-p5-v0.5.0-debug.apk` on the phone.
2. When prompted, allow "install unknown apps" for your browser/file manager.
3. Launch the app, then:
   - **Import binary** — pick the opencode arm64 Linux binary (or the tarball,
     it extracts it), available in Releases as
     `opencode-linux-arm64-android.tar.gz` (opencode v1.18.25).
   - Optionally import `auth.json` / `opencode.json` from your desktop
     (`~/.local/share/opencode/auth.json`, `~/.config/opencode/opencode.json`).
   - **Start server** → **Open chat**.
4. For the agent sandbox: main screen → **Agent sandbox** → **Install**
   (Alpine 3.20.9 + proot, bundled in the APK), then **Add tools** once
   (bash, git, nodejs, npm, python3, openssh-client via `apk add`).

## What's in v0.5.0 (P5)

- **Model picker** — header chip lists everything from `GET /config/providers`;
  the chosen model rides on each message body, with automatic retry without
  the model field on 400/422 (self-healing against picky providers).
- **Stop button** — the send arrow morphs into a red stop square while the
  agent is busy → `POST /session/{id}/abort`. Busy state tracks
  `session.status` SSE events; animated "working dots" while streaming.
- **Long-press copy** on any message bubble.
- **Session delete** — long-press in the session list → `DELETE /session/{id}`
  with transcript-cache eviction.
- **Relative timestamps** in the session list — and a real bug fix: opencode
  sends `time.updated` in unix *seconds*; older builds rendered it as
  milliseconds so every session claimed "Jan 20, 1970".
- **Real app icon** — adaptive launcher icon (blue `>_` glyph); the app
  previously shipped with no icon attribute at all.

Carried from earlier phases: SSE streaming with reconnect/backoff, markdown
rendering in assistant bubbles, tool/file activity cards (bash/read/edit as
their own monospace bubbles), permission approval flow with missed-ask
recovery (`permission.asked` → `POST /permission/{id}/reply`), partial wake
lock during agent runs, per-session transcript cache.

## Architecture notes

- **minSdk = targetSdk = 28, deliberately.** targetSdk < 29 preserves the
  Termux-style exec-from-app-private-storage behaviour (`Process` exec of the
  opencode ELF in `files/`), which modern targetSdk levels block via W^X.
- **Zero-dependency UI** — programmatic views + small XML layouts; Markdown is
  a hand-rolled Spannable renderer; JSON via `android.util.JsonReader`.
- **Endpoint discipline** — every API surface used (providers, abort, delete,
  permission reply schema, `time.updated` units) was verified by scanning the
  shipped v1.18.25 binary (`scripts/p5_scan_binary.py`, `scripts/p4_scan_binary.py`),
  not guessed from docs.
- **Sandbox** — Alpine minirootfs via proot 5.3.0 (both bundled as assets);
  `PROOT_TMP_DIR` is set everywhere proot runs (host must not inherit the
  guest's `TMPDIR=/tmp` or proot's glue rootfs breaks); busybox tar extraction
  with a pure-Java fallback; install-time self-test `proot -R rootfs /bin/echo
  oc-proot-ok`; `/sdcard` bound into the guest; `bash`/`git` shims route
  through the sandbox with graceful fallback.

## Build from source

- JDK 21, Android SDK platform 34 + build-tools 34.0.0, Gradle 8.9, AGP 8.5.2.
- On a fresh machine, `scripts/p0_setup_toolchain.sh` restores the whole
  toolchain rootless in `~/p0-tools` (~2 min).
- Then: `JAVA_HOME=<jdk21> gradle assembleDebug` from this directory.
  Output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed).

Project layout:

```
app/src/main/java/ai/opencode/app/
  MainActivity.java    import binary/creds, server start/stop, sandbox cards
  ServerService.java   foreground service, opencode serve :4096, global SSE,
                       permission queue, wake lock
  ChatActivity.java    chat screen, SSE reconcile, model picker, stop/abort,
                       permission dialogs, tool cards
  SessionsActivity.java session list, delete, relative timestamps
  Api.java             loopback HTTP client (SSE-capable)
  Models.java          /config/providers parser
  Binaries.java        SAF import, ELF gate, chmod, version probe
  Sandboxes.java       proot/Alpine installer, shims, self-test
  TarGz.java           pure-Java tar.gz extractor
  Markdown.java        Spannable markdown renderer
  Json.java            JsonReader helpers
scripts/               toolchain setup, binary API scanners, packaging
```

## Checksums (v0.5.0 release artifacts)

```
6384e745af0ee988a6f51e8aa205c0d71b614e0bb100d22c4e535b355d673a7d  opencode-linux-arm64-android.tar.gz
5c347e5dbb76cf2dc81084c98c5330ee06dea2d4c06609c287f29143d3faa887  opencode-p5-v0.5.0-debug.apk
ab6b1f6c36bf8118839dddb2e92b37ba50c3550ff7bad70c3cbd9cdc55463f41  opencode-p5-kit.tar.gz
```

## Status / roadmap

| Phase | Scope | Status |
|-------|-------|--------|
| P0 | Feasibility probes (exec policy, serve/SSE, ELF gate) | done, device-verified |
| P1 | Foreground service + chat screen on in-process server | done, device-verified |
| P2 | Permissions, markdown, sessions, wake lock | done, device-verified |
| P3a | Agent sandbox (Alpine + proot in-app, /sdcard bind) | shipped |
| P4 | proot TMPDIR fix, permission schema fix, tool cards | shipped, on-device test pending |
| P5 | Model picker, abort, copy, session delete, icon | shipped, on-device test pending |
