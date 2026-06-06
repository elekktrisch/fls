#!/usr/bin/env bash
# Fail-soft Stop hook: reap orphaned alpenflight-pg-test-* Postgres containers
# (+ their volumes) and dangling volumes left by a SIGKILLed test JVM.
#
# The Testcontainers harness (PostgresTestContainerLifecycle) tears its container
# down via a JVM shutdown hook that never fires when a worker's
# `timeout NNN ./gradlew test` SIGKILLs the JVM — so each killed run leaks a
# container + ~1 GB volume, slowly filling the dev box. This sweeps them at
# session end. It MUST never block session end: every step is best-effort and
# the script always exits 0.

set +e

command -v docker >/dev/null 2>&1 || exit 0

# Remove our test containers (`-v` drops their anonymous volumes in the same call).
names="$(docker ps -a --filter 'name=alpenflight-pg-test-' --format '{{.Names}}' 2>/dev/null)"
if [ -n "$names" ]; then
  echo "$names" | xargs -r docker rm -f -v >/dev/null 2>&1
fi

# Reclaim any now-dangling volumes (covers volumes orphaned before the -v reap).
docker volume prune -f >/dev/null 2>&1

exit 0
