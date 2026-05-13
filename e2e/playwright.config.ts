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
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
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
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
