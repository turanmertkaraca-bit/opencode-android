# opencode-android

The opencode TUI (v1.18.25) as a native Android chat app. Zero external
dependencies — no Termux install, no proot, no root: the agent binary is
bundled in the APK and runs natively in app-private storage.

Repo: https://github.com/turanmertkaraca-bit/opencode-android
Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases

## Install (v0.10.0 — self-tested polish: permission fix, deck feel, chat blocks)

1. Grab `opencode-p10-v0.10.0-debug.apk` from the releases page and sideload
   it (same signing key as v0.6.0-v0.9.0 → updates in place).
2. Open the app. The first boot unpacks the agent (~60 MB) and the sandbox
   toolkit (~4 MB, watch the log), then the **project deck** opens.
3. Tap a project card → that project's own sandbox → chat. **＋ → pick a
   folder** adds a project. **⌘ → API keys** → OpenCode Zen (first row) or
   any provider → paste a key → send.
4. The agent can install its own tooling — `pkg install python3 git
   nodejs gcc …` (Alpine packages via apk, no proot; wrappers auto-link
   onto PATH; downloads flow through the in-app proxy).

## What's in v0.10.0 (P10 — every fix verified by an automated self-test)

- **Permission buttons FIXED** — Allow / Always allow / Deny were dead
  because replies queued on the same single thread as the (blocking)
  message POST — and permissions always arrive mid-run. Replies now run
  on a dedicated pool; an automated test taps Allow and watches
  `POST /permission/{id}/reply {"reply":"once"}` arrive on the wire.
  Endpoint + reply values verified against the shipped v1.18.25 binary.
  The approval card is now an indigo sheet with big buttons + toasts.
- **Fast fling FIXED** — a quick flick on the deck always lands on the
  next/previous card (ViewPager's direction rule anchored at the gesture's
  start page). 5 regression tests encode the complaint.
- **Cards stay together** — the deck is a stacked wallet: card-height +
  small gap between cards, neighbors peek above/below, strip biased up.
- **Chat blocks redesigned** — tool calls are icon-disc cards (per-tool
  color, status line, tap-anywhere expand, rounded INPUT/OUTPUT code
  blocks); THINKING cards are violet with italic voice.
- **OpenCode Zen key** — listed first on the API keys screen.
- **Easier navigation** — visible back button in the chat header.
- **Self-test harness shipped** (`app/src/test/`): DeckSnapTest (5),
  PermissionFlowTest (2, real HTTP round-trips), ScreenTest (3, renders
  real views to PNGs) — 10/10 green at release time.

## What's in v0.9.0 (P9)

- **Sandbox package manager (`pkg`)** — the Alpine minirootfs (aarch64)
  ships inside the APK; every binary runs through the musl dynamic loader
  directly from app-private storage. `pkg update / install / remove /
  search / list / rehash` maps onto apk with signatures verified. The
  agent's shell gets python3, pip, git, node, gcc, ripgrep, jq, curl,
  openssh and 30k+ more Alpine packages on demand.
- **Realtime chat** — SSE deltas feed a 24 ms smoothing ticker: text
  materializes token-by-token with a live caret, then finalizes with one
  markdown render. In-place view updates keep it snappy on long chats.
- **Chat redesign** — gradient user bubbles, THINKING cards, tool cards
  with status dots + friendly names, error cards, hero empty state with
  starter prompts, raised composer bar (⌘ · Build/Plan · model chips ·
  gradient send FAB).
- **Full model catalog** — /config/providers merged with the complete
  models.dev catalog: every provider/model that exists, searchable, with
  "(no key)" markers, disk-cached.
- **Settings from zero** — gradient hero server card, section cards with
  icon discs, custom animated switch, sandbox section (pkg status,
  install/repair, rehash, doctor).
- **Deck fix** — page indicator is now a vertical rail (the old horizontal
  dots read as left/right while paging was up/down).

## What's in v0.8.0 (P8 — the deck: style, motion, per-project sandboxes)
— the deck: style, motion, per-project sandboxes)

- **Project deck (home)** — projects shown as credit-card-style gradient
  cards in a vertical snap carousel (`DeckView`, a custom framework-only
  pager: one card per gesture, neighbors peek + shrink + dim, animated
  page dots, pulsing status pill, staggered entrances). ＋ ghost card →
  folder picker (browse /sdcard, create folders) → new card; long-press →
  open / rename / remove (files are never touched).
- **A sandbox per project** — tapping a card opens that project's OWN
  sandbox: the opencode server is (re)started with the project folder as
  its working directory, so the agent's file tools, sessions and shell
  cwd are rooted exactly there. The last-used project is pre-warmed at
  boot — opening its card is INSTANT (no restart); switching projects
  shows an animated "starting sandbox" veil (~5 s cold start).
- **Midnight-deck restyle** — deep blue-black background, indigo accent,
  glassy surfaces, fast (≤ 230 ms) activity transitions app-wide; chat
  rows animate in, a three-dot typing indicator pulses while the agent
  thinks, a "↓ latest" pill appears when you scroll up, send/mode chips
  spring on tap. Everything is framework views/APIs — still zero
  dependencies.
- **Settings, restyled** — animated hub (rounded sections, staggered
  entrances, switch rows) with: server state + restart, DNS bridge,
  default-model picker (all models), API keys, project deck link, and a
  **sandbox doctor** that shows what the agent can actually run
  (opencode version, busybox, bash/git shims, python3/node/gcc, PATH) —
  plus an **Animations** toggle that also honors the system's "remove
  animations" accessibility setting.
- Chat logic from P7 is unchanged (⌘ palette — now with Projects and
  Settings entries — Build/Plan chip, collapsed thinking/tool cards,
  pinned permission card, searchable all-models, stop/abort, sessions,
  export, crash capture).

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

## Checksums (v0.8.0 release artifacts)

```
(see SHA256SUMS.txt in the release assets — APK + kit + binary tarball)
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
| P7 chat-first rewrite, no proot, crash-proofing | shipped |
| P8 project deck, per-project sandboxes, motion design | **current** |
| Next: on-device toolchain (clang) import path | planned |
