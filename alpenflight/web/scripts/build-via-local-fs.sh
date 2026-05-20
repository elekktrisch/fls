#!/usr/bin/env bash
# Sandbox-only workaround: the Windows-mounted host FS at /c/Users/... cannot serve
# concurrent file reads to esbuild during a full ng build without deadlocking.
# This script rsyncs sources to a Linux-local path, builds there, then rsyncs the dist back.
#
# Real machines do NOT need this — `pnpm build` runs in-place fine on native Linux/Mac/Windows.
# Detect: if HOST_LOCAL_BUILD=1 or the script senses /c/Users, use this. Otherwise build in-place.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_BUILD_DIR="${LOCAL_BUILD_DIR:-/home/agent/fls-build/web-build}"
DIST_OUT="$PROJECT_DIR/dist"

CONFIG="${1:-production}"

mkdir -p "$LOCAL_BUILD_DIR"

# Mirror sources to local FS (exclude node_modules — already a symlink at the destination)
rsync -a --delete \
  --exclude='node_modules' \
  --exclude='dist' \
  --exclude='.angular' \
  --exclude='build.out' --exclude='build.err' \
  "$PROJECT_DIR/" "$LOCAL_BUILD_DIR/"

# Reuse the project's node_modules symlink target. `-fn` overwrites a stale
# symlink (e.g. one left behind by the pre-rename next-web path) instead of
# silently keeping a broken pointer.
ln -sfn /home/agent/fls-build/alpenflight/node_modules "$LOCAL_BUILD_DIR/node_modules"

cd "$LOCAL_BUILD_DIR"
node node_modules/@angular/cli/bin/ng build --configuration="$CONFIG"

# Mirror dist back to project so CI tooling / downstream scripts see it
mkdir -p "$DIST_OUT"
rsync -a --delete "$LOCAL_BUILD_DIR/dist/" "$DIST_OUT/"

echo "build complete — dist at $DIST_OUT"
