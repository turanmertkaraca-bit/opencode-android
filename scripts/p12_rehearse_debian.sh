#!/usr/bin/env bash
# ============================================================================
# p12_rehearse_debian.sh — validate the P12 Debian/proot design on x86_64
#
# Rehearses EXACTLY what the app will do on-device:
#   1. extract the docker-library debian:bookworm rootfs tar.gz
#   2. configure resolv.conf decoy + apt sources (http)
#   3. run proot with the app's flag set (fake root, binds, resolv bind)
#   4. all guest network traffic goes through miniproxy.py (the Java
#      ProxyServer twin) via http_proxy/https_proxy env
#   5. apt-get update + install git/ca-certificates + git clone the
#      opencode-android repo  → the "analyse the repo" bootstrap
#   6. per-project bind check: only the project dir is visible at its
#      real path; /sdcard is NOT
# ============================================================================
set -uo pipefail
cd /home/z/my-project/p12-assets
ROOT=rehearse
PROOT="LD_LIBRARY_PATH=$PWD/ex-talloc-amd64/usr/lib/x86_64-linux-gnu $PWD/ex-proot-amd64/usr/bin/proot"
say() { echo -e "\n==> $*"; }

# ---- 0. proxy ---------------------------------------------------------------
python3 miniproxy.py 18899 2>"$ROOT-proxy.log" &
PROXYPID=$!
sleep 0.4
say "mini proxy up on :18899 (pid $PROXYPID)"

# ---- 1. extract rootfs ------------------------------------------------------
if [ ! -d "$ROOT/rootfs/usr/bin" ]; then
  say "extracting debian rootfs (~150 MB)…"
  mkdir -p "$ROOT/rootfs"
  tar -xzf debian-amd64-rootfs.tar.gz -C "$ROOT/rootfs" && echo "extract ok"
fi
say "rootfs sanity: $(cat $ROOT/rootfs/etc/os-release | head -1)"

# ---- 2. configure -----------------------------------------------------------
# apt sources: force http (no ca-certificates yet in minimal image)
cat > "$ROOT/rootfs/etc/apt/sources.list" <<'EOF'
deb http://deb.debian.org/debian bookworm main
deb http://deb.debian.org/debian bookworm-updates main
deb http://security.debian.org/debian-security bookworm-security main
EOF
rm -f "$ROOT/rootfs/etc/apt/sources.list.d/debian.sources"
printf 'nameserver 127.0.0.1\n# DNS goes through the in-app proxy (http_proxy env)\n' > "$ROOT/rootfs/etc/resolv.conf"
mkdir -p "$ROOT/rootfs/root/project"

# ---- 3..6 proot run ---------------------------------------------------------
# EXACT app flag pattern (paths swapped for the rehearsal):
#   proot -r ROOTFS -0 -w / \
#     -b /dev -b /proc -b /sys \
#     -b $PWD/rootfs/etc/resolv.conf:/etc/resolv.conf \
#     -b $PWD/rootfs/root/project:/root/project  (per-project bind, same path)
#     -- /bin/bash -c CMD
run() {
  env -i \
    http_proxy=http://127.0.0.1:18899 \
    https_proxy=http://127.0.0.1:18899 \
    HTTP_PROXY=http://127.0.0.1:18899 \
    HTTPS_PROXY=http://127.0.0.1:18899 \
    no_proxy=127.0.0.1,localhost NO_PROXY=127.0.0.1,localhost \
    DEBIAN_FRONTEND=noninteractive HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm \
    $PROOT \
      --rootfs="$PWD/$ROOT/rootfs" --cwd=/ -0 \
      --kill-on-exit \
      --bind=/dev --bind=/proc --bind=/sys \
      --bind="$PWD/$ROOT/rootfs/etc/resolv.conf:/etc/resolv.conf" \
      --bind="$PWD/$ROOT/rootfs/root/project:/root/project" \
      /bin/bash -c "$1"
}

say "probe: identity + apt update through the proxy"
run 'echo "user=$(id -u) arch=$(uname -m)"; cat /etc/resolv.conf' || echo "PROBE FAILED rc=$?"

say "apt-get update (via proxy)"
run 'apt-get update -o Acquire::Retries=1 2>&1 | tail -4' || echo "APT UPDATE rc=$?"

say "apt-get install git + ca-certificates"
run 'apt-get install -y --no-install-recommends git ca-certificates 2>&1 | tail -3' || echo "APT INSTALL rc=$?"

say "git clone the opencode-android repo (public, no token)"
run 'git clone --depth 1 https://github.com/turanmertkaraca-bit/opencode-android /root/project/opencode-android 2>&1 | tail -2; ls /root/project/opencode-android | head -5' || echo "CLONE rc=$?"

say "per-project isolation: /root/project visible, /home/other NOT bound"
run 'ls /root/project >/dev/null && echo "project bind OK"; [ ! -d /root/secret ] && echo "unbound path absent OK"'

say "git identity for commits"
run 'git config --global user.name "opencode-android agent"; git config --global user.email "agent@localhost"; echo git identity ok'

kill $PROXYPID 2>/dev/null
say "REHEARSAL DONE"
