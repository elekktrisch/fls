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
// All tests share one FLSTest database. Parallelism therefore has to be
// carefully partitioned: read-only specs can safely interleave across
// workers; mutation specs (anything that POSTs/PUTs/DELETEs against
// flsserver, or relies on a specific DB state) must serialize so they
// don't trample each other or step on freshDb's reseed.
//
// We split the suite into two `projects`:
//   - `read`     read-only specs, fullyParallel + workers 3
//   - `mutate`   mutation specs, serial (workers 1)
//
// Run both via `npx playwright test`; Playwright walks projects in the
// declared order and respects the per-project `workers` override.
const READ_ONLY_SPECS = [
  '01-public.spec.ts',
  '02-authenticated.spec.ts',
  '03-masterdata.spec.ts',
  '11-reservation-scheduler.spec.ts',
  '16-flight-reports-generation.spec.ts',
  '17-custom-report-builder.spec.ts',
  '25-multi-tenant-isolation.spec.ts',
  '33-api-contract.spec.ts',
  'auth.spec.ts',
  'landing.spec.ts',
];

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  // Project-level fullyParallel + workers override these defaults.
  retries: 0,
  outputDir: '/tmp/fls-e2e-results',
  reporter: [['list'], ['html', { open: 'never', outputFolder: '/tmp/fls-e2e-report' }]],
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1280, height: 800 },
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
      command: 'cd /tmp/flsweb-build && yarn start',
      url: 'http://localhost:3000/',
      reuseExistingServer: true,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
  projects: [
    {
      name: 'read',
      testMatch: READ_ONLY_SPECS,
      fullyParallel: true,
      workers: 2,
      retries: 1,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // No `dependencies: ['read']` — that would skip the entire mutate
      // project if any read test fails, and we want mutate's results even
      // when read flakes. Both projects share one FLSTest database, so
      // they must NOT run concurrently. To enforce that, run the suite
      // via two separate playwright invocations (`yarn test` /
      // `npm test` invokes them in sequence; see package.json scripts).
      name: 'mutate',
      testIgnore: READ_ONLY_SPECS,
      fullyParallel: false,
      workers: 1,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
