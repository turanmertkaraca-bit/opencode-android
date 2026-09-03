#!/usr/bin/env bash
# ============================================================================
# p0_rehearse_x86.sh — validate p0-probe-termux.sh mechanics on x86_64 Linux
# before it ships to the phone. Reuses the EXACT proot invocation, dual-bind,
# env-curation, shim generation and test flow from the kit script (with the
# only difference being arch + no --link2symlink, which is Termux-specific).
# ============================================================================
set -u
cd /tmp || exit 1

PASS=0; FAIL=0
ok()  { echo "PASS $*"; PASS=$((PASS+1)); }
bad() { echo "FAIL $*"; FAIL=$((FAIL+1)); }
say() { echo "==> $*"; }

ROOTFS=/tmp/p0rootfs
PROOT_BIN=/tmp/proot
P0WS=/tmp/p0-workspace
EXTRA_FLAGS=""

rm -rf "$P0WS"; mkdir -p "$P0WS"

ENV_ARGS="HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin DEBIAN_FRONTEND=noninteractive"

sbx() { # sbx <guest-cwd> <command...>
  local wd="$1"; shift
  "$PROOT_BIN" $EXTRA_FLAGS -0 --kill-on-exit \
    -r "$ROOTFS" \
    -b /dev -b /proc -b /sys \
    -b "$P0WS:/workspace" \
    -b "$P0WS:$P0WS" \
    -w "$wd" \
    /usr/bin/env -i $ENV_ARGS "$@"
}

# --- 1. boot -----------------------------------------------------------------
say "1. sandbox boot (uname/os-release/cwd)"
out="$(sbx /workspace /bin/bash -c 'uname -m && head -1 /etc/os-release && pwd' 2>&1)"
echo "$out" | sed 's/^/   | /'
if echo "$out" | grep -q "Debian GNU/Linux" && echo "$out" | grep -qx "/workspace"; then
  ok "sandbox boots, cwd=/workspace honored"
else
  bad "sandbox boot"
fi

# --- 2. apt under proot -0 -----------------------------------------------------
say "2. apt-get update + install git (seed mechanics, small subset)"
if sbx / /usr/bin/apt-get update >/tmp/apt.log 2>&1; then
  ok "apt-get update"
else
  bad "apt-get update (see /tmp/apt.log)"; tail -5 /tmp/apt.log | sed 's/^/   | /'
fi
if sbx / /usr/bin/apt-get install -y --no-install-recommends git python3 >/tmp/apti.log 2>&1; then
  ok "apt-get install git python3 (--no-install-recommends)"
else
  bad "apt-get install (see /tmp/apti.log)"; tail -8 /tmp/apti.log | sed 's/^/   | /'
fi

# --- 3. git e2e + write-through ------------------------------------------------
say "3. git init/add/commit inside /workspace, verify on host"
if sbx /workspace /usr/bin/git init -b main >/dev/null 2>&1 \
   && sbx /workspace /usr/bin/git config user.email p0@probe.local \
   && sbx /workspace /usr/bin/git config user.name "P0 Probe" \
   && printf '# P0 workspace\n' > "$P0WS/README.md" \
   && sbx /workspace /usr/bin/git add -A \
   && sbx /workspace /usr/bin/git commit -qm "p0: initial commit"; then
  if [ -d "$P0WS/.git" ]; then
    ok "git commit write-through visible on host"
  else
    bad ".git not visible on host"
  fi
else
  bad "git e2e inside sandbox"
fi

# --- 4. network from inside sandbox ---------------------------------------------
say "4. git ls-remote over https from inside sandbox"
if sbx /workspace /usr/bin/git ls-remote https://github.com/octocat/Hello-World.git HEAD >/dev/null 2>&1; then
  ok "github reachable from inside sandbox"
else
  bad "git ls-remote from sandbox"
fi

