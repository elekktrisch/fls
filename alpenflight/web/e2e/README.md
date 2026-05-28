# alpenflight/web/e2e — Playwright suites

Two Playwright projects share `playwright.config.ts`, with very different
running posture:

| Project | Port | Angular config | Deps | CI lane | Runtime |
|---|---|---|---|---|---|
| `chromium` (mock-auth) | 4200 | `mock-auth` (file-replaces `app.config.mock.ts`) | none | PR gate (`alpenflight-e2e.yml`) | ~4 min |
| `real-idp-setup` + `real-idp` | 4201 | `development` (real OIDC) | Keycloak + Mailpit + alpenflight backend | nightly + `workflow_dispatch` (`alpenflight-e2e-real-idp.yml`) | ~10-15 min |

**Rule:** mock-auth stays the fast PR gate; real-idp is opt-in. Real-IdP
flakiness must not gate feature work.

## Running locally

### mock-auth (default)

Zero deps; `ng serve` boots under the mock-auth configuration via the
top-level `webServer`.

```bash
pnpm e2e                                # all chromium specs
pnpm exec playwright test --config=e2e/playwright.config.ts --project=chromium --grep flights
```

### real-idp

Requires the full dev stack (S-172's three compose projects + alpenflight
backend):

```bash
bash alpenflight/ops/dev-up-full.sh     # infra + legacy + new stack
cd alpenflight/server && ./gradlew bootRun &
cd alpenflight/web && pnpm e2e:real-idp
```

The `e2e:real-idp` npm script sets `E2E_REAL_IDP=1`, which gates the
:4201 `ng serve` webServer block — without it, PR CI's `pnpm e2e` only
pays the :4200 boot cost.

Environment overrides:

| Var | Default | Purpose |
|---|---|---|
| `E2E_KC_ISSUER` | `http://localhost:8090/realms/alpenflight` | Keycloak realm discovery base. Hard-failed if not localhost (committed dev-secret boundary). |
| `ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET` | `alpenflight-backend-admin-dev-secret` (committed) | Service-account client-credentials for KC Admin REST. Same posture as the realm-export placeholder. |
| `E2E_MAILPIT_BASE` | `http://localhost:8025` | Mailpit REST endpoint. |
| `E2E_BACKEND_HEALTH` | `http://localhost:8080/actuator/health` | Backend probe URL. |
| `E2E_REAL_IDP_BASE_URL` | `http://localhost:4201` | SPA under `--configuration=development`. |
| `E2E_RUN_ID` | (set by setup project) | 6-char hex run id; per-test emails interpolate it. |

## What lives where

```
e2e/
├── playwright.config.ts            two projects, globalTeardown wired
├── tsconfig.json
└── tests/
    ├── *.spec.ts                   mock-auth specs (chromium project)
    ├── public/signup.spec.ts       SPA-side signup wiring (mock-auth)
    └── real-idp/
        ├── setup.ts                  pre-flight probes + e2e-occupied provision
        ├── global-teardown.ts        e2e-* sweep + accessTokenLifespan restore
        ├── register.spec.ts          happy + password-policy + email-in-use
        ├── login.spec.ts             happy + wrong-pw + logout/re-login + locale
        ├── google-redirect.spec.ts   unconditional accounts.google.com smoke
        ├── token-lifecycle.spec.ts   silent refresh + multi-tab + hard 401 + Bearer scoping
        ├── public-routes.spec.ts     anonymous nav stays public, no /api/v1/* calls
        └── _helpers/
            ├── keycloak-admin.ts     token cache + cleanup guard + realm/user mutation HOFs
            ├── mailpit-client.ts     poll + verify-link extraction
            ├── test-user.ts          UUID email factory + canned password
            └── probes.ts             the four HTTP probes
```

## Realm-mutating specs

The token-lifecycle suite mutates `accessTokenLifespan` realm-wide to force
silent-refresh + refresh-grant-deny inside the test window. The contract:

1. **Wrap every mutation in `withRealmPatch(partial, fn)`** from
   `_helpers/keycloak-admin.ts`. The HOF snapshots the affected keys
   pre-patch and restores them in `finally`. Specs MUST NOT call
   `updateRealm()` directly.
2. **Wrap the whole describe in `test.describe.configure({ mode: 'serial' })`**.
   On first failure, serial stops the block — protects the restore step
   from running against a half-mutated realm in a follow-on spec.
3. **Single-instance invariant**: real-idp runs `workers: 1` against ONE
   alpenflight realm. Do NOT run a second nightly job, `--shard` matrix,
   or concurrent `--project=real-idp` invocation against the same KC —
   realm-mutating specs would interleave.

The `global-teardown.ts` safety net re-fetches `accessTokenLifespan` and
PUTs it back to the canonical 900s if drifted; covers the SIGKILL /
wall-clock-timeout path where `withRealmPatch`'s `finally` never runs.

## Cleanup contract (real-idp only)

Three defense layers:

1. **Per-test `afterEach`** deletes anything the test pushed onto a
   tracked array. Runs even on test failure.
2. **Top-level `globalTeardown`** sweeps any user matching
   `email.startsWith('e2e-') && email.endsWith('@example.com')`. Runs
   even on suite-abort (where per-project teardown wouldn't). The
   prefix is the safety pin — seed users (`pilot1@example.com`, etc.)
   share the `@example.com` suffix.
3. **Admin REST helper** asserts the predicate on every DELETE
   candidate or throws — defense-in-depth against a bad caller.

`e2e-occupied@example.com` (the email-in-use reject fixture) is
provisioned idempotently in `setup.ts` and never torn down.

## Diagnostic artifacts

`retain-on-failure` traces + videos land under `playwright-report/` and
are uploaded as a workflow artifact for 14 days. Screenshots are
**diagnostic only** — no `toHaveScreenshot` visual-regression per
`alpenflight/web/CLAUDE.md` §8.

KC password fields are captured in failing-test traces (dev-fixture
credentials, localhost-only CI — acceptable). Do NOT extend retention
beyond the workflow run's default.
