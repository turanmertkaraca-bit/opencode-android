# opencode-android

The opencode TUI (v1.18.25) as a native Android chat app. Zero external
dependencies — no Termux install, no proot, no root: the agent binary is
bundled in the APK and runs natively in app-private storage.

Repo: https://github.com/turanmertkaraca-bit/opencode-android
Releases: https://github.com/turanmertkaraca-bit/opencode-android/releases

## Install (v0.37.0 — P37)

1. Grab `opencode-p37-v0.37.0-debug.apk` from the releases page and sideload
   it (same signing key as every earlier build → installs as an update in
   place, no uninstall; your projects, keys and sessions survive).
2. Open the app: the project deck opens, tap a card → that project's
   sandbox → chat. **＋** adds a project, **long-press** a card → the project
   sheet (Open / Rename / Remove card / **Delete project** — the app's own
   palette-owned presentation now, not a system box). **⌘** is the command
   palette (interactive canvas, find in chat, share).
3. **⌘ → API keys** to paste keys; the sandbox reloads them BY ITSELF the
   moment a key is saved, changed or imported — the picker and your next
   message see them immediately, no manual restart. The OpenCode row (Zen +
   Go plans, console.opencode.ai) runs its 31 FREE models with no key at all.
4. One-minute armor against Galaxy process kills: **Settings → keep alive →
   Battery optimization — exempt ✓**, plus Device care → Never sleeping apps.
5. If anything ever dies: **Diagnostics → "last exits"** names the killer
   (system exit records, retroactive), and the sandbox incident log has
   the server's side. Paste both.

## Why not just Termux + proot + the TUI?

Fair question — and it is the honest one, because this project started
exactly there: the first builds were a Termux port with a proot Debian
and the TUI on top, before P7 replaced all of it with the native setup.
Same agent binary either way (opencode v1.18.25). The difference is
everything around it:

| | **opencode-android** | **Termux + proot + TUI** |
|---|---|---|
| First chat | minutes: sideload the APK, paste a key — or none at all for the 31 free models | an evening: Termux, proot-distro, distro bootstrap, package installs, node/binary wiring, TUI config |
| Skills needed | none — if you can use a chat app, you can use this | shell, package manager, TUI keybindings, terminal session management |
| The interface | a native Android chat app: streaming bubbles, six themes, haptics, gesture navigation, real sheets | a terminal grid rendered on a touchscreen |
| Photos & vision | attach up to 6 photos in one message; a vision-capable model sees the pixels, otherwise a free vision model describes them | no practical pipe from your gallery into the TUI |
| Watching it work | live file feed of every edit, a peek at the exact line being changed, tappable file mentions | tail -f and hope |
| Long runs | runs outlive the chat screen, parallel runs tracked per session, sessions auto-recover after a kill | survival depends on terminal multiplexer and wake-lock discipline |
| After Android kills the app | the supervisor restarts the server with backoff; the incident log and last-exits forensics name the killer | you restart Termux and guess what happened |
| Cost & context | a context-depth meter with cache stats, next-send cost prediction, a credit limit that actually stops spending | TUI counters on a six-inch terminal |
| Extras | interactive HTML canvas, model favorites, terse mode, unattended mode, hibernation, in-place updates | whatever you script yourself |
| Sandbox weight | curated rootfs — around 108 MB of dead weight trimmed every session | the full distro image |
| The agent core | **opencode v1.18.25 — identical** | **opencode v1.18.25 — identical** |

One honest row for the other side: the TUI exposes every CLI knob, and
the app deliberately covers the core loop instead — power config still
lives in the sandbox's own files. Same brain, same sessions-on-disk
format. One of the two was designed for a phone.

## What's in v0.37.0 (P37 — the chat stops lying with empty space)

- **no more empty items and weird gaps in chat** — assistant rounds that
  only ran tools (no text) used to render as an invisible padded box with
  their token footer floating alone between the tool cards (the blank
  areas and lonely `tok` lines in the field screenshots). A message now
  exists only when it has words: tool cards sit tight together, real
  messages keep their bodies and footers exactly as before.
- **error cards show one cross, not two** — the `✕` was painted twice on
  the same card.
- **raw tool-call markup in the chat gets explained, once per session** —
  if a model prints its own tool-call syntax as plain text, the app says
  plainly that those actions did not run and another model should be
  tried. The junk text stays visible; nothing is hidden, it just stops
  being unexplained.
