# opencode-android

The opencode TUI (v1.18.25) as a native Android chat app. Zero external
dependencies — no Termux install, no proot, no root: the agent binary is
bundled in the APK and runs natively in app-private storage.

Repo: https://github.com/turanmertkaraca-bit/opencode-android
Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases

## Install (v0.7.0 — open the app, land in the chat)

1. Grab `opencode-p7-v0.7.0-debug.apk` from the releases page and sideload
   it (same signing key as v0.6.0 → updates in place).
2. Open the app. A short boot log runs once (unpack → server → health),
   then the chat screen opens — like launching the TUI.
3. **⌘ → API keys** → paste a key (OpenRouter/Groq have free tiers) → send.

## What's in v0.7.0 (P7 — from-scratch chat-first rewrite)

- **Chat-first flow** — no wizard, no setup cards, no sandbox gate. Boot is
  a quiet log; a warm launch skips straight to the transcript.
- **⌘ palette** = the TUI's Ctrl+P: new chat, sessions, model picker,
  Build/Plan toggle, API keys, server logs & shell, restart server,
  expand/collapse all cards, copy last response, export chat to Downloads.
- **Build / Plan chip** = the TUI's Tab; the agent travels with every
  message (with graceful no-agent retry on 400/422/500).
- **"✦ Thinking" reasoning cards and tool-call cards, collapsed by
  default**, tap to expand; tool cards carry live status dots
  (queued/running/done/error) and show input/output; errors auto-expand;
  expand/collapse-all in the palette.
- **Permission asks** (bash, edit, webfetch…) pin above the composer with
  Allow once / Always / Deny. The foreground service catches asks even
  when the screen is closed and tombstones answered ids.
- **Every model, searchable** — `GET /config/providers` grouped by
  provider with search; picking sets the per-message model and the
  server-side default (`opencode.json`).
- **In-app API keys** (⌘ → API keys) — provider keys land in the
  app-private `auth.json`; custom OpenAI-compatible endpoints
  (OpenRouter, Groq, Ollama, LM Studio, vLLM…) register in
  `opencode.json`; auth.json import kept for desktop migrations.
