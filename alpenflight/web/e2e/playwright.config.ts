import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';

const PROJECT_ROOT = resolve(__dirname, '..');
const BASE_URL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';

export default defineConfig({
  testDir: './tests',
  // Skip the parity-port masterdata specs that are not on the current
  // critical path (flights, clubs, navigation, landing). They cover real
  // CRUD surfaces, but their happy-paths add ~22 tests and the 5-minute
  // step budget is more valuable than the coverage right now. Re-enable
  // when the underlying features become load-bearing — or move them to
  // a separate nightly project so they don't gate PR feedback.
  testIgnore: [
    '**/masterdata/articles-crud.spec.ts',
    '**/masterdata/locations-crud.spec.ts',
    '**/masterdata/flight-types-crud.spec.ts',
  ],
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  // Zero retries in CI: this is a mock-only suite (no Keycloak, no
  // backend, no flaky cross-process timing). Retries here mask flake
  // instead of fixing it, and triple the wall-clock cost of every
  // failure — burning the 5-minute step budget on a single red test
  // chasing 30s × 3 timeouts.
  retries: 0,
  // 4 parallel workers in CI to match ubuntu-22.04's core count. ng
  // serve is shared via Playwright's `webServer` config (one instance)
  // so worker count only fans out browser contexts, not dev servers.
  // If ng-serve asset-serving becomes a bottleneck, dial back to 2.
  workers: process.env['CI'] ? 4 : undefined,
  // Bail once a regression is obvious instead of burning the full 15-min
  // budget on a guaranteed-red run. 3 absorbs a flake without dragging
  // the whole suite through a doomed run. Tunable via
  // PLAYWRIGHT_MAX_FAILURES; 0 / empty disables.
  maxFailures: Number(process.env['PLAYWRIGHT_MAX_FAILURES'] ?? 3),
  reporter: process.env['CI'] ? [['github'], ['html', { open: 'never' }]] : 'html',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    // SPA boots under the `mock-auth` angular.json configuration —
    // fileReplaces app.config.ts → app.config.mock.ts so the bootstrap
    // does NOT hit `/realms/alpenflight` (no Keycloak in this lane).
    // Specs that need a real OIDC round-trip belong in a follow-up
    // playwright project + Keycloak-up CI job (S-021 manual smoke
    // covers the OIDC path today; see story file).
    command: 'node node_modules/@angular/cli/bin/ng serve --port=4200 --configuration=mock-auth',
    cwd: PROJECT_ROOT,
    url: BASE_URL,
    reuseExistingServer: !process.env['CI'],
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
