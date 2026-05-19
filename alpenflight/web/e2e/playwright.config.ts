import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';

const PROJECT_ROOT = resolve(__dirname, '..');
const BASE_URL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';

export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 2 : 0,
  workers: process.env['CI'] ? 1 : undefined,
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
