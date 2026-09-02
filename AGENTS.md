# AGENTS.md — read me FIRST (you are the on-device agent)

You are opencode, running inside the **opencode-android** app sandbox on a
Samsung phone (arm64). The folder you are working in is the **source code of
the very app you are running inside of**. The user wants you to be able to
analyze it, improve it, and eventually push new versions from right here.

## What this repo is

`opencode-android` — a zero-dependency Android wrapper (namespace
`ai.opencode.app`) around the official `opencode` CLI v1.18.25
 linux-arm64 build. No Gradle plugins beyond AGP, no androidx — 100%
framework views. The APK embeds the opencode binary as the asset
`oc_pkg.bin` (added at build time, never committed) and spawns it as a
localhost HTTP server; the app UI is a native client of that server.

## Source map (app/src/main/java/ai/opencode/app/)

| File | Role |
|---|---|
| `MainActivity` | boot screen: binary/ELF checks, crash capture, first-run |
| `HomeActivity` + `DeckView` | project deck (vertical credit-card carousel), one sandbox per project |
| `ChatActivity` | THE app: chat, streaming smoother, tool/reasoning cards, permission card, model sheet, sessions sheet, session wallet |
| `ServerService` | foreground service; spawns/embeds the opencode server, per-project cwd switching, SSE event rebroadcast, permission queue |
| `Api` / `Json` | loopback HTTP client + dependency-free JSON |
| `Models` | provider/model catalog: server `/config/providers` (live) ⊕ models.dev (discovery); persistence |
| `Sandbox` | Alpine minirootfs layer (musl-loader wrappers, `pkg`/`apk` package manager) |
| `Shims` | bash/git/pkg fallbacks, user bin dir |
| `Binaries` | embedded binary extraction, `$HOME` layout, process env (PATH, proxies, GITHUB_TOKEN) |
| `Github` | P12: token storage → `.git-credentials` + `GITHUB_TOKEN` for you |
| `AuthStore` | provider keys → `auth.json` |
| `KeysActivity` / `SettingsActivity` / `DiagnosticsActivity` | API keys UI, settings hub, logs + native shell |
| `Theme` | the graphite/monochrome design system |

Build: Gradle 8.9 + AGP 8.5.2, compileSdk 34, **minSdk=targetSdk 28 on
purpose** (the Termux-style exec allowance — do not "fix" this). JDK 21.

## Your environment (sandbox truths)

- `pkg` is the Alpine package manager: `pkg update`, `pkg add <pkg>` works.
- `$GITHUB_TOKEN` / `$GH_TOKEN` are set **iff** the user saved a token in
  Settings → GitHub. `$HOME/.git-credentials` + `$HOME/.gitconfig` are
  written for you too.
- Network goes through an in-app local proxy (`http(s)_proxy` env is
  already set for alpine-world tools).

## First-time setup (run once)

```sh
sh ai/bootstrap.sh
```

It installs `git` + CA certs, turns this folder into a proper clone of
`https://github.com/turanmertkaraca-bit/opencode-android`, and verifies
push access. Re-running it is safe (it never discards your local edits).

## Working protocol

1. **Analyze first.** When asked to "analyze the repo", produce
   `ANALYSIS.md` in the repo root: architecture summary, risk list, and a
   prioritized improvement backlog. Commit it.
2. **Small commits, clear messages** — one logical change per commit,
   message style: `what + why`, e.g. `chat: cut row overdraw (cheaper streaming)`.
3. **Never** print, echo, commit, or embed the token ($GITHUB_TOKEN or
   `.git-credentials`). If a diff ever contains it, stop and rewrite.
4. **Never** force-push, never rewrite history on `main`, never bump
   `versionCode` unless the user asks for a release.
5. Pushing is allowed to `main` (it is the user's repo). CI (GitHub
   Actions) builds + tests every push; a pushed tag `vX.Y.Z-pN` is built
   into a draft release automatically.
6. If a push is rejected (403): the token is missing/expired — tell the
   user to re-paste it in Settings → GitHub. Do not retry in a loop.

## What "good" looks like here

- Runtime zero-dependency discipline: no new libraries without asking.
- Every render path try/caught — a bad payload degrades, never crashes.
- Monochrome (graphite) design language — do not reintroduce colored accents.
- Snappy: incremental view updates, no full-list rebuilds on hot paths.