- **Diagnostics** (⌘ → Server logs & shell): live server log tail, a
  native shell console (busybox + system tools), binary facts + one-tap
  re-unpack, SAF import of your own static arm64 tools into `bin/` (first
  on the agent's PATH), and an optional DNS bridge proxy.
- **Crash capture** — any uncaught exception is written to
  `last-crash.txt` and shown on the next boot; every SSE frame and part
  renders through defensive paths, so a malformed payload degrades to a
  plain line instead of taking the chat down.
- Token/cost footers per assistant message (formula verified in the
  binary), stop/abort, session delete, relative timestamps, partial wake
  lock during agent runs.

## Architecture notes

- **No proot. No rootfs.** The P3–P6 proot/Alpine sandbox was removed. The
  bundled agent is an **Android/NDK bionic build** (ELF interpreter
  `/system/bin/linker64`, "for Android 28" — verified with readelf), so it
  executes natively and resolves DNS through the OS exactly like Termux
  programs. Native shims (`bash` → mksh or a user-imported bash, `git` →
  user-imported binary) replace the proot command wrappers.
- **minSdk = targetSdk = 28, deliberately.** targetSdk < 29 preserves the
  Termux-style exec-from-app-private-storage behaviour (`Process` exec of
  the opencode ELF in `files/`), which modern targetSdk levels block via
  W^X. Verified on-device (API 36): `opencode --version`, 1412 ms, exit 0.
- **Optional DNS bridge** — for exotic VPN/DNS setups, Diagnostics can
  enable a local HTTP-CONNECT proxy (resolves with the OS resolver,
  tunnels raw bytes) and export `HTTPS_PROXY`/`HTTP_PROXY` into the server
  process. Off by default; the direct bionic path is the proven one.
- **Zero-dependency UI** — programmatic views + two small XML layouts;
  Markdown is a hand-rolled Spannable renderer; JSON via
  `android.util.JsonReader` plus a matching serializer for the files the
  app writes.
- **Endpoint discipline** — every API surface used (providers, message
  body with model+agent, abort, delete, permission reply schema
  `{"reply":…}`, reasoning/tool/patch part shapes, token formula,
  auth.json location, custom provider config) was verified by scanning the
  shipped v1.18.25 binary (`scripts/p6_scan_binary.py`, `p6_scan2.py`,
  `p5_scan_binary.py`, `p4_scan_binary.py`), not guessed from docs.
- **Bundled binary** — the 60 MB opencode tarball ships as
  `assets/oc_pkg.bin` (`noCompress`, `.bin` suffix so aapt2 cannot
  decompress/rename it); first launch extracts it with the pure-Java
  `TarGz` extractor and chmods it executable. ELF-gated.
- **Foreground service** owns `opencode serve` on 127.0.0.1:4096, the SSE
  stream, the permission queue and a partial wake lock; the UI layer is a
  subscriber.

## Build from source

- JDK 21, Android SDK platform 34 + build-tools 34.0.0, Gradle 8.9, AGP 8.5.2.
- On a fresh machine, `scripts/p0_setup_toolchain.sh` restores the whole
  toolchain rootless in `~/p0-tools` (~2 min).
- Put an opencode arm64 tarball at `app/src/main/assets/oc_pkg.bin`
  (gitignored) to build with the bundled binary.
- `JAVA_HOME=<jdk21> gradle assembleDebug` → `app/build/outputs/apk/debug/`.

Project layout:

```
app/src/main/java/ai/opencode/app/
  App.java                  crash capture (last-crash.txt)          (P7)
  MainActivity.java         boot screen: unpack → server → chat     (P7)
  ChatActivity.java         the whole UI: transcript, ⌘ palette,
                            Build/Plan chip, collapsed reasoning +
                            tool cards, permission card, model and
                            session sheets, export                  (P7)
  KeysActivity.java         provider API keys → auth.json, custom
                            OpenAI-compatible providers             (P7)
  DiagnosticsActivity.java  server log, native shell, binary facts,
                            DNS bridge toggle, bin/ import          (P7)
  ServerService.java        foreground service, opencode serve :4096,
                            SSE, permission queue, wake lock
  ProxyServer.java          optional local CONNECT proxy (DNS bridge)(P7)
  Shims.java                native PATH shims (bash/git) + busybox  (P7)
  AuthStore.java            auth.json / opencode.json read-write    (P6)
  Models.java               provider/model catalog + selection      (P6)
  Api.java                  loopback HTTP client (SSE-capable)
  Binaries.java             bundled extraction, ELF gate, env build
  TarGz.java                pure-Java tar.gz extractor
  Markdown.java             Spannable markdown renderer
  Json.java                 JsonReader helpers + serializer
scripts/                     toolchain setup, binary API scanners, packaging
```

## Checksums (v0.7.0 release artifacts)

```
6384e745af0ee988a6f51e8aa205c0d71b614e0bb100d22c4e535b355d673a7d  opencode-linux-arm64-android.tar.gz
f89839bd1ae7cf068b5b540b344a42aabe7147c6db4c65ec094d0e50379fa794  opencode-p7-v0.7.0-debug.apk
90873aaf585ca23fd03274040d2555d8a91c43fb3effb64e2273c38f2692adc5  opencode-p7-kit.tar.gz
```

## Status / roadmap

| Phase | State |
|-------|-------|
| P0 exec probe (targetSdk-28 trick) | verified on device |
| P1 skeleton (server + first chat) | shipped |
| P2 SSE streaming + sessions | shipped |
| P3 proot sandbox (superseded) | removed in P7 |
| P4 permissions + abort + polish | shipped |
| P5 model picker + stop + session mgmt | shipped |
| P6 wizard + bundled binary + in-app keys | shipped |
| P7 chat-first rewrite, no proot, crash-proofing | **current** |
| Next: on-device toolchain (clang) import path | planned |
