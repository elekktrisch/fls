#!/usr/bin/env bash
# Sandbox bootstrap: prepares alpenflight/web for development from inside
# the Linux sandbox while leaving the Windows-native install (if any)
# intact alongside.
#
# What it does (idempotent — safe to re-run):
#   1. Creates `node_modules_sandbox` as a stable symlink to the
#      Linux-local hoisted store at /home/agent/fls-build/alpenflight/node_modules.
#   2. Points `node_modules` at `node_modules_sandbox`. If a real
#      Windows-installed `node_modules/` is in the way, it's backed up
#      to `node_modules.windows/`; restore via `pnpm sandbox:restore-windows`.
#   3. Points `.angular/cache` at /home/agent/fls-build/alpenflight/.angular-cache
#      so the Angular CLI's incremental cache + Vite dep-optimizer never
#      touch the Windows-mounted FS (where `rmdir` of nested dirs
#      intermittently EIOs).
#
# Real Linux / macOS / Windows-native operators DO NOT need this — they
# `pnpm install` normally and use the un-suffixed package.json scripts.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SANDBOX_NM="${SANDBOX_NM:-/home/agent/fls-build/alpenflight/node_modules}"
SANDBOX_CACHE="${SANDBOX_CACHE:-/home/agent/fls-build/alpenflight/.angular-cache}"

cd "$PROJECT_DIR"

# 1. node_modules_sandbox — stable named symlink to the Linux-local store.
ln -sfn "$SANDBOX_NM" "$PROJECT_DIR/node_modules_sandbox"

# 2. node_modules — point at node_modules_sandbox; back up a real
#    Windows-installed directory if present.
NM="$PROJECT_DIR/node_modules"
if [ -L "$NM" ]; then
  # Already a symlink — overwrite to ensure it points at the sandbox via-point.
  ln -sfn node_modules_sandbox "$NM"
elif [ -d "$NM" ]; then
  BACKUP="$PROJECT_DIR/node_modules.windows"
  if [ -e "$BACKUP" ]; then
    echo "ERROR: $BACKUP already exists. Move it out of the way before re-running." >&2
    exit 1
  fi
  echo "Backing up Windows-installed node_modules/ → node_modules.windows/"
  mv "$NM" "$BACKUP"
  ln -sfn node_modules_sandbox "$NM"
else
  ln -sfn node_modules_sandbox "$NM"
fi

# 3. .angular/cache — point at a Linux-local path to dodge the Windows-FS
#    rmdir EIO that bites the Angular CLI cache + Vite dep-optimizer.
mkdir -p "$PROJECT_DIR/.angular" "$SANDBOX_CACHE"
CACHE="$PROJECT_DIR/.angular/cache"
if [ -L "$CACHE" ]; then
  ln -sfn "$SANDBOX_CACHE" "$CACHE"
elif [ -e "$CACHE" ]; then
  # Try to remove; if rmdir fails (FS quirk) bail with a clear message.
  if ! rm -rf "$CACHE" 2>/dev/null; then
    echo "ERROR: could not remove existing $CACHE. Delete it manually then re-run." >&2
    exit 1
  fi
  ln -sfn "$SANDBOX_CACHE" "$CACHE"
else
  ln -sfn "$SANDBOX_CACHE" "$CACHE"
fi

echo "✓ sandbox setup ready (node_modules → node_modules_sandbox → $SANDBOX_NM; .angular/cache → $SANDBOX_CACHE)"
