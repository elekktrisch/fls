import { defineConfig, devices } from '@playwright/test';

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
  globalSetup: './global-setup.ts',
  timeout: 60_000,
  expect: { timeout: 5_000 },
  workers: 6,
  retries: 0,
  maxFailures: 10,
  outputDir: '/tmp/fls-e2e-results',
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: '/tmp/fls-e2e-report' }],
    ['json', { outputFile: '/tmp/fls-e2e-report.json' }],
  ],
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1280, height: 800 },
    actionTimeout: 10_000,
    navigationTimeout: 15_000,
  },
  webServer: [
    {
      command:
        'cd ../flsserver/src/FLS.Server.Console/bin/Debug && FLS_LISTEN_URL="http://*:25567/" mono FLS.Server.Console.exe',
      url: 'http://localhost:25567/api/v1/countries',
      reuseExistingServer: true,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command:
        'cd /tmp/flsweb-build && ./node_modules/.bin/webpack-dev-server --TARGET=DEV --SERVER_URL=http://localhost:25567/',
      url: 'http://localhost:3000/',
      reuseExistingServer: true,
      timeout: 180_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
  ],
  projects: CATEGORIES.map((category) => ({
    name: category,
    testDir: `./tests/${category}`,
    fullyParallel: true,
    retries: 3,
    use: { ...devices['Desktop Chrome'] },
  })),
});
