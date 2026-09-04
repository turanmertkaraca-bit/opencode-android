# opencode-android

The opencode TUI (v1.18.25) as a native Android chat app. Zero external
dependencies — no Termux install, no proot, no root: the agent binary is
bundled in the APK and runs natively in app-private storage.

Repo: https://github.com/turanmertkaraca-bit/opencode-android
Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases

## Install (v0.20.0 — P20)

1. Grab `opencode-p20-v0.20.0-debug.apk` from the releases page and sideload
   it (same signing key as every earlier build → updates in place, no
   uninstall; your projects, keys and sessions survive).
2. Open the app: the project deck opens, tap a card → that project's
   sandbox → chat. **＋** adds a project. **⌘** is the command palette.
3. **⌘ → API keys** to paste keys; the OpenCode row (Zen + Go plans,
   console.opencode.ai) runs its 31 FREE models with no key at all.
4. One-minute armor against Galaxy process kills: **Settings → keep alive →
   Battery optimization — exempt ✓**, plus Device care → Never sleeping apps.

## What's in v0.20.0 (P20 — the background survivor)

- **the empty thought bubble is dead** — the field report: leave the app
  in background during a run, come back, tap the ✦ THINKING card → empty.
  Root cause: the chat unsubscribes from the event feed while paused, and
  onResume only refetched an EMPTY list — every part that fired while you
  were away was lost forever. Now EVERY resume replays the session from
  the server's own message store: known parts update in place, missed
  parts append in order, and nothing the agent said while the screen was
  away can disappear again (trim-safe: ancient trimmed rows are never
  re-appended at the bottom).
- **thinking streams token-by-token now** — the P9 smoothing ticker only
  drove assistant text; reasoning cards painted in raw SSE bursts. The
  ticker now drives thinking rows too: a collapsed card grows a live
  one-line ticker of the FRESHEST thought (sliding window, caret), an
  open card streams its body with the caret, and everything finalizes
  into the calm collapsed card on catch-up.
