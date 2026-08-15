#!/usr/bin/env bash

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SANDBOX_NM="${SANDBOX_NM:-/home/agent/fls-build/alpenflight/node_modules}"
SANDBOX_CACHE="${SANDBOX_CACHE:-/home/agent/fls-build/alpenflight/.angular-cache}"

cd "$PROJECT_DIR"

ln -sfn "$SANDBOX_NM" "$PROJECT_DIR/node_modules_sandbox"

NM="$PROJECT_DIR/node_modules"
if [ -L "$NM" ]; then
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

mkdir -p "$PROJECT_DIR/.angular" "$SANDBOX_CACHE"
CACHE="$PROJECT_DIR/.angular/cache"
if [ -L "$CACHE" ]; then
  ln -sfn "$SANDBOX_CACHE" "$CACHE"
elif [ -e "$CACHE" ]; then
  if ! rm -rf "$CACHE" 2>/dev/null; then
    echo "ERROR: could not remove existing $CACHE. Delete it manually then re-run." >&2
    exit 1
  fi
  ln -sfn "$SANDBOX_CACHE" "$CACHE"
else
  ln -sfn "$SANDBOX_CACHE" "$CACHE"
fi

echo "✓ sandbox setup ready (node_modules → node_modules_sandbox → $SANDBOX_NM; .angular/cache → $SANDBOX_CACHE)"
