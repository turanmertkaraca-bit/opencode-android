# opencode Android payload — rebuild with `--bytecode`

This directory is a self-contained recipe to rebuild the opencode agent binary
that the app ships, **with Bun's `--bytecode` startup cache**, and repackage it
as the payload (`oc_pkg.bin`).

## Why

The shipped binary is opencode v1.18.25 compiled by **Bun v1.4.0 (Android
arm64)**. It was captured from an existing install, not built in this project.
`--bytecode` precompiles the JS so startup skips parse/compile — the one
remaining startup lever.

## Why it can't run inside the app's proot guest

Bun's isolated install + global cache breaks `@babel/core`'s `gensync` require
in that environment (`_gensync() is not a function`). Upstream builds opencode
with Bun on normal Linux daily, so run this on **a normal host or CI**, not in
the phone's Debian/Alpine/BusyBox guest.

## Run it

On any Linux machine with [Bun 1.4.0](https://bun.sh):

```bash
bash opencode-android-build/build.sh
# -> opencode-android-build/opencode-linux-arm64-android-bytecode.tar.gz
```

Or use the GitHub Actions workflow (`opencode-payload.yml`, drop it in
`.github/workflows/` and run it from the Actions tab).

## What it patches (and why)

1. **Deps** — `bun install --ignore-scripts` (the native `node-gyp` bits are
   not needed; the bundle uses the WASM tree-sitter).
2. **`@opentui/core-linux-arm64/libopentui.so`** is replaced with
   `libopentui-android.so` in this directory — the **bionic** build carved out
   of the currently shipped binary. Upstream ships no Android opentui.
3. **`@opentui/core` chunks** get two string replacements so
   `process.platform === "android"` resolves as linux:
   - `platform: process.platform,` → `platform: process.platform === "android" ? "linux" : process.platform,`
   - `if (process.platform === "linux") {` → `if (process.platform === "linux" || process.platform === "android") {`
4. **`packages/opencode/script/build.ts`** gets an `--android` target and
   `bytecode: true`.

Notes:
- `fff` is disabled at runtime by the app (`OPENCODE_DISABLE_FFF=1`), so its
  missing Android lib is a non-issue.
- `bun-pty`'s prebuilt `librust_pty_arm64.so` is glibc; `serve` does not create
  a PTY, so it is not loaded. If you later need a PTY on Android, carve a
  bionic `librust_pty` the same way and swap it in.
- The result is **untested** — A/B it against the current binary on-device and
  keep whichever boots faster.

## Verify on-device

Both binaries are the same app payload. To A/B:
1. Build the bytecode payload above.
2. Replace `app/src/main/assets/oc_pkg.bin`, bump `EXPECT_SHA` in
   `scripts/build_apk.sh`, rebuild the APK.
3. Time cold boot (Diagnostics → last exits / incident log) with each.

## Provenance

- opencode source: https://github.com/sst/opencode tag `v1.18.25`
- Bun: 1.4.0 (`bun-linux-aarch64-android` target)
- Shipped binary sha256: `13bea2fdd4a4f4c7e2761924c81b8677a1e353179f5c3d2ddbe3eb67ea60415d`
