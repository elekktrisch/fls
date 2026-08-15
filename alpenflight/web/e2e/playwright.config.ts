import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';
import { resolveChromiumExecutablePath, chromiumLaunchArgs } from './chromium-executable';

const PROJECT_ROOT = resolve(__dirname, '..');
const MOCK_BASE_URL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const REAL_IDP_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';

const CI_RUNNER_VCPU_WORKERS = 4;
const REAL_IDP_SERIAL_WORKERS_ENFORCED_BY_CLI_FLAG = 1;
const PROOF_MANIFEST_OUTPUT_FILE_RELATIVE_TO_CONFIG_DIR = '../test-results/proof-manifest.json';
const OFF_CRITICAL_PATH_SPECS_SKIPPED_FOR_PR_STEP_BUDGET = [
  '**/masterdata/articles-crud.spec.ts',
  '**/masterdata/flight-types-crud.spec.ts',
];

const CHROMIUM_EXECUTABLE_PATH = resolveChromiumExecutablePath();
const CHROMIUM_LAUNCH_OPTIONS = {
  ...(CHROMIUM_EXECUTABLE_PATH ? { executablePath: CHROMIUM_EXECUTABLE_PATH } : {}),
  args: chromiumLaunchArgs(CHROMIUM_EXECUTABLE_PATH),
};

const WEB_SERVER = process.env['GALLERY_LINKS_ONLY']
  ? undefined
  : process.env['E2E_REAL_IDP']
    ? [
        {
          command:
            'node node_modules/@angular/cli/bin/ng serve --port=4200 --configuration=mock-auth',
          cwd: PROJECT_ROOT,
          url: MOCK_BASE_URL,
          reuseExistingServer: !process.env['CI'],
          timeout: 120_000,
          stdout: 'pipe' as const,
          stderr: 'pipe' as const,
        },
        {
          command:
            'node node_modules/@angular/cli/bin/ng serve --port=4201 --configuration=development',
          cwd: PROJECT_ROOT,
          url: REAL_IDP_BASE_URL,
          reuseExistingServer: !process.env['CI'],
          timeout: 180_000,
          stdout: 'pipe' as const,
          stderr: 'pipe' as const,
        },
      ]
    : {
        command:
          'node node_modules/@angular/cli/bin/ng serve --port=4200 --configuration=mock-auth',
        cwd: PROJECT_ROOT,
        url: MOCK_BASE_URL,
        reuseExistingServer: !process.env['CI'],
        timeout: 120_000,
        stdout: 'pipe' as const,
        stderr: 'pipe' as const,
      };

export default defineConfig({
  testDir: './tests',
  ...(process.env['CI'] ? { workers: CI_RUNNER_VCPU_WORKERS } : {}),
  testIgnore: OFF_CRITICAL_PATH_SPECS_SKIPPED_FOR_PR_STEP_BUDGET,
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  globalTeardown: require.resolve('./tests/real-idp/global-teardown.ts'),
  reporter: process.env['CI']
    ? [
        ['github'],
        ['html', { open: 'never' }],
        ['json', { outputFile: PROOF_MANIFEST_OUTPUT_FILE_RELATIVE_TO_CONFIG_DIR }],
      ]
    : 'html',
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      testMatch: ['tests/!(real-idp|profile)/**/*.spec.ts'],
      testIgnore: ['**/proof-gallery/gallery-deployed-link-check.spec.ts'],
      use: {
        ...devices['Desktop Chrome'],
        baseURL: MOCK_BASE_URL,
        launchOptions: CHROMIUM_LAUNCH_OPTIONS,
      },
      retries: 0,
      timeout: 30_000,
      expect: { timeout: 5_000 },
    },

    {
      name: 'real-idp-setup',
      testMatch: 'tests/real-idp/setup.ts',
    },

    {
      name: 'real-idp',
      testMatch: ['tests/real-idp/**/*.spec.ts', 'tests/profile/**/*.spec.ts'],
      dependencies: ['real-idp-setup'],
      use: {
        ...devices['Desktop Chrome'],
        baseURL: REAL_IDP_BASE_URL,
        video: 'on',
        launchOptions: CHROMIUM_LAUNCH_OPTIONS,
      },
      workers: REAL_IDP_SERIAL_WORKERS_ENFORCED_BY_CLI_FLAG,
      retries: process.env['CI'] ? 1 : 0,
      timeout: 45_000,
      expect: { timeout: 5_000 },
    },

    {
      name: 'proof-gallery-links',
      testMatch: ['tests/proof-gallery/gallery-deployed-link-check.spec.ts'],
      use: { launchOptions: CHROMIUM_LAUNCH_OPTIONS },
      retries: 0,
      timeout: 60_000,
      expect: { timeout: 10_000 },
    },
  ],
  ...(WEB_SERVER ? { webServer: WEB_SERVER } : {}),
});