- **the environment map rides every session's first message** — the shell
  runs inside a Debian guest whose `/root` the host read/edit/write tools
  cannot see (keep shared files in the project folder — that was the
  read-file error in the field), and the GitHub-token question gets an
  honest answer in both states: with a token set (scoped to this repo, a
  credential helper preinstalled, plain `git push` works, the token is
  never printed) or without one (pushes will fail; add a key under the
  keys screen, Agent GitHub access). No more blind git/gh probing.
- **plain `git push` actually works now** — git never read `GH_TOKEN` by
  itself (the field report had the token in the environment and push
  still failing); the guest ships a `/root/.gitconfig` credential helper
  that reads the token at push time. The file itself holds no secret.
- Rides along: the dead single-screenshot send path removed (superseded
  by the attachment tray long ago); `scripts/axml_parse.py` ships in the
  repo so the build gate's manifest proof survives workspace wipes; and
  the build gate now pins the bundled payload digest — the opencode
  tarball itself, not the binary inside it — so a wrong-stage payload is
  refused before the build even starts.
- 346 JVM tests green, 16 new pins: the blank-part rules, the note texts
  (never carrying a token-shaped secret), the gitconfig quoting, the
  DSML detector, and the zero-footprint render.

## What's in v0.36.0 (P36 — the icon follows the theme)

- **the launcher icon re-inks itself when you pick a theme** — the same
  `>_` glyph the app has carried since P5, on each palette's own face:
  the background gradient is that theme's real home gradient and the
  chevron wears the palette accent (Paper goes light with blue ink, Ember
  goes warm amber, Forest goes deep green). Under the hood it is one
  launcher alias per palette with exactly ONE enabled at a time.
- **an explicit pick always wins, silence keeps the classic** — a device
  that never chose a theme keeps the P17 icon; the Graphite default is a
  rendering default, not a choice (the same philosophy the picker already
  uses for its · default crown).
