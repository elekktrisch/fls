#!/usr/bin/env bash

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


CACHE="$PROJECT_DIR/.angular/cache"
if [ -L "$CACHE" ]; then
  rm "$CACHE"
  echo "✓ removed sandbox .angular/cache symlink"
fi

echo "✓ Windows mode restored"
