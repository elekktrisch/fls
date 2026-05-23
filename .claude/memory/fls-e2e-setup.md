---
name: fls-e2e-setup
description: Stack-up quirks for the FLS Playwright suite. The repo docs cover the happy path; this captures the booby traps you only hit once.
metadata: 
  node_type: memory
  type: project
  originSessionId: 3ab1d4cc-7858-4140-9a9b-5c2b95219278
---

Quickstart and write-rules are in the repo (`e2e/README.md`,
`e2e/TEST_WRITING.md`, `e2e/PLAN.md`). The things that are NOT obvious
from those:

**Node split.** `webpack-dev-server` (in `/tmp/flsweb-build/`) needs
Node 8 (legacy webpack 1 + node-gyp/microtime issue). Playwright needs
Node 20. Invoke Playwright explicitly as `env PATH="/usr/bin:/bin"
/usr/bin/npx playwright test` to bypass the nvm-loaded Node 8 in PATH.

**Mono server start.** Always export `FLS_LISTEN_URL="http://*:25567/"`
before starting `FLS.Server.Console.exe` — otherwise Mono binds
IPv6-only and the webpack proxy 502s. See `[[fls-server-ipv6-binding]]`.

**`yarn start` is a trap.** It re-runs the install and chokes on
`microtime`'s node-gyp. Once `/tmp/flsweb-build/node_modules` is
populated, run `./node_modules/.bin/webpack-dev-server` directly.

**Seed cache.** `e2e/scripts/seed.sh` hashes every `.sql` file in
`flsserver/database/FLSTest/` and BACKUP/RESTOREs a per-hash `.bak`
(~5s vs ~30s). Touching any seed `.sql` invalidates the cache and
forces a real reseed. `FLS_SEED_FORCE=1` bypasses the cache.

**SQL container name on the compose stack.** docker-compose uses
`fls-e2e-mssql-1`, not `fls-mssql`. The freshDb fixtures set
`FLS_MSSQL_CONTAINER` accordingly.

**Output dirs off the Windows mount.** `/c/...` causes `EIO: rmdir` on
test-result cleanup. Config points `outputDir` + html reporter to
`/tmp/fls-e2e-results` and `/tmp/fls-e2e-report`. Don't change this.

**Spinner wait.** `networkidle` is useless under webpack-dev-server's
HMR websocket. `gotoRoute()` waits for `[data-testid="busy-indicator"]`
to clear instead — don't reach for `waitForLoadState('networkidle')`.

**Seeded login.** `testclubadmin` / `s` (NOT real production creds).
`othertestadmin` / `s` is the second-club admin for multi-tenancy tests.

Related: [[fls-server-ipv6-binding]] [[fls-e2e-state]]
