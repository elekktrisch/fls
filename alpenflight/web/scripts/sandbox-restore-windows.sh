#!/usr/bin/env bash
# Undoes `sandbox-bootstrap.sh`: removes the sandbox-mode symlinks and
# restores a backed-up Windows-installed `node_modules/` if present.
# Run before switching back to a Windows-native pnpm flow.

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_DIR"

NM="$PROJECT_DIR/node_modules"
BACKUP="$PROJECT_DIR/node_modules.windows"
if [ -L "$NM" ]; then
  rm "$NM"
fi
if [ -d "$BACKUP" ]; then
  mv "$BACKUP" "$NM"
  echo "✓ restored node_modules.windows/ → node_modules/"
else
  echo "(no node_modules.windows/ backup — `pnpm install` to populate)"
fi

# Leave node_modules_sandbox in place (it's just a named symlink — harmless on
# Windows since the target path simply doesn't exist there).

CACHE="$PROJECT_DIR/.angular/cache"
if [ -L "$CACHE" ]; then
  rm "$CACHE"
  echo "✓ removed sandbox .angular/cache symlink"
fi

echo "✓ Windows mode restored"