- **returning from background settles the truth** — if the run FINISHED
  while you were away, the chat settles itself (no more "working — tap ■
  to stop" spinning forever) and says so in one line. A run still going
  re-arms the full busy UI (P19 self-heal).
- **no more dead THINKING cards** — a reasoning part born empty that
  never received text (run died early) is hidden at settle instead of
  sitting there as an unopenable "THINKING…" card forever.
- 9 new JVM regression tests (67 total): stable part keys, the live
  think-window edges, and the settle-only-when-finished rule.

## What's in v0.19.0 (P19 — the crash killer)

- **the cold-boot crash is structurally dead** — the field crash (app
  process killed by the device; the orphaned server child kept port 4096;
  every respawn died EADDRINUSE until a phone reboot) cannot wedge the
  sandbox anymore: the supervisor asks the kernel for a free port before
  every spawn (4096 when free, kernel-assigned the moment it isn't),
  sweeps orphaned opencode processes by exact-binary match, and gates
  "healthy" on the child's own listen banner. Upstream opencode v1.18.25
  was stress-tested standalone (write burst + storm + kill -9 respawn):
  the server survived everything — the killer was device-level process
  death, and the sandbox now survives that too.
- **nothing dies silently anymore** — a 30 s heartbeat in
  sandbox-diag.log means even a whole-process kill leaves "when it
  stopped + what memory looked like" on disk. Settings → keep alive →
  **Sandbox incident log**.
- **the live-edit shower actually shows** — the P18 watchdog declared a
  run dead after 3.5 s of feed silence (bash runs ARE silent for
  minutes), tearing down the live-edit watcher mid-run. The quiet
  threshold is now 10 minutes; the edit card lives on the ✦ thinking
  surface from run start and vanishes when a run produced zero edits.

## What's in v0.18.0 (P18 — the unstoppable sandbox)

- **the sandbox heals itself** — when the opencode server process dies
  (the field report: "chat and sandbox shuts off, cold boot again"), the
  service now auto-restarts it in place with growing backoff (1.5 s → 4 s
  → 8 s), kills any stale port squatter first so a zombie listener can
  never wedge the respawn, and the chat stays attached: a ♻ row says
  "sandbox auto-recovered — this chat is still attached". Sessions live
  on disk, so you keep working. Three deaths inside 10 minutes trips a
  crash-loop guard that stops burning battery and says so.
- **every death leaves a black-box record** — files/sandbox-diag.log
  (timestamp · event · exit code · last server output · free memory),
  one tap away in Settings → keep alive → **Sandbox incident log**. No
  more "no Java crash file is written" dead ends.
- **send timeouts can't kill a thinking run** — the field report's
  `send failed: java.net.SocketTimeoutException: timeout` fired while
  the agent was still working. The send POST now has a 15-minute read
  budget, a timeout is soft-landed ("still watching the run — tap ■ to
  stop if nothing moves") and the SSE feed keeps rendering; the run is
  NEVER re-POSTed (a blind retry would run the agent twice and double
  the tokens). Broken-pipe errors get their own human wording. Raw
  java.net text is banned from the chat.
- **the Σ pill explains itself** — the top counter is the chat's
  cumulative token + cost sum; it only ever climbs because every turn
  re-sends the whole conversation. Now labeled **Σ**, and tapping it
  opens a breakdown: what the number is, how deep the conversation is
  (~context each new turn re-reads), and at ≥50k depth a **＋ Fresh
  chat** button that resets per-turn cost in one tap (old chat stays in
  Sessions). The 1.9 M tok field report was the runaway diagnosis loop
  the other two fixes eliminate.

## What's in v0.15.0 (P15 — the P12 picker restored + proot dirs/env + the UI rework)

- **model picker = the first P12 again** — built from forensics on the
  actual P12a release source: `Mdl.live` is back. Bright rows = the
  running server serves them right now; dim "· catalog" rows are
  discovery-only and a tap on them REFUSES with a plain-language toast
  instead of a runtime "Model not found". available() requires live, so
  the send-path self-heal clears stale picks before the request.
  Usable providers first, live models first — the P12a feel, keeping
  P14's ⟨free⟩ badges, $/Mtok, 88% sheet, search. Provider headers no
  longer close the sheet.
- **Debian dirs initialized before proot runs** (the agent's own field
  report, 1:1): ensureDirs() creates files/debian/tmp (the PROOT_TMP_DIR
  target proot mkdtemps inside), files/home and rootfs/tmp before
  install/probe/every guest run/launcher write.
- **environment detection + welcome message** — every chat opens with a
  one-shot environment row (kernel · arch · user · cwd · OS · tools ·
  Download reachability · project path), gathered inside Debian when
  active; also written to files/debian/env.txt for the agent. ⌘ →
  "Sandbox environment" re-runs it; Settings → Environment check audits
  the dirs.
- **Files — the visual project file manager** — breadcrumbs, gradient
  discs, type glyphs, size/age, preview sheet with copy-all, rename /
  delete / copy path, ＋ new folder / file. Project-scoped by design.
- **chat fluidity** — merge-path repaints coalesced to one flush per
  80 ms: a burst of N SSE events costs one relayout, not N. Sends,
  expand/collapse and error rows stay instant.

## What was in v0.14.0 (P14 — the field-report killer)

- **bash shim fixed for real** — the Debian branch test was emitted as two
  lines; mksh cannot parse a newline before `&&` (a leading operator is a
  syntax error), so every bash tool call died at line 5. Now single-line,
  the generator refuses to ever write a continuation-operator line, and
  JVM regression tests pin it. Updating repairs the shim on-device.
- **model picker merge fixed** — a server response no longer short-circuits
  the models.dev catalog (bundled snapshot keeps everything visible even
  offline); key state comes from the app's own auth.json; "(add API key)"
  providers open the keys screen on a single tap; free models badged,
  paid models show $/Mtok.
- **session spend pill** — ⇅ tokens + $ cost in their own header pill, no
  longer ellipsized away inside the one-line subtitle.
- **unattended mode** — auto-answers tool approvals ("always"), status
  pill instead of the blocking card, failed replies fall back to the card.
- **long-output jank fixed** — tool I/O blocks dropped selectable spans,
  cap with a "+N more chars" tail, long-press copies the full text.
- **model sheet rebuilt** — 88%-height bottom sheet, weight-based list,
  recycled rows; **settings gained an agent section** (unattended toggle +
  GitHub token access). **Zen/Go clarified**: same row, same key.
- **Debian 12 + apt** (from P12/P13, intact here): one shared rootfs in
  app-private storage — install packages ONCE, every project session
  binds only its own folder. Probed at install; falls back to the Lite
  (Alpine) layer if the device refuses it.

## What came before (highlights)

- **P13** Debian install actually installs (probed, with proxy wiring for
  apt/pip/git); **P12** monochrome "graphite" theme, session spend meter,
  agent GitHub token; **P11** per-chat model picks + model-not-found
  self-heal (verified live); **P10** permission/stop on a control lane
  (they finally fire mid-turn), deck fling fix, tool/thought card
  redesign; **P9** `pkg` package manager + realtime chat + full model
  catalog; **P8** project deck with per-project sandboxes; **P7**
  from-scratch chat-first rewrite (palette, collapsed reasoning/tool
  cards, in-app keys, diagnostics); **P6** zero-setup wizard with the
  bundled binary.

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

## Checksums (see SHA256SUMS.txt in each release / kit)

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
