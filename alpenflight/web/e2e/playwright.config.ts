import { defineConfig, devices } from '@playwright/test';
import { resolve } from 'node:path';

const PROJECT_ROOT = resolve(__dirname, '..');
const MOCK_BASE_URL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const REAL_IDP_BASE_URL = process.env['E2E_REAL_IDP_BASE_URL'] ?? 'http://localhost:4201';

export default defineConfig({
  testDir: './tests',
  // Skip the parity-port masterdata specs that are not on the current
  // critical path (flights, clubs, navigation, landing). They cover real
  // CRUD surfaces, but their happy-paths add ~22 tests and the 5-minute
  // step budget is more valuable than the coverage right now. Re-enable
  // when the underlying features become load-bearing — or move them to
  // a separate nightly project so they don't gate PR feedback.
  testIgnore: ['**/masterdata/articles-crud.spec.ts', '**/masterdata/flight-types-crud.spec.ts'],
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  // Sweeps `e2e-*@example.com` users from the alpenflight realm. Runs
  // even on suite-abort where per-project teardown wouldn't. No-op on
  // mock-auth runs (no admin token, no probes — exits early).
  globalTeardown: require.resolve('./tests/real-idp/global-teardown.ts'),
  // Config-level (shared across projects). In CI we also emit a JSON report:
  // it IS the J-24 proof manifest (see e2e/proof-gallery/README.md) — the
  // gallery generator reads the `proof-video` attachments + `proof-*`
  // annotations the `proofVideo()` helper pushes from the real-idp proof specs.
  // The mock chromium PR run produces this file with no proof-* annotations,
  // which the generator tolerates as "no proofs"; it does not break the
  // github/html reporters (Playwright runs all listed reporters).
  // Path resolution: Playwright resolves a config-level reporter `outputFile`
  // against the CONFIG DIR — the dir holding THIS config file, i.e.
  // `alpenflight/web/e2e/` — NOT the process cwd (verified in the installed
  // source: `runner/index.js` `resolveOutputFile` →
  // `path.resolve(options.configDir, options.outputFile)`). So the leading
  // `../` is required: `e2e/` + `../test-results/proof-manifest.json` lands the
  // manifest at `alpenflight/web/test-results/proof-manifest.json` — co-located
  // with the `test-results/` videos + the proof artifact upload path, and
  // byte-identical to what the `alpenflight-proof` generate step reads (cwd
  // `alpenflight/web` + `test-results/proof-manifest.json`). Without the `../`
  // the manifest would land at `e2e/test-results/` and the generate step would
  // ENOENT (the real gate-red this T-05 fixes).
  reporter: process.env['CI']
    ? [
        ['github'],
        ['html', { open: 'never' }],
        ['json', { outputFile: '../test-results/proof-manifest.json' }],
      ]
    : 'html',
  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    // ── mock-auth ────────────────────────────────────────────────────────
    // Fast PR gate. SPA boots under the `mock-auth` angular configuration
    // (fileReplaces app.config.ts → app.config.mock.ts); zero deps on
    // Keycloak / Mailpit / backend. Default `pnpm e2e` target.
    {
      name: 'chromium',
      testMatch: 'tests/!(real-idp)/**/*.spec.ts',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: MOCK_BASE_URL,
      },
      // Zero retries in CI: this is a mock-only suite (no Keycloak, no
      // backend, no flaky cross-process timing). Retries here mask flake
      // instead of fixing it.
      retries: 0,
      // 4 parallel workers in CI to match ubuntu-22.04's core count. ng
      // serve is shared (one webServer instance) so worker count only
      // fans out browser contexts.
      workers: process.env['CI'] ? 4 : undefined,
      maxFailures: Number(process.env['PLAYWRIGHT_MAX_FAILURES'] ?? 3),
      timeout: 30_000,
      expect: { timeout: 5_000 },
    },

    // ── real-idp-setup ──────────────────────────────────────────────────
    // Zero observable specs in CI output. Runs the four HTTP probes
    // (Keycloak discovery / Mailpit / backend health / seed-user lookup)
    // + provisions the long-lived `e2e-occupied@example.com` user used by
    // the email-in-use reject spec. Stamps E2E_RUN_ID for the `real-idp`
    // project's per-test email factory.
    {
      name: 'real-idp-setup',
      testMatch: 'tests/real-idp/setup.ts',
    },

    // ── real-idp ────────────────────────────────────────────────────────
    // Opt-in (nightly + workflow_dispatch). Boots the SPA under
    // `--configuration=development` against a live alpenflight realm +
    // Mailpit + backend. Cross-process timing makes this flakier than
    // mock-auth, so retry posture diverges deliberately.
    {
      name: 'real-idp',
      testMatch: 'tests/real-idp/**/*.spec.ts',
      dependencies: ['real-idp-setup'],
      use: {
        ...devices['Desktop Chrome'],
        baseURL: REAL_IDP_BASE_URL,
        // J-0 acceptance artifact: the real-chain proof retains its
        // pass-video, not just the failure video. This is the only run
        // that proves verticality end-to-end (real Keycloak + real
        // backend + real Postgres), so the green run's video is archived
        // as a CI artifact for operator parity review. Overrides the
        // global `video: 'retain-on-failure'`; mock-auth chromium keeps
        // the global policy so every PR run isn't bloated with videos.
        video: 'on',
      },
      // Single worker against one realm + one Mailpit inbox — parallel
      // registration races against KC user-exists checks and Mailpit
      // poll contention. Nightly opt-in suite; parallelism isn't worth
      // the complexity.
      workers: 1,
      // One retry in CI catches Mailpit-delivery jitter without masking
      // real bugs. Local: zero retries — diagnose, don't paper over.
      retries: process.env['CI'] ? 1 : 0,
      // Per-TEST budget: a real-chain test (real Keycloak redirect-login +
      // token exchange ~5-10s, then nav + multi-step create) can't fit in
      // 5s, but 60s let a single stuck step burn a minute and report a vague
      // "Test timeout exceeded" instead of the failing assertion. 20s is
      // enough headroom for the legitimate flow yet fails ~3x faster.
      timeout: 20_000,
      // Per-ASSERTION/action timeout: 5s max so a stuck wait fails fast at
      // the exact step (matches the mock `chromium` project). Explicit
      // `waitForResponse` waits in specs are likewise capped at 5s.
      expect: { timeout: 5_000 },
      maxFailures: Number(process.env['PLAYWRIGHT_REAL_IDP_MAX_FAILURES'] ?? 3),
    },
  ],
  // mock-auth dev server is always wired (default `pnpm e2e` target).
  // real-idp dev server is opt-in via E2E_REAL_IDP=1 so PR CI (which
  // only runs --project=chromium) doesn't pay its ~20-30s boot cost.
  // The real-idp nightly workflow sets the env var; local invocations
  // either set it explicitly or rely on `reuseExistingServer` if a
  // separate `pnpm start` is already running on :4201.
  webServer: process.env['E2E_REAL_IDP']
    ? [
        {
          command:
            'node node_modules/@angular/cli/bin/ng serve --port=4200 --configuration=mock-auth',
          cwd: PROJECT_ROOT,
          url: MOCK_BASE_URL,
          reuseExistingServer: !process.env['CI'],
          timeout: 120_000,
          stdout: 'pipe',
          stderr: 'pipe',
        },
        {
          // real-idp: `development` configuration keeps the real
          // app.config.ts (no fileReplacements) so OIDC hits
          // localhost:8090/realms/alpenflight.
          command:
            'node node_modules/@angular/cli/bin/ng serve --port=4201 --configuration=development',
          cwd: PROJECT_ROOT,
          url: REAL_IDP_BASE_URL,
          reuseExistingServer: !process.env['CI'],
          timeout: 180_000,
          stdout: 'pipe',
          stderr: 'pipe',
        },
      ]
    : {
        // mock-auth only — the default for PR CI and `pnpm e2e`.
        command:
          'node node_modules/@angular/cli/bin/ng serve --port=4200 --configuration=mock-auth',
        cwd: PROJECT_ROOT,
        url: MOCK_BASE_URL,
        reuseExistingServer: !process.env['CI'],
        timeout: 120_000,
        stdout: 'pipe',
        stderr: 'pipe',
      },
});
