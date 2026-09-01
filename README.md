# opencode-android

Native Android client for the [opencode](https://github.com/sst/opencode) CLI —
a Termux-style chat UI wired to an in-process opencode server, with an
agent-only Alpine Linux / proot sandbox. Zero external dependencies: every
class is framework-only Java, no Gradle deps, no WebView, no bridges.

Built and tested for a Samsung SM-G990E (Android 16 / API 36, arm64-v8a), but
it should run on any arm64 device with Android 9+ (minSdk 28).

## Install (v0.6.0 — everything is automatic now)

1. Grab `opencode-p6-v0.6.0-debug.apk` from **Releases** and sideload it
   (allow "install unknown apps" when prompted).
2. Open the app — the opencode agent binary (v1.18.25, arm64) is **bundled
   in the APK** and auto-unpacks on first launch (one-time, with progress).
   No file importing.
3. Tap **Connect API keys** and paste a key for your provider (Anthropic,
   OpenAI, Google, OpenRouter, Groq, xAI, Mistral, DeepSeek, Together,
   Perplexity — or add a custom OpenAI-compatible endpoint with a base URL).
   Keys are written to the app-private `auth.json`; `opencode.json` gets the
   default model. Importing a desktop `auth.json` still works too.
4. The server starts by itself. Chat auto-opens once it is healthy.
5. The agent sandbox (Alpine 3.20.9 + proot, bundled) auto-installs after
   the server is up; **Add tools** (bash/git/nodejs/npm/python3/openssh) is a
   one-tap extra that needs network.

> NOTE: v0.6.0 is signed with a new persistent debug keystore — uninstall any
> older OpenCode build before installing (one-time; future updates will
> upgrade in place).

## What's in v0.6.0 (P6 — the "complete app" drop)

- **Zero-setup flow**: bundled binary + auto-extract + auto server start +
  auto chat open. The manual import/install/start checklist is gone.
- **In-app API connection** (`ProviderSetupActivity`): provider keys →
  `auth.json` (`{"id":{"type":"api","key":…}}`), custom OpenAI-compatible
  providers → `opencode.json` (`npm:"@ai-sdk/openai-compatible"` + baseURL —
  shape verified in the shipped binary), plus auth.json import.
- **Model picker v2**: every provider the server reports, grouped with
  headers, searchable by name/id; picking a model also writes it as the
  server-wide default (`"model":"provider/model"`) so fresh sessions never
  hit the "no model configured" 500.
- **TUI-style chat rendering** — messages are rendered part-by-part:
  - `reasoning` parts → "✦ Thinking" cards, collapsed by default, tap to
    expand (the thought bubbles).
  - `tool` parts → compact cards (`▸ bash · $ npm install ●`) with live
    status dots (running/completed/error), collapsed by default; tap to
    expand full input + output. edit/write/patch outputs get **diff
    coloring** (+green/−red/@@blue).
  - `patch` parts → "N files changed" cards listing touched files.
- **Token/cost footers** on assistant bubbles — the tokens formula
  (`input+output+reasoning+cache.read+cache.write`) was verified in the
  shipped binary; cost comes from `info.cost`.
- **Setup shortcut in chat**: a failed send (e.g. HTTP 500 with no model)
  adds a tappable "Connect API keys" card right in the conversation.

Carried from earlier phases: SSE streaming with reconnect/backoff, markdown
assistant bubbles, permission approval flow with missed-ask recovery,
stop/abort button, long-press copy, session list with delete + relative
timestamps, working-dots indicator, per-session transcript cache, partial
wake lock during agent runs, adaptive launcher icon.

## Architecture notes

- **minSdk = targetSdk = 28, deliberately.** targetSdk < 29 preserves the
  Termux-style exec-from-app-private-storage behaviour (`Process` exec of the
  opencode ELF in `files/`), which modern targetSdk levels block via W^X.
- **Zero-dependency UI** — programmatic views + small XML layouts; Markdown
  is a hand-rolled Spannable renderer; JSON via `android.util.JsonReader`
  plus a matching serializer for the files the app writes.
- **Endpoint discipline** — every API surface used (providers, abort, delete,
  permission reply schema, reasoning/tool/patch part shapes, token formula,
  auth.json location, custom provider config) was verified by scanning the
  shipped v1.18.25 binary (`scripts/p6_scan_binary.py`, `p6_scan2.py`,
  `p5_scan_binary.py`, `p4_scan_binary.py`), not guessed from docs.
- **Bundled binary** — the 60 MB opencode tarball ships as
  `assets/oc_pkg.bin` (`noCompress`, `.bin` suffix so aapt2 cannot
  decompress/rename it — the P3a rootfs lesson); first launch extracts it
  with the pure-Java `TarGz` extractor and chmods it executable.
- **Sandbox** — Alpine minirootfs via proot 5.3.0 (both bundled as assets);
  `PROOT_TMP_DIR` set everywhere proot runs; busybox tar extraction with a
  pure-Java fallback; install-time self-test; `/sdcard` bound into the
  guest; `bash`/`git` shims route through the sandbox with graceful
  fallback.

## Build from source

- JDK 21, Android SDK platform 34 + build-tools 34.0.0, Gradle 8.9, AGP 8.5.2.
- On a fresh machine, `scripts/p0_setup_toolchain.sh` restores the whole
  toolchain rootless in `~/p0-tools` (~2 min).
- Put an opencode arm64 tarball at `app/src/main/assets/oc_pkg.bin`
  (gitignored) to build with the bundled binary — or build without it and
  import the binary via SAF on the device.
- `JAVA_HOME=<jdk21> gradle assembleDebug` → `app/build/outputs/apk/debug/`.

Project layout:

```
app/src/main/java/ai/opencode/app/
  MainActivity.java          wizard: auto-extract, auto server, auto chat
  ProviderSetupActivity.java in-app API key / custom provider management
  ChatActivity.java          part-aware chat: reasoning + tool cards, model
                             picker with search, token/cost footers
  SessionsActivity.java      session list, delete, relative timestamps
  ServerService.java         foreground service, opencode serve :4096, SSE,
                             permission queue, wake lock, restart()
  AuthStore.java             auth.json / opencode.json read-write (P6)
  Models.java                provider/model catalog + selection (P6)
  Api.java                   loopback HTTP client (SSE-capable)
  Binaries.java              SAF import, bundled extraction, ELF gate
  Sandboxes.java             proot/Alpine installer, shims, self-test
  TarGz.java                 pure-Java tar.gz extractor
  Markdown.java              Spannable markdown renderer
  Json.java                  JsonReader helpers + serializer
scripts/                     toolchain setup, binary API scanners, packaging
```

## Checksums (v0.6.0 release artifacts)

```
6384e745af0ee988a6f51e8aa205c0d71b614e0bb100d22c4e535b355d673a7d  opencode-linux-arm64-android.tar.gz
cdf5c8a74575ff71b5680ad06c31c63e5039849badc00ee22826c1cd8ee3c00f  opencode-p6-v0.6.0-debug.apk
ac0971301f7faae239d5d4ef463504e0edeeb34db7bface8588b44bab93b3bd3  opencode-p6-kit.tar.gz
```

## Status / roadmap

| Phase | Scope | Status |
|-------|-------|--------|
| P0 | Feasibility probes (exec policy, serve/SSE, ELF gate) | done, device-verified |
| P1 | Foreground service + chat screen on in-process server | done, device-verified |
| P2 | Permissions, markdown, sessions, wake lock | done, device-verified |
| P3a | Agent sandbox (Alpine + proot in-app, /sdcard bind) | shipped |
| P4 | proot TMPDIR fix, permission schema fix, tool cards | shipped |
| P5 | Model picker, abort, copy, session delete, icon | shipped |
| P6 | Zero-setup wizard, in-app API connect, TUI-style parts rendering, all-models picker, token/cost footers | shipped, on-device test pending |
