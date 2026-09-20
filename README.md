# opencode-android

The opencode agent (v1.18.25) as a native Android chat app. Zero external
dependencies — no Termux, no proot dance, no root: the agent binary is
bundled in the APK and runs natively in app-private storage, with a full
Debian 12 toolchain (apt, git, python, node, gcc) available to the agent.

Repo: https://github.com/turanmertkaraca-bit/opencode-android
Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases

---

## What it does

- **Real chat, real streaming** — tokens arrive and render live
  (token-by-token, paced, no strobe), with thinking shown in a calm
  fixed-height card that never bounces the transcript.
- **A real sandbox per project** — each project card gets its own
  sandbox rooted at its folder. The agent can read, write, run shell
  commands, install packages with apt, and clone git repos (clones are
  tuned for Android's FUSE storage).
- **The question tool works** — when the agent asks you a question
  (or requests plan approval), a pinned card shows the options as
  tappable chips. Answer or Skip in place; the agent continues.
- **Permissions, both ways** — tool approvals arrive as a pinned card
  (Allow once / Always / Deny), and an opt-in unattended mode can
  auto-allow them while you're away.
- **All models, searchable** — every provider the server discovers,
  with key status per provider, per-message model choice, and live
  cost/token footers. The OpenCode row runs 31 free models with no
  key at all.
- **Files, canvas, sessions** — a project-scoped file browser, an
  interactive canvas for self-contained HTML pages the agent writes,
  parallel chats with per-session run state, export to Downloads.
- **Zero-crash posture** — every render/parse path is guarded (a bad
  part degrades, never crashes), server deaths are diagnosed and
  auto-restarted, and every incident lands in a readable log
  (Diagnostics → Sandbox incident log).

## Install

1. Grab the newest `opencode-pXX-vX.Y.Z-debug.apk` from the
   [releases page](https://github.com/turanmertkaraca-bit/opencode-android/releases)
   and sideload it. Every release uses the same signing key → installs
   as an update in place; projects, keys and sessions survive.
2. Open the app → tap a project card (a Playground is seeded on first
   run) → **⌘ → API keys** to paste a key. The sandbox reloads keys by
   itself the moment one is saved.
3. That's it. Type in the box, send, watch it stream.

## Background work (important)

The app is built to keep working while backgrounded — the sandbox is a
foreground service with a persistent notification, and three layers
protect it:

- **Auto-hibernate is OFF by default** — the sandbox never stops itself
  while you're away (it used to; the switch is still there under
  Settings → keep alive for battery-conscious days).
- **Background watchdog (default ON)** — if the system kills the app in
  the background, an allow-while-idle alarm puts it right back (~4 min
  cadence). Sessions live on disk, so the chat is still attached.
- **Battery exemption** — tap **"Allow background run"** on the
  notification once (Settings → keep alive → Battery optimization).
  On Samsung, additionally allow the app under **Device care →
  Never sleeping apps** (Settings links the guide).

If anything ever does die: Diagnostics → **last exits** / **Sandbox
incident log** names the killer (exit code, last output, memory state).

## The command palette (⌘)

Ctrl+P parity: new chat, sessions, models, Build/Plan mode, API keys,
files, logs & shell, restart server, export, expand/collapse all.
The composer chip **Build** toggles the agent mode (Tab parity).

## Settings, briefly

- **keep alive** — battery exemption, start on boot, cool idle (wake
  lock only while the agent works), auto-hibernate (off), background
  watchdog (on), cache beats, notifications, the Samsung guide, and
  the sandbox incident log.
- **appearance** — six palettes (Graphite is the default face), the
  Claude-style chat layout, motion toggle (also honors the system
  "remove animations" accessibility setting).
- **essentials** — interactive canvas, model defaults, keys, credits.
- **sandbox** — Debian tooling, curated rootfs trim, environment
  reset, DNS bridge (for exotic VPN/DNS setups only), diagnostics
  with a live log tail and a native shell console.

## Building from source

```bash
git clone https://github.com/turanmertkaraca-bit/opencode-android
cd opencode-android
bash scripts/p0_setup_toolchain.sh   # gradle 8.9 + android-sdk into ~/p0-tools
# restore the payload (not in git): opencode-linux-arm64-android.tar.gz
# from any release → app/src/main/assets/oc_pkg.bin
bash scripts/build_apk.sh            # → app/build/outputs/apk/debug/app-debug.apk
```

JVM test suite (Robolectric, ~550 tests):

```bash
~/p0-tools/gradle-8.9/bin/gradle testDebugUnitTest --no-daemon
```

## Release notes, one line each

| Version | The release |
|---|---|
| v0.49.0 | consistency: Settings ANR cured at the source, keyboard re-anchor, quiet markdown finalize, honest scroll base |
| v0.48.0 | smooth: pacing profiles with hard caps, fixed 3-line thinking stage, scroll pin stabilization |
| v0.47.0 | live tokens: message.part.delta streaming, in-place fast path, FUSE-tuned git repos |
| v0.46.0 | keep-alive groundwork + git shim FUSE workarounds |
| v0.45.0 | act-normal: auto-compaction default OFF |
| v0.44.0 | quiet-collapse fixes + warm-paper palette attempt |
| v0.43.0 | realtime-feel pacing profiles |
| v0.42.0 | honesty: spend visibility, compaction kill switch |
| v0.41.0 | memory metering + cache beats |
| v0.40.0 | compaction repair (poisoned-root diagnosis) |
| v0.39.0 | context health + self-checks (carries v0.38.0) |
| v0.37.0 | chat stability pass |
| v0.36.0 | themed launcher icons |
| v0.35.0 | agent web browser (RenderServer) |
| v0.34.0 | single-surface chat + credit card in essentials |
| v0.33.0 | verification pass + release gates |
| v0.32.0 | polish pass |
| v0.31.0 | parallel chats + interactive canvas |
| v0.30.0 | settings restructure (terse mode as a live note) |
| v0.29.0 | model picker rework |
| v0.28.0 | stability pass |
| v0.27.0 | boot budget instrumentation |
| v0.26.0 | evergreen boot |
| v0.25.0 | run lifecycle (unattended auto-allow) |
| v0.24.0 | streaming flush scheduler |
| v0.23.0 | blast-radius fixes |
| v0.22.0 | native shell depth |
| v0.21.0 | long-run stability |
| v0.20.0 | background keep-alive |
| v0.19.0 | crash forensics |
| v0.18.0 | self-healing supervisor |
| v0.17.0 | edit sheet + diagnostics |
| v0.16.0 | key clarity (Zen vs Go) + DeX |
| v0.15.0 | project file manager |
| v0.14.0 | session hardening |
| v0.13.0 | initial public snapshot |

v0.50.0-p50 (in flight): the background release — background working
made certain (watchdog, want-flag, notification battery action) and the
question tool answerable in place.

## Privacy

Your API keys live in app-private storage and go only to the model
provider you configured. The app talks to its own in-process loopback
server; there is no telemetry and no third-party endpoint.
