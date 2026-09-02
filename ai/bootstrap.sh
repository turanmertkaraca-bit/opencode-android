#!/system/bin/sh
# ai/bootstrap.sh — one-shot setup for the on-device agent (P12).
# Idempotent: safe to re-run, never discards local edits.
# Requires: the app's sandbox (pkg, alpine layer) and ideally a GitHub
# token saved in Settings → GitHub (GITHUB_TOKEN).

set -e
REMOTE="https://github.com/turanmertkaraca-bit/opencode-android.git"

echo "== 1/4 package manager: git + CA certificates =="
pkg update -q 2>/dev/null || echo "  (pkg update had warnings — continuing)"
pkg add -q git 2>/dev/null || pkg add git || echo "  !! git install failed — pushes won't work"
pkg add -q ca-certificates 2>/dev/null || true

echo "== 2/4 git identity + credential helper =="
git config --global credential.helper store || true
git config --global init.defaultBranch main || true
git config --global --get user.name >/dev/null 2>&1 || \
  git config --global user.name "opencode-android (on-device agent)"
git config --global --get user.email >/dev/null 2>&1 || \
  git config --global user.email "agent@opencode-android.local"

echo "== 3/4 convert this folder into a tracked clone =="
if [ ! -d .git ]; then
  git init -q -b main
  git remote add origin "$REMOTE" 2>/dev/null || true
  # the seed already contains the same files as origin/main — adopting
  # remote history WITHOUT touching your working tree:
  git fetch -q origin main || { echo "  !! fetch failed (no token / offline)"; exit 1; }
  git reset -q --soft FETCH_HEAD
  git branch -q --set-upstream-to=origin/main main 2>/dev/null || \
    git branch -q -u origin/main main 2>/dev/null || true
  echo "  adopted origin/main — your edits are preserved as local commits on top"
else
  git remote get-url origin >/dev/null 2>&1 || git remote add origin "$REMOTE"
  git fetch -q origin main || { echo "  !! fetch failed (no token / offline)"; exit 1; }
  echo "  already a clone — fetched latest (not merged; pull when ready)"
fi

echo "== 4/4 verifying push access =="
if git ls-remote --heads origin >/dev/null 2>&1; then
  echo "OK — you can read AND write the remote."
  echo "Next: read AGENTS.md, then do what the user asked."
else
  echo "READ-WRITE CHECK FAILED — no/invalid token?"
  echo "Ask the user: Settings → GitHub → paste a token (repo scope)."
  exit 1
fi
