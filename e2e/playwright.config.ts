import { defineConfig, devices } from '@playwright/test';

// The two `webServer` entries below assume that:
//
//   1. `bash e2e/scripts/dev-up.sh` has been run (brings up the SQL Server
//      and Mailpit containers under the `fls-e2e` compose project).
//   2. The FLS Web API (Mono console) is either already running on :25567,
//      OR can be started locally with the command in `webServer[0].command`.
//   3. The flsweb webpack-dev-server is either already running on :3000,
//      OR can be started locally with the command in `webServer[1].command`.
//
// `reuseExistingServer: true` is set so a developer who has the stack up
// manually (per TESTING.md Milestones 3 + 5) is not disturbed. Playwright
// will skip the spawn and just wait for the health check to pass.
//
// We poll cheap, unauthenticated endpoints:
//   - `/api/v1/countries` for the FLS API (public, returns 200 + JSON once EF
//     and the DB are wired)
//   - `/` for the dev-server (200 + the SPA HTML)
//
// `timeout: 180_000` gives Mono cold-start enough headroom.
//
// Test layout: specs are grouped by feature category under tests/<category>/.
// Each category is exposed as its own Playwright `project`, so the HTML
// report shows a coloured project badge per test and you can filter with
// `npx playwright test --project=<category>` (e.g. `--project=flights`).
//
// Tests are self-contained-parallel (see TEST_WRITING.md §1): each owns
// its rows via a stable id and pre-cleans, so the read-vs-mutate split is
// no longer needed — everything shares one worker pool at top-level
// `workers: 6`.

const CATEGORIES = [
  'auth',
  'public',
  'flights',
  'planning',
  'reservations',
  'masterdata',
  'accounting',
  'reporting',
  'email',
  'profile',
  'multi-tenant',
  'api',
] as const;

export default defineConfig({
  testDir: './tests',
  // 60s per test: most UI flows finish in 10-15s, but multi-step forms
  // (master-data hydration + ng-table reload + selectize widgets) plus
  // the occasional workflow-job poll need real headroom. 60s leaves room
  // without masking genuine hangs.
  timeout: 60_000,
  expect: { timeout: 5_000 },
  // `workers` is a top-level option in Playwright (TestProject doesn't
  // accept it — silently ignored if set per-project). All projects share
  // this pool. `fullyParallel` + `retries` ARE project-level.
  //
  // The constraint is backend throughput (single Mono + SQL Server). 12
  // caused test timeouts under load; 6 keeps real concurrency without
  // pushing the stack into GC pause + EF-pool contention.
  workers: 6,
  retries: 0,
  // Cap total failures so a mass-regression (server down, schema drift,
  // seed mismatch) doesn't burn 20+ runner-minutes hammering the same
  // broken state across 155 specs × 2 retries. Trade-off: a real wave of
  // failures in an early category (e.g. all `auth` specs) now hides any
  // later-category failures until the auth issue is fixed and re-run.
  // That's the right shape — first 10 failures usually share a root cause
  // and fixing them in order is faster than triaging 70 simultaneous reds.
  // Override with `--max-failures=0` to surface every spec on a local run.
  maxFailures: 10,
  outputDir: '/tmp/fls-e2e-results',
  reporter: [['list'], ['html', { open: 'never', outputFolder: '/tmp/fls-e2e-report' }]],
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1280, height: 800 },
    // Per-aspect timeouts. Each one short-circuits independently so a
    // failure tells us WHICH aspect is slow, not just "test hit 60s".
    //   actionTimeout     — click/fill/check/uncheck/selectOption/etc.
    //   navigationTimeout — page.goto / waitForURL / waitForLoadState
    // expect.timeout (toBeVisible, toHaveCount, …) is separate, set below.
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  webServer: [
    {
      // FLS Web API (Mono console host). The build artifacts and the
      // EntityFramework.SqlServer.dll drop are produced once per machine
      // via TESTING.md Milestone 2; this command just runs the exe.
      command:
        'cd ../flsserver/src/FLS.Server.Console/bin/Debug && FLS_LISTEN_URL="http://*:25567/" mono FLS.Server.Console.exe',
      url: 'http://localhost:25567/api/v1/countries',
      reuseExistingServer: true,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      // flsweb webpack-dev-server (Node 8 via nvm). The source tree is
      // expected to have been copied to /tmp/flsweb-build with the two
      // case-sensitivity sed fixes already applied (TESTING.md Milestone 5).
      //
      // Invoke webpack-dev-server directly rather than `yarn start`.
      // Yarn 1 runs an integrity check before script execution and
      // re-attempts to install dependencies when node_modules / yarn.lock
      // drift apart; that re-install hits the `microtime` optional native
      // dep, which fails to compile under modern Python (collections.MutableSet
      // gone in Python 3.10+). The direct bin invocation skips the check.
      command:
        'cd /tmp/flsweb-build && ./node_modules/.bin/webpack-dev-server --TARGET=DEV --SERVER_URL=http://localhost:25567/',
      url: 'http://localhost:3000/',
      reuseExistingServer: true,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
  // One project per feature category. All share the same browser, viewport,
  // parallelism and retry policy — the project list is purely an
  // organisational lens for the HTML report and CLI filtering.
  projects: CATEGORIES.map((category) => ({
    name: category,
    testDir: `./tests/${category}`,
    fullyParallel: true,
    // retries: 1 absorbs the occasional /Token 500 / page-boot timing
    // flake under load. workers count is set at the top level.
    retries: 1,
    use: { ...devices['Desktop Chrome'] },
  })),
});
