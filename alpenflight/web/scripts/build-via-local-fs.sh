#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_BUILD_DIR="${LOCAL_BUILD_DIR:-/home/agent/fls-build/web-build}"
DIST_OUT="$PROJECT_DIR/dist"

CONFIG="${1:-production}"

mkdir -p "$LOCAL_BUILD_DIR"

rsync -a --delete \
  --exclude='node_modules' \
  --exclude='dist' \
  --exclude='.angular' \
  --exclude='build.out' --exclude='build.err' \
  "$PROJECT_DIR/" "$LOCAL_BUILD_DIR/"

ln -sfn /home/agent/fls-build/alpenflight/node_modules "$LOCAL_BUILD_DIR/node_modules"

cd "$LOCAL_BUILD_DIR"
node node_modules/@angular/cli/bin/ng build --configuration="$CONFIG"

mkdir -p "$DIST_OUT"
rsync -a --delete "$LOCAL_BUILD_DIR/dist/" "$DIST_OUT/"

echo "build complete — dist at $DIST_OUT"