- **the switch can never strand you** — the new alias is enabled BEFORE
  the old one is disabled, so the launcher never sees a no-icon moment;
  every process start reconciles the alias state, so an interrupted
  switch (enable landed, disable didn't) heals itself on the next open;
  and the whole thing is contained — a PackageManager failure is one
  incident-log line, never a broken theme switch (the palette change
  that called it already succeeded).
- **some launchers repaint lazily** — the icon is correct app-side the
  moment you tap; a launcher that caches hard (a few OEMs) may show the
  old face until it next reloads. That is launcher behavior, not app
  state.
- **the fresh-install boot fix rides along** — the v0.34/v0.35 builds
  packaged the bundled server binary under a name the code does not read
  (n.bin where the code opens oc_pkg.bin): upgrades never noticed (the
  binary was already on disk) but a FRESH install could not boot the
  sandbox. The rebuilt v0.35.0 asset (Sep 8) already carried the fix;
  P36 ships it forward and adds a name-fallback chain (pinned by tests)
  so a packaging slip can never brick the first boot again.
- **330 JVM tests green (17 new)** — the alias table must stay aligned
  with the palette table (a future theme without an icon fails the suite,
  not the user), the theme→alias mapping (garbage never maps to OLED),
  the exactly-one-enabled plan (enable-before-disable, interrupted-switch
  healing, stale-cleanup, no-op when already correct), and the
  bundled-asset name chain.

## What was in v0.35.0 (P35 — the agent's eyes: write, render-check, fix, then show)

- **the loop closed** — the agent could write code and run it, but it
  could not LOOK at a page it wrote: the sandbox has no browser, tool
  results are text, and the canvas was a one-way window — the render
  existed only for the user. P35 gives it eyes.
- **the browser the sandbox could never carry** — no 300 MB chromium in
  the rootfs, nothing that gambles the sandbox's size or stability: the
  app already ships a full web engine (the canvas viewer's WebView), so
  THAT renders offscreen and the agent drives it with one curl from the
  sandbox (loopback-only, token-gated, one route). The reply is JSON:
  verdict pass|fail, every console error, a DOM outline (elements,
  buttons, links, inputs, canvases, horizontal overflow), layout
  notes, and — when asked — a one-paragraph visual description of the
  page via the free vision ladder (keyless, same as photos).
- **the verification loop, taught at the moment it matters** — the P30
  lesson stands (no AGENTS.md blocks: they leak into git and go stale
  mid-session). Instead the ⌘ canvas ask carries the loop itself —
  write the page, render-check it, fix every console error, re-run
  until the verdict is pass, only then call it done — and after any
  .html write/edit/patch in a chat, the next message in THAT chat rides
  one <system-reminder> note teaching the same check (once per
  session, success-marked, zero tokens when no HTML was written).
- **the quiet check mark** — the ▶ interactive chips (tool cards and
  the Files menu) grow a state: · ✓ checked when the last render check
  passed, · ⚠ issues when it failed. Nothing opens by itself; the user
  still taps — they just tap pages the agent already verified.
- **containment everywhere** — one render at a time, a 20 s hard
  watchdog, the path guard (canonical: .. and symlinks cannot walk out
  of the project, 3 MB cap like the canvas), file + network access off
  in the render WebView (external loads are refused AND reported), a
  per-boot token gating the endpoint, and a dead render process is a
  report line — never a crash.
- P35Test pins the path guard, the verdict rule, the report caps and
  shape, the note arming rule and the check-state LRU; the suite runs
  green end to end.

## What was in v0.34.0 (P34 — one surface for every box, a deck that always catches you)

- **every box in the app grew up** — the audit found 41 framework alert
  boxes across 9 files (the Credit limit editor and the Interactive canvas
  ask among them — the grey Android-4 platform box from the field
  screenshots). Every one is now the app's own Sheet: bottom-anchored
  28 dp panel, grab handle, large title, stacked full-width pills,
  palette-correct by construction on all six themes, slide-up/down motion
  on every open AND dismiss (back / scrim / pill), swipe-away header,
  manual IME + nav insets, the motion switch honored, and a dead-flag
  no-op so a sheet can never take a screen down. Zero framework boxes
  remain.
- **back from a chat ALWAYS lands on the deck** — the field report: after
  an update the app spawned straight into the playground chat, and both
  the hardware back and the in-app back closed the app instead of opening
  the project deck (the restore had made the chat the task root —
  `finish()` had nothing beneath it). The back rule is now pure and
  pinned by test: task root → open the deck explicitly, else a plain
  finish reveals it.
- **Settings ESSENTIALS moved to the top** — the important toggles lived
  below a scroll, buried at the bottom of the screen:
  Interactive canvas (with a one-tap pre-typed chat hand-off), Credit
  limit, Default model, API keys, Unattended mode — now a top zone in
  their own accent-washed color family.
- **the credit-limit editor is Pixel-style** — a big amount field,
  $5–$100 + no-limit quick chips, inline validation that keeps the sheet
  open and clears as you type, Save/Reset stacked pills. The direct
  number input stays — a slider cannot know the price range the user is
  willing to give.
- **the polish sweep** — the ⌘ sheet pins a featured canvas row; ⌘ list
  rows and the Diagnostics shell input now paint from live tokens (they
  wore frozen white on Paper before).
- P34Test pins the back rule, the chip round-trips and the resume
  format; 300 JVM tests green; zero framework boxes remain.

## What was in v0.33.0 (P33 — the final version: themes land instantly, Graphite is the face, keys reach the sandbox, the picker tells the truth)

- **the theme change is INSTANT** — tapping a palette in Settings used to
  call `recreate()`: a whole-activity teardown + rebuild + window animation
  — seconds of dead air on every switch. The theme
  now lands the same frame: save → apply → dismiss the sheet → rebuild the
  one view tree in place. The new palette is on screen before the sheet
  finishes its 180 ms slide-out.
- **Graphite is the default face** — the user picked it ("the graphite
  theme is cool make it default"). Fresh installs (and pref-less devices)
  come up in Graphite; an explicit theme choice always wins; the picker's
  "· default" label moved with the crown.
- **the whole-app palette sync actually works now** — P32's `syncIfNeeded`
  compared the pref against the PROCESS-GLOBAL static, which Settings' own
  `apply()` had already updated — so no other screen could ever be "stale"
  and the sync never fired in the field (the source of the lingering
  inconsistent-screens reports). The palette id now rides a
  per-screen decor stamp: every screen knows what it was built with,
  re-skins exactly once when the pref moves, and provably cannot loop.
- **keys reach the sandbox by themselves** — the exact report: the app
  swearing there is no API key even though API settings shows one saved.
  Three layers fixed: (1) a CHANGED key now auto-restarts the sandbox (the
  old code only restarted for a FIRST-TIME key — an updated key left the
  running server serving the old value, so sends failed with key errors
  while API settings showed the new key saved); (2) the model sheet re-reads
  auth.json at open, so a saved key can never be called "missing" by a
  stale fetch; (3) the "no API key yet" hint also counts custom providers
  whose key lives inline in opencode.json. Importing auth.json and adding
  a custom endpoint apply the same way — no manual restart to forget.
- **the picker contrast is honest again** — "p31 showed the models i cant
  select low contrast white while the ones i do have acces to white...
  p32 made everything low contrast white": when the running server didn't
  answer (boot, a key-change restart, hibernate wake), the fetch marked
  EVERY model catalog-only and the whole sheet went dim. The pure
  `carryLive` rule keeps last-known live truth through a server blip — a
  model the server served last time stays bright and selectable, and a
  restart window can no longer flatten the catalog.
- **the project long-press grew up** — the actions menu was the last
  framework list box — the grey Android-4 relic. It's the app's
  own sheet now: project name + mono path header, glyph rows (▸ Open ·
  ✎ Rename · ⌦ Remove card · ✕ Delete project… in the danger color),
  ripple + haptics, palette-owned end to end. The delete confirm shows the
  exact path in a code well with Keep it / Delete forever pills, and the
  rename flow matches. Same shields as P30 underneath (safetyCheck,
  stop-first-when-serving, off-thread walk).
- **smoothness sweep** — the theme-change recreate and the GitHub-token
  save recreate are both gone (in-place updates); the model sheet keeps
  its instant-open; the deck, transcript and Σ pill keep their pinned
  rhythms. Nothing new was added — polish only, as asked.
- 291 JVM tests green (16 new: carryLive rules, embedded-key detection,
  the graphite default, per-screen stamp + one-shot reskin, the in-place
  theme change pinned by decor identity, and the model-sheet auth re-read).

## What was in v0.32.0 (P32 — the final polish: the theme crash, fixed; every screen follows the palette)

- **the theme crash** — "pressing the theme change button causes a
  crash": P31's picker dead-cast `simple_list_item_1` (a TextView!) to
  LinearLayout — ClassCastException on every tap, before the sheet even
  opened, in a line no logic test could see. The dead cast is gone, the
  picker is contained (failure = one toast + incident-log line, never
  the app), and a Robolectric test now performs the exact tap and
  asserts the sheet opens.
- **every screen follows the palette** — a theme switch used to re-skin
  Settings only; all screens sync on resume now (loop-proof,
  contained).
- **no more frozen colors** — code wells, error cards, permission
  pills, system pills, thinking cards, suggestion chips, the user-bubble
  rim, card ink, the sandbox veil and the hero disc all follow the live
  palette now; on the default OLED theme every result is byte-identical
  to P31 (pinned by test) and Paper finally gets real ink. `retint()`
  re-skins the static-XML hairline strokes it could never reach.
- **the picker, improved** — live swatches (bg · surface · accent) on
  every theme row, haptic on select.
- 275 JVM tests green (17 new). Fixes and polish only — no new surface:
  this is the final version.

## What was in v0.31.0 (P31 — parallel chats, favorites, a credit limit, the canvas, themes, sleep)

- **the long-press fix** — "long press to delete doesn't work, it just
  opens the chat": a project card is a clickable child, so it consumed the
  whole touch stream and the deck's gesture detector never saw a
  stationary hold; on release the card's own click fired — the chat
  opened. The long-press now lives ON THE CARD (native long-click:
  consumes the gesture, suppresses the release-click) with the deck
  callback kept as the gap fallback. Pinned by a UI test.
- **★ model favorites** — long-press a model in the picker and it lands
  on a ★ FAVORITES shelf at the very top, ahead of every provider. Tap to
  use, long-press to unpin. Ordered, capped at 8, rotation-proof (a
  favorite the free catalog no longer lists hides — it is never deleted).
- **credit limit** — Settings → Safety: set a dollar cap; the app tracks
  what it actually observed (all-time, this device, persisted) and
  refuses every send past the cap with one honest line. ⚠ subtitle nudge
  at ≥80%, cap state in the Σ popover, manual counter reset.
- **parallel sessions** — runs are tracked per chat: a script can stream
  in one session while you keep working in another (up to 3). Sessions →
  green ● RUNNING NOW badge, long-press → Stop the run; ■ answers only
  the chat on screen; the subtitle announces background runs; the
  watchdog/re-arm/eviction rules all became per-run aware.
- **interactive canvas** — ⌘ → "✦ Interactive canvas…": the agent writes
  a self-contained HTML page (inline CSS/JS, no external resources) to
  canvas.html; a ▶ interactive chip (on the tool card, or Files →
  long-press an .html file) opens it in a sandboxed viewer — JS on, file
  and content access off, external navigation refused, nothing
  auto-opens. Offered, never forced.
- **reset sandbox environment** — Settings → Environment: wipes ONLY the
  extracted tooling (Debian rootfs, Alpine layer, shims, applets,
  caches). Keys, GitHub token, projects, every chat on disk and all
  settings survive; canonical-path guards; optional immediate reinstall.
- **auto-hibernate** — app in the background + no run in any chat + no
  approval waiting + past the quiet threshold (default 10 min) → the
  sandbox stops itself and the RAM goes back to the phone; reopening
  drops you straight into the chat you left, restored from disk. Never
  fires while work is in flight.
- **six themes** — OLED black (default), Midnight blue, Graphite, Ember,
  Forest, Paper (light). Dialogs, status bars and every static XML color
  are remapped at runtime; the old AMOLED toggle migrates.
- **plus** — Share chat as Markdown (system share sheet), Find in chat
  (jump match to match).

## What was in v0.30.0 (P30 — the setting that listens, the honest price line, the delete button)

- **terse replies now work MID-conversation** — the field report was
  exact: P29 wrote the toggle into the project's AGENTS.md, but opencode
  reads that file once per session, so flipping it mid-chat changed
  nothing (testers would call the feature broken) — and the block lived
  inside the project folder, leaking a chat style into git diffs and
  other sessions. Now the toggle is a pure app preference (nothing
  written to your projects, ever) and the preference reaches the model
  as a one-line `<system-reminder>` that rides your NEXT message in THAT
  chat — live, one turn, no extra send, ~80 tokens once per change.
  After /compact the session is re-told automatically. The UI says
  exactly when it applies: "applies from your next message". Upgrading
  strips P29's old managed block from your AGENTS.md files (user content
  byte-preserved, logged in Diagnostics).
- **the cost hint stopped clipping** — the price line above the input
  was right-aligned and hard-clipped with no ellipsis, so it read like
  it was escaping the UI and spilling past the edge. It now left-aligns
  with the input well, the format is shorter
  (`≈ 2k new · next $0.0500 · ctx 48k`), a length-bound test keeps the
  worst case inside a 360dp screen, and the ≥50% nudge names the actual
  button: `/compact saves`. Σ pill untouched.
- **delete projects for real** — long-press a card → **Delete project…**:
  a confirm dialog that spells out the exact path and the
  irreversibility, then the folder and every file inside are gone and
  the card leaves the deck. The guards are the feature: roots, mount
  points, /sdcard, the app's own dir (and any ancestor of it) are
  refused by a pure, tested checker; oversized trees abort BEFORE
  touching a file; symlinks are unlinked, never followed; and deleting
  the project the server is currently serving stops the server first.
  ("Remove card" is still there and still only unpins.)
- **feel** — the deck's long-press answers the finger with a haptic tick
  before the action sheet pops, and the delete confirm carries its own
  tick on "Delete forever".

## What's in v0.29.0 (P29 — no more double-open, a real photo tray, a price tag)

- **the model picker can't double-open** — the double-open was silence: a
  tap started the catalog fetch with no feedback, so the next tap opened
  a second dialog. Three layers now: an in-flight gate (a tap during the
  load pops the chip and queues NOTHING), instant open from the cached
  catalog with a background refresh that updates the OPEN sheet in place,
  and a single-dialog guard in the sheet builder. The chip pulses
  "loading models…" so silence is never the feedback.
- **photos like a real chat app** — the ◉ chip multi-selects. Every
  picked photo becomes a 64dp thumb with an ✕ in a tray above the
  composer (add/remove with a haptic tick), and send ships ONE message
  carrying every image as file parts — the agent sees them together, not
  as N separate context trips. Server refuses pixels? The free vision
  model describes EACH photo and the joined descriptions feed the agent.
  Cap 6 per message; the composer text is the caption.
- **the cost tag** — a quiet mono line above the input prices the NEXT
  send live as you type: `≈ 2k new · next ≈ $0.0500 · ctx 48k`. Tokens
  are estimated (ASCII ~4 chars/token, CJK ~1/char, images pixels/750
  from the REAL decoded dims); the cost is the honest worst case (the
  model re-reads the whole window; caching can only shrink the bill);
  free models say "free model"; at ≥50% window it nudges
  "compact to pay less". The Σ pill and the $ meter are untouched.
- **/compact** — ⌘ palette → "Compact context (save tokens)" and a
  ◈ Compact button in the Σ popover. POSTs `/session/{id}/summarize`
  (route verified in the bundled binary's OpenAPI): the summary lands as
  a normal streamed message, the window drains, history stays in
  Sessions.
- **terse replies (the token saver)** — the web-researched community
  presets ("i-have-adhd", caveman: 40-65% fewer OUTPUT tokens) shipped
  natively: ⌘ → "Turn ON terse replies (token saver)" writes a managed
  block into the project's AGENTS.md — act first, no pleasantries, code
  speaks, no summaries of the summary. Your own AGENTS.md content is
  preserved byte-for-byte; OFF removes only our block.
- **feel** — haptic ticks on the commit-y actions (send, chips, vision,
  sessions, Σ, suggestions, attach, permission buttons) independent of
  the animations toggle, and the input well gained the 1dp hairline every
  other raised element already had — the one naked element in the design
  language was the one element that still felt off.

## What's in v0.28.0 (P28 — the P27 field report, fixed)

P27 shipped the live card, the resume-current catch-up, AMOLED and the
tappable mentions. The field report on P28's plate: tapped file links
doing nothing (the existence detection was perfect — the
TAP was dead), the thinking dots drifting into the middle of the
transcript instead of sitting right above the chat box, and a fair
question about big-file glitches. All three are fixed, plus two
lightness wins:

- **the file links actually open now** — a ClickableSpan only ever fires
  through a movement method, and the transcript rows (selectable text,
  so copy-a-response keeps working) have none attached: P27 rendered the
  accent + underline beautifully and the tap fell through to the row and
  died. Taps are now routed by hit-testing the span array at the touch
  point — a genuine tap opens the Files viewer, a scroll drag across a
  link never does, selection and long-press copy keep their native
  handling, and glyph-boundary rounding (±1 offset + equal-x runs) can't
  strand a tap one character away from its link.
- **the thinking dots live above the composer again** — they were
  parented at runtime via `scroll.getParent()` + `indexOfChild(permSlot)`;
  the P27 transcript FrameLayout changed scroll's parent, the lookup
  returned −1 and the dots became a floating overlay INSIDE the
  transcript. The slot is now declared in the layout itself — it cannot
  drift again (and a test pins where it lives).
- **the peek is big-file-proof** — the cap was already 11 lines; what
  could stutter was the WORK behind it: every debounced fs batch re-read
  and re-split up to 2 MB of a streaming file. Now: a (len, mtime) memo
  skips unchanged files entirely, a streamed append (no edit-tool
  locator) reads only the last 8 KB with honest line numbers, full reads
  are hard-capped at 2 MB even if the file grows mid-read, and the line
  the agent is editing is HIGHLIGHTED in the accent color — "shows what
  the AI is currently editing" is now literally true at a glance.
- **faster cold boot** — the boot thread used to read + SHA-256 the whole
  ~175 MB binary on every app launch before the server could spawn. The
  hash is memoized by (len, mtime) now: one stat instead of 175 MB of
  I/O; the value restamps itself when the binary actually changes, so
  Diagnostics still shows the real fingerprint.
- **the opencode binary and the sandbox stay as P27 shipped them** — the
  server binary is upstream (bun-compiled; nothing safe to strip inside,
  shipped once, gzipped, never duplicated) and the curated rootfs trim
  (~50+ MB at install) plus the post-boot cache hygiene (~108 MB/session)
  landed in P27. P28's lightness wins are the ones the app itself was
  wasting: fewer full-file reads under streaming, no cold-boot hashing.

## What's in v0.26.0 (P26 — the evergreen release)

The field verdict on P25: stable and able to handle long runs — with a
short list. All of it fixed here:

- **the live edit tree is actually visible now** — P25 inserted it as a
  transcript row at run start, and every tool/text row that streamed in
  afterwards landed BELOW it, so autoscroll buried the tree above the
  fold within seconds (the field: "the files the AI edited don't show up
  in chat"). The tree is now a PINNED FOOTER above the composer: always
  on screen while the agent works, in nothing's way, gone the moment the
  run settles — files stay in the project file manager, as before.
- **the flashing live symbol is gone** — the pulsing ● and the
  hot-driven expand/collapse strobe were replaced by a static dot and a
  card that stays expanded (a stable tree, not a strobe).
- **back walks backward instead of dumping you on the launcher** — Files
  now goes UP one directory per back press (project root → leaves), and
  chat's system back mirrors its ‹ button (chat → deck).
- **the chat updates every time you come back** — the stale screen had
  two roots: re-opening the displayed session SWAPPED its transcript for
  a fresh empty one and re-rendered from a replay that could race the
  sandbox boot and die silently (the "loading…" screen until the next
  bounce); and a failed re-pull had no retry. Now resume always upserts
  in place (never wipes), and a pull that fires before the server
  answers arms a retry that runs the moment the server flips healthy —
  event-driven, zero polling.
- **catalog models are selectable** — the hard refusal was by
  design (the free list rotates), but it read like a bug.
  A dim "· catalog" row now taps through: the run tries it, and if the
  server truly can't serve it, the model-not-found self-heal clears the
  pick and re-sends with the server default — one honest note, no dead
  end, no double token burn.
- **built to run for a month — or a year** — the indefinite-run audit
  capped every growth path: per-message token/cost bookkeeping is now a
  capped LRU whose totals move by delta (the Σ/$ pill reads are O(1)
  forever), the pid-less part counter no longer grows one entry per
  part, edit-focus snippets and paint-fault maps carry hard caps, and a
  12,000-part soak test proves rows/memory/sums stay inside their walls
  with exact totals on day 300 as on minute one. Under it all the
  existing self-healing chain stands: SSE auto-reconnect, server
  supervisor with backoff, orphan sweep, heartbeat, crash/incident
  capture, run-state recovery.
:- **144 JVM tests green** (10 new: sums-vs-eviction, delta corrections,
  counter gating, focus-map cap, replay retry flag, try-anyway rule,
  forced-pick prefs round-trip, project-switch reset, the soak).

## What was in v0.25.0 (P25 — runs outlive the chat)

A new RunHub (run engine) owned the transcript, busy state, send
orchestration, the SSE consumption and the live-edit watcher for the
whole process lifetime — the chat became a pure view (bind on resume,
unbind on pause), re-entering mid-run re-PULLed the session from the
server API through the same upsert pipeline (never re-POSTed, never
restarted a healthy stream), only the stop button aborted, and a
swipe-kill mid-run recovered on next launch via a persisted run-state
file. The Σ pill became a CONTEXT DEPTH meter ("48k / 200k · 24%"),
the $ meter untouched; the edit shower became a compact live tree and
the peek live-updated during runs. The suite caught a real dormant bug:
the run-time model-not-found matcher checked the wrong token, so that
self-heal trigger could never fire.

## What was in v0.22.0 (P22 — the native-layer audit)

The ask was blunt: "are you sure the native code is tested? most problems
are coming from there." Fair — so this release EXECUTED the native layer
and fixed what the execution caught. Nothing here is reasoned about;
every claim was run.

- **the real binaries were executed for the first time** — the bundled
  BusyBox v1.36.1 ran under ARM64 emulation on the build rig: all 305
  applets listed, every shim-critical command pattern exercised (sed/awk
  pipelines, tar/gzip round-trips, shell semantics the agent's bash tool
  depends on) — 100% green. The full applet list now ships as a test
  fixture, and the JVM suite pins the hardcoded fallback list against it.
- **caught: a dead `patch` command** — the fallback applet list included
  `patch`, which this busybox build does not have ("patch: applet not
  found"). Removed, and one-time flag bumped so installs that ran the
  fallback drop the dead symlink.
- **caught: dangling hardlinks in every Debian install** — the real
  debian:bookworm arm64 docker layer (the exact 48 MB blob the app
  downloads, digest-verified) was extracted with the app's own extractor:
  `usr/bin/perl5.36.0` and `usr/bin/uncompress` landed as DANGLING links
  (hardlink targets are archive-root-relative; the extractor resolved
  them against the link's directory). Fixed; re-extraction now matches
  the ground truth byte-for-byte (5237 files, 639 links, 0 dangling,
  0 escapes).
- **caught: a latent pax-header parser bug** — the substring search for
  `path=` also matched inside `linkpath=`, so a pax header ordered
  linkpath-before-path (legal; docker layer writers use map iteration)
  would extract the file under the LINK TARGET's name. Now record-exact.
- **proot toolkit wiring verified** — ELF-level audit of db_proot/loader/
  talloc/shmem: DT_NEEDED deps (libtalloc.so.2, libandroid-shmem.so,
  bionic libc/liblog) all resolve from the app's lib dir via
  LD_LIBRARY_PATH; SONAMEs match the install layout exactly.
- **the sandbox proxy can no longer pile up threads** — the DNS-bridge
  proxy spawned an unbounded thread per connection; now capped at 64
  concurrent (far above any real apt/git/pip workload) with the surplus
  refused, and a live-connection counter for Diagnostics.
- **send double-tap latch** — session setup + model validation run real
  network I/O before the busy flag sets, so a fast double-tap on send
  could queue two identical runs (doubled tokens). A pre-busy latch now
  collapses them; released on every exit path.
- **the Debian launcher stops rewriting itself per spawn** — the comment
  claimed write-if-different; the code always rewrote + spawned a chmod
  process. Now it actually compares and skips.
- **tested before ship, again** — 81 JVM tests green (8 new P22 pins:
  hardlink normalization, pax both orders, exact-key matching, GNU
  long-link regression, CORE_APPLETS vs the real 305-applet list).

## What's in v0.21.0 (P21 — the stable one)

- **the keyboard stays in the chat box** — the auto-scroll used
  `ScrollView.fullScroll()`, which runs a FOCUS SEARCH and could move
  keyboard focus into the selectable message rows while the agent
  streamed (every 24 ms) — the IME kept detaching from the input.
  Scrolling is now focus-free at all three call sites, and a guard
  restores focus to the chat box if any row rebuild ever takes it.
- **exit forensics** — Diagnostics → "last exits — why Android stopped
  the app": the system's own ApplicationExitInfo records, naming the
  process killer (LOW MEMORY / ANR / NATIVE crash / signal / freezer)
  retroactively — the evidence that was missing for the P19/P20 field
  deaths. Reason constants pinned against the API-34 android.jar.
- **send-crash hardening** — resume replays no longer re-decode every
  image (up to 12 MB of base64 → bytes → bitmap per image per return
  was an LMKD invitation); vision images reuse the cached decode; a
  synthetic trailing message can no longer settle the chat.
- **tested before ship** — a REAL opencode v1.18.25 server ran on the
  rig, a real free model completed a turn, and the captured
  `/session/{id}/message` + 176 SSE events replay through the app's
  settle/replay logic in the JVM suite: **73 tests green** (fixtures
  shipped in the kit). Part-id keying + `time.completed` verified
  against reality.

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
  (the field report: chat and sandbox dying on background, cold boot
  again), the
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
  App.java                  crash capture (last-crash.txt), P30 AGENTS.md
                            migration (strips the app's old managed block)
  MainActivity.java         boot screen: unpack → server → chat     (P7)
  HomeActivity.java         the project deck: cards, dir picker,
                            long-press menu incl. Delete project    (P8/P30)
  ProjectDelete.java        pure guarded recursive delete: path guards,
                            count-first abort, symlink-safe         (P30)
  ChatActivity.java         the whole UI: transcript, ⌘ palette,
                            Build/Plan chip, collapsed reasoning +
                            tool cards, permission card, model and
                            session sheets, export                  (P7)
  RunHub.java               run engine: sends, SSE, transcripts,
                            live style injection (<system-reminder>) (P25/P30)
  TerseMode.java            the terse token-saver: preference + live
                            note + P29 managed-block strip (P29→P30)
  CostMath.java             pure next-send pricing (the hint line)   (P29)
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
| P8 project deck, per-project sandboxes, motion design | shipped |
| P9–P24 streaming polish, self-healing, native audit, flush isolation | shipped |
| P25 runs outlive the chat (RunHub, context-depth pill, live tree) | shipped |
| P26 evergreen: pinned live tree, back navigation, resume re-sync, catalog try-anyway, month/year caps | shipped |
| P27 stable taps + resume-current + curated rootfs + AMOLED design system + tappable file mentions | shipped |
| P28 the P27 field report: tappable mentions, dots above composer, big-file-proof peek, faster boot | shipped |
| P29 model-sheet double-open, photo tray, cost prediction, /compact, terse v1, feel pass | shipped |
| P31 parallel chats, model favorites, credit limit, interactive canvas, six themes, auto-hibernate | shipped |
| P30 live setting injection (<system-reminder>), cost-hint clipping, long-press project delete | shipped |
| P32 the final polish: theme crash fixed, whole-app palette sync, frozen colors retired, swatch picker | shipped |
| P33 the final version: instant themes, Graphite default, keys reach the sandbox, honest picker, project sheet | shipped |
| P34 one surface for every box (41 framework boxes → Sheets), back always catches you, Settings ESSENTIALS top zone, Pixel-style credit editor | shipped |
| P35 the agent's eyes: localhost render endpoint (the browser the agent can use), write → render-check → fix → present loop, quiet ✓ chips | shipped |
| P36 the icon follows the theme: one launcher alias per palette, exactly-one-enabled with self-heal, fresh-install boot fix | shipped |
| P37 empty chat gaps gone, the environment map, plain git push, single error cross | **current** |
| Next: P38 | reserved |

## Credits

**[@turanmertkaraca-bit](https://github.com/turanmertkaraca-bit) — Founder & Project Lead**

Ran development end to end: spec'd every feature, called every design
decision, tested every build in the field, and shipped 34 releases
(P1 → P37).

Developed with AI assistance under their direction.