# --- 5. shims (same generator logic as the kit script, host shebang) -------------
say "5. PATH shims: bash + git routing"
P0SHIMS_ALL=/tmp/p0-shims-all
P0SHIMS_GIT=/tmp/p0-shims-git
rm -rf "$P0SHIMS_ALL" "$P0SHIMS_GIT"; mkdir -p "$P0SHIMS_ALL" "$P0SHIMS_GIT"
SHIM_LOG=/tmp/p0-shim.log; : > "$SHIM_LOG"

write_shim() { # write_shim <dir> <name> <guest-binary>
  local d="$1" n="$2" g="$3"
  cat > "$d/$n" <<EOF
#!/bin/sh
[ -n "\${OC_PROJ_DIR:-}" ] || { echo "p0 shim: OC_PROJ_DIR not set" >&2; exit 127; }
[ -n "\${OC_ROOTFS:-}" ] || { echo "p0 shim: OC_ROOTFS not set" >&2; exit 127; }
REL="\${PWD#"\$OC_PROJ_DIR"}"
[ "\$REL" = "\$PWD" ] && REL=""
printf '[%s] shim $n %s\n' "\$(date +%T)" "\$*" >> "\${OC_SHIM_LOG:-/dev/null}"
exec "\${PROOT_BIN:-proot}" \${OC_EXTRA_FLAGS:-} -0 --kill-on-exit \\
  -r "\$OC_ROOTFS" \\
  -b /dev -b /proc -b /sys \\
  -b "\$OC_PROJ_DIR:/workspace" \\
  -b "\$OC_PROJ_DIR:\$OC_PROJ_DIR" \\
  -w "/workspace\$REL" \\
  /usr/bin/env -i HOME=/root TERM="\${TERM:-xterm-256color}" LANG=C.UTF-8 \\
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\
  \${OC_PASSTHROUGH:-} \\
  $g "\$@"
EOF
  chmod +x "$d/$n"
}
write_shim "$P0SHIMS_ALL" bash /bin/bash
write_shim "$P0SHIMS_ALL" sh   /bin/sh
write_shim "$P0SHIMS_ALL" git  /usr/bin/git
write_shim "$P0SHIMS_GIT" git  /usr/bin/git

export OC_ROOTFS="$ROOTFS" OC_PROJ_DIR="$P0WS" OC_SHIM_LOG="$SHIM_LOG"
export PROOT_BIN OC_EXTRA_FLAGS=""
export OC_PASSTHROUGH=""

if PATH="$P0SHIMS_ALL:$PATH" bash -c 'cat /etc/os-release' 2>/dev/null | grep -qi debian; then
  ok "bash shim routes into sandbox"
else
  bad "bash shim"
fi
if PATH="$P0SHIMS_GIT:$PATH" git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  ok "git shim routes into sandbox repo"
else
  bad "git shim"
fi
# relative-cwd mapping: run git from a subdirectory of the project
mkdir -p "$P0WS/sub"
if (cd "$P0WS/sub" && PATH="$P0SHIMS_GIT:$PATH" git status --short --branch >/dev/null 2>&1); then
  ok "git shim maps project subdirectory cwd correctly"
else
  bad "git shim subdirectory cwd"
fi
if [ -s "$SHIM_LOG" ]; then
  ok "shim log captured invocations ($(wc -l < "$SHIM_LOG") lines)"
  head -3 "$SHIM_LOG" | sed 's/^/   | /'
else
  bad "shim log empty"
fi

# --- 6. env -i leakage check ------------------------------------------------
say "6. env curation (no host leakage)"
out="$(sbx /workspace /bin/bash -c 'env | wc -l && env | grep -c SECRET || true' 2>&1)"
if [ "$(printf '%s' "$out" | head -1)" -le 12 ]; then
  ok "sandbox env is curated ($(printf '%s' "$out" | head -1) vars)"
else
  bad "env leakage: $out"
fi

echo
echo "REHEARSAL RESULT: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && echo "MECHANICS GREEN - kit script invocation pattern is sound"
exit 0
