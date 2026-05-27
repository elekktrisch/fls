---
id: S-174
title: Playwright e2e — real-Keycloak register + login (no mock)
epic: E-13
status: todo
estimate: M
depends_on: [S-021, S-134, S-171, S-172]
integration_base: integration/users-suite
adr_refs: [0007]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements, solution, qa, security]
context7_last_checked: 2026-05-27
github_issue: 149
origin: punch-list
---

## Context

The current SPA Playwright suite under `alpenflight/web/e2e/` runs against the `mock-auth` Angular configuration — `app.config.mock.ts` stubs `OidcSecurityService` and records authorize-args on `window.__lastAuthorizeArgs`. That covers the SPA-side wiring (CTAs call the right OIDC params, intent stamps work) but tests nothing about the *real* register + login round-trip: Keycloak's registration form, password-policy enforcement, verify-email via Mailpit, the `clubId` claim showing up on the token, the SPA's session bridge consuming `userinfo`, etc.

This story adds a second Playwright project — `real-idp` — that boots the SPA against a *running* alpenflight realm + backend + Mailpit, and exercises the full register + login + logout cycle. It deliberately runs separately from the mock-auth project: mock-auth stays the fast no-deps suite (4 min in CI); real-idp is opt-in (a separate npm script, a separate CI job that depends on `dev-up-full.sh`).

## Acceptance criteria

### Project shape

- [ ] New Playwright project `real-idp` declared in `alpenflight/web/e2e/playwright.config.ts` alongside the existing `chromium` (mock) project. `testMatch` scopes it to `tests/real-idp/**/*.spec.ts`.
- [ ] `real-idp` boots the SPA under the *non*-mock config: `ng serve --port=4201 --configuration=development` (or `--configuration=real-idp` if a tailored one is added). Distinct port so it can run in parallel with the mock-auth dev server.
- [ ] `globalSetup` script asserts (via HTTP probes, not docker-internal calls):
  - Keycloak realm `alpenflight` discovery doc reachable at `http://localhost:8090/realms/alpenflight/.well-known/openid-configuration`
  - Mailpit reachable at `http://localhost:8025/api/v1/info`
  - Backend `/actuator/health` (or `/api/v1/health`) returns 200
  - Seed user `pilot1` can be retrieved via the Admin API (no auth flow yet; just existence)
  - Prints a friendly bail-out message ("run `bash alpenflight/ops/dev-up-full.sh` first") if any probe fails.
- [ ] `npm run e2e:real-idp` invokes only this project.

### Coverage — register

- [ ] Local register happy path: `/signup` → "Sign up" CTA → KC registration form → submit valid (per realm password policy) → land on verify-email screen → Mailpit shows a delivered message → click verify link → SPA `/migrate/start` shows the post-signup landing with funnel event.
- [ ] Local register password-policy reject: short password → KC inline error shown, user stays on the form.
- [ ] Local register email-in-use reject: register `pilot1@example.com` → KC inline error.
- [ ] Each registered user is cleaned up in `afterEach` via the backend Admin API (or a Mailpit-derived sub-claim → admin-delete) so the suite is rerunnable.

### Coverage — login

- [ ] Login happy path with seed user `pilot1` / `pilot1-dev-2026!` → land on the SPA's authed root → `clubId=club-1` visible in the token (SPA exposes it via a debug attribute on `<body data-tenant>` or similar — pick the seam during refinement).
- [ ] Login wrong password → KC error, SPA stays unauthed.
- [ ] Login → logout → re-login: session cleared (no auto-relogin from a stale refresh token).
- [ ] Login locale switch: `/login?kc_locale=fr` flips KC chrome to French (smokes the parent message-bundle fallback verified by `check-theme-load.sh`).

### Coverage — Google IdP (skip-if-unconfigured)

- [ ] `test.skip(!process.env.E2E_GOOGLE_TEST_USER, '...')` guards a single google-redirect spec that only verifies the click leads to `accounts.google.com` (no full Google login automation; that's E2E flakiness we don't want). Real Google round-trip stays a manual smoke per `alpenflight/auth/README.md`.

### CI

- [ ] New CI job `e2e-real-idp` (separate workflow file or new job in the existing e2e workflow). Steps: `dev-up-full.sh` → wait-for-health → `npm run e2e:real-idp` → tear down.
- [ ] Job tagged opt-in (manual dispatch + nightly) — NOT blocking on PR merge. Real-IdP flakiness must not gate feature work; the mock-auth suite stays the PR gate.

## Tasks

- [ ] Carve out `tests/real-idp/` directory; move nothing from the mock suite (it stays as-is).
- [ ] Author register specs (happy + 2 error paths + cleanup helper).
- [ ] Author login specs (happy + 3 variants).
- [ ] Author globalSetup probes.
- [ ] Add npm script + Playwright project entry.
- [ ] Add CI workflow / job.
- [ ] Doc note in `alpenflight/web/e2e/README.md` distinguishing the two suites.

## Notes

- This story explicitly depends on S-172 (infra compose split) so the Mailpit reachability assertion is a stable contract, not "if you brought up the legacy stack first".
- This story explicitly depends on S-171 (visual theme) — register + login screens are screenshot-asserted in some specs (visual-regression at low resolution as a smoke); pinning the theme avoids churn.
- The cleanup-by-admin-API on register specs is the failure mode to design carefully — a test that registers a user but doesn't delete leaves Keycloak with growing state. `afterEach` runs even on test failure, but on suite crash it doesn't. Mitigation: globalTeardown sweeps any `e2e-*@example.com` user (predicate by email-prefix).
- Real Google OAuth automation is out of scope; the click-redirect-target smoke is enough to catch a misconfigured IdP. Don't try to drive `accounts.google.com` from Playwright.

<!-- modernize-refine: start -->

## Design notes

### Cross-story contracts
- **Consumes:** S-021 SessionStore + `alpenflight.post-login-redirect` stamp; S-134 `/signup` + `alpenflight.signup-pending` one-shot + `/migrate/start` landing; S-171 theme reference (`/login/alpenflight/`); S-172 `dev-up-infra.sh` + `dev-up-alpenflight.sh` + `alpenflight_shared` external network; S-019 seed users (`pilot1`, `clubadmin1`, `sysadmin`) + `alpenflight-backend-admin` client (`alpenflight/auth/README.md:48-49`).
- **Produces** (for S-175 + the linking-UI follow-up + S-052 / S-038 / S-040 later): `e2e/tests/real-idp/_helpers/keycloak-admin.ts` (client-credentials cache + `users.findByEmail / delete / triggerVerifyEmail`), `mailpit-client.ts` (poll + verify-link extraction), `test-user.ts` (uuid email factory + canned strong password).

### Playwright project shape
- Two new projects in the existing `playwright.config.ts`: `real-idp-setup` (zero specs; runs the four HTTP probes + provisions `e2e-occupied@example.com`) and `real-idp` (declares `dependencies: ['real-idp-setup']`). Per-project `webServer` arrays (1.40+ idiom): mock-auth keeps `:4200`; real-idp adds `ng serve --port=4201 --configuration=development`. Top-level `globalTeardown` owns the email-prefix sweep — it runs even on suite-abort where project teardown doesn't.
- Reuse `--configuration=development` (no new angular.json config — `development` already file-replaces back to the real `app.config.ts`).

### Module layout
- `e2e/tests/real-idp/{setup.ts, register.spec.ts, login.spec.ts, google-redirect.spec.ts, _helpers/...}`. Flat unless ≥3 specs share a fixture. Per-test email: `e2e-${runId}-${randomUUID().slice(0,8)}@example.com`; `runId` set in `real-idp-setup` and exported via `process.env.E2E_RUN_ID`.

### Cleanup strategy (defense-in-depth)
- (1) per-test `afterEach` deletes everything the test array-pushed; (2) `globalTeardown` sweeps any user matching `email.startsWith('e2e-') && email.endsWith('@example.com')` (the prefix is the safety pin — seed users share the `@example.com` suffix per `alpenflight/auth/README.md:67`); (3) one client-credentials token cached at worker scope, refreshed on 401. Token endpoint: `/realms/alpenflight/protocol/openid-connect/token` (realm-local, NOT master). Admin helper asserts the cleanup predicate on every DELETE candidate or throws.
- `e2e-occupied@example.com` for the email-in-use reject is provisioned idempotently in `real-idp-setup` and **never torn down** — distinct from per-test ephemeral users; do NOT reuse `pilot1`.

### Configuration / parallelism / CI
- `workers: 1` for real-idp (single realm + single Mailpit inbox). Mock-auth keeps `workers: 4` in CI.
- `retries: process.env.CI ? 1 : 0`, `timeout: 60_000`, `expect.timeout: 10_000`, `trace: 'on-first-retry'`, `video: 'retain-on-failure'`. Mock-auth's `retries: 0` posture stays.
- New workflow file `alpenflight-e2e-real-idp.yml`, `runs-on: ubuntu-22.04`, triggers `schedule: '0 3 * * *'` + `workflow_dispatch`. Steps: bring up `alpenflight_shared` network → `dev-up-infra.sh` → `dev-up-alpenflight.sh` → `pnpm exec playwright test --project=real-idp` → teardown via `if: always()`. Job timeout 20 min. **NOT** a PR gate — mock-auth stays the gate.

### Schema check (ADR 0022 directive 2)
- N/A — pure e2e infrastructure, no DB schema change.

## Edge cases & hidden requirements

- **Mailpit message race:** poll `GET /api/v1/search?query=to:<email>` every 500ms, cap 15s. If >1 match for a single test's email → fail loud (test bug; don't paper over). Parse verify-link via href regex `/realms/alpenflight/login-actions/action-token\?key=`; click via `page.goto(href)` (locale-agnostic — email body may be DE/FR but href isn't).
- **Locale assertion:** `?kc_locale=fr` → assert `<html lang="fr">` (mirrors S-171's `check-theme-load.sh` shape). Do NOT assert visible French strings; theme-parent message bundles are churn-prone.
- **Password policy + seed:** `pilot1-dev-2026!` (16 chars, special, not equal to username) satisfies S-134's `length(12) and notUsername and notEmail and specialChars(1)`. New e2e users use canned `E2eTest-2026!`.
- **`afterEach` race with KC eventual consistency:** Admin REST returns 201 before all session writes flush. Delete with retry-on-404 (3×, 500ms). `globalTeardown` is the safety net.
- **H2 reset:** federated users persist across `docker compose up` until `rebuild-keycloak.sh` does `down -v` (`alpenflight/auth/README.md:39`). Prefix sweep absorbs the gap.
- **Probes are all-or-nothing.** Suite is opt-in already — per-spec scoping multiplies surface for little gain. Probe failure prints which one (KC discovery / Mailpit / backend `/actuator/health` / `pilot1` lookup) so a backend-mid-rebuild surfaces clearly.

## Security plan

- **Admin secret scope:** suite reads `ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET` (default = the committed dev value `alpenflight-backend-admin-dev-secret`). `real-idp-setup` hard-fails before any admin call when the discovery `issuer != http://localhost:8090/realms/alpenflight` — the dev secret cannot run against non-localhost. Acceptable for nightly + manual-dispatch CI only.
- **Privilege scope:** `manage-users` + `view-users` + `query-users` only (`alpenflight/auth/README.md:49`). NEVER `manage-realm` / `manage-clients` / `impersonation`. S-175 (deferred — see Open design questions) may need `manage-realm` for `accessTokenLifespan` shortening; ADR-grade decision lives there.
- **Cleanup-predicate guard (load-bearing):** DELETE candidate MUST satisfy `email.startsWith('e2e-') && email.endsWith('@example.com')`. Helper enforces or throws — seed users (`sysadmin@example.com`, `clubadmin1@example.com`, `pilot1@example.com`) share the suffix; prefix is the safety pin.
- **Verify-link click:** click only, never extract/log the `key=` token. KC validates issuance/expiry/single-use server-side.
- **PII surface:** all addresses use `e2e-<uuid>@example.com` (RFC 2606). Seed users also `@example.com`. No real PII.
- **Trace retention:** `retain-on-failure` may capture KC password fields. Acceptable on dev-fixture credentials + localhost-only CI; do NOT extend artifact retention beyond default workflow run.
- **Google smoke:** no Google credentials ever entered Playwright. Unconditional `accounts.google.com` host-prefix assertion + `page.goBack()`.

## Test plan

- **Scope boundary.** S-174 ships: register flows (KC form + Mailpit verify-mail click + post-verify landing), login flows (happy / wrong-password / logout→re-login), locale switch, Google CTA redirect target. Stays in `signup.spec.ts:32-129`: intent resolution, signup-pending stamp shape, CTA visibility, touch-targets, funnel emit. Deferred to S-175 (token-lifecycle): silent refresh, multi-tab logout, hard-401 redirect, Bearer scoping.
- **Parity:** greenfield, no oracle. Legacy FLS has only `/Token` password-grant — no signup, no IdP, no verify-mail to validate against.
- **Cases (seams, not method names):**
  - Register happy: Mailpit poll → parse `key=` href → `page.goto` → land on `/migrate/start`. Funnel-emit assertion stays in mock-auth.
  - Password-policy reject: short password → KC inline error (PF5 v5 `.pf-v5-c-form__helper-text`) + URL stays on `registration`.
  - Email-in-use reject: register against the long-lived `e2e-occupied@example.com` → KC "already exists" inline.
  - Login happy: `pilot1/pilot1-dev-2026!` → login succeeded + landed on authed root + SessionStore populated. (`clubId=club-1` claim assertion is **deferred** — see Open design questions.)
  - Wrong-password: KC error + SPA stays unauthed.
  - Logout → re-login: `end_session_endpoint` → `context.clearCookies()` → cold `oidcSecurity.checkAuth()` returns unauthenticated. Single-tab only.
  - Locale `?kc_locale=fr`: `<html lang="fr">` + a French anchor from S-171's `messages_fr.properties` (e.g. `loginAccountTitle=Connexion`). NOT subject-line French.
  - Google redirect: unconditional. Click → assert `page.url()` host is `accounts.google.com` → `goBack()`.
- **Fixtures:** per-test ephemeral `e2e-${runId}-${uuid8}@example.com`; long-lived `e2e-occupied@example.com` provisioned in `real-idp-setup`; `pilot1` read-only.
- **Mock vs live:** everything live in real-idp. No `page.route()` interception anywhere.
- **Stable Mailpit assertions:** verify-link href regex (the contract). Brittle: subject line, body copy, brand CSS.

## Performance plan

(N/A — pure e2e test infrastructure; no hot path, no query budget, no caching surface. Wall-clock budgets are operational — 20-min job timeout, 15s Mailpit poll cap, 60s per-test timeout — and are codified in the Design notes, not separately measured.)

## Open design questions

The grill resolved every design fork; nothing remains open for the implementer to decide. The items below are **AC drift surfaced for `/modernize-decompose` and `/modernize-finalize`** — they require AC text edits that refine itself is out-of-scope for (per the refine skill).

1. **Login AC — drop the `clubId=club-1 visible in the token` clause.** The clause assumed a debug-attribute seam; per operator grill, the user→person→club linking belongs in a real UI flow. **Action at finalize:** rewrite the login-happy AC to assert only login-succeeded + authed-root + SessionStore populated, and **file a new sibling story** for the linking UI (which will own the clubId-visible assertion against a real UI surface).
2. **Google IdP AC — drop the `test.skip(!E2E_GOOGLE_TEST_USER)` env-gate.** Per grill, the click-redirect smoke runs unconditionally; the `accounts.google.com` bounce happens before any KC `invalid_client` error, so the test is meaningful with the committed placeholder client_id. **Action at finalize:** rewrite the Google AC to "unconditional click-redirect smoke; assertion target is `accounts.google.com` host prefix; no env-gate."
3. **Notes — drop "visual-regression at low resolution as a smoke".** Conflicts with `alpenflight/web/CLAUDE.md` §8 ("diagnostic output, not visual-regression — no `toHaveScreenshot`"). **Action at finalize:** replace with "screenshots written as diagnostic output per CLAUDE.md §8; theme parity asserted structurally (login HTML contains `/login/alpenflight/`, asset reachability)."
4. **File S-175 — token-lifecycle real-IdP harness.** Picks up S-021's deferred follow-up: silent refresh (via Admin-API `accessTokenLifespan` shortening), multi-tab logout, hard-401 redirect via Admin-API user-disable, Bearer scoping to `/api/v1/*`. Depends on S-174 (uses its helpers + Playwright project). Likely needs `manage-realm` for the `accessTokenLifespan` mutation — surface that scope expansion in S-175's security plan.

<!-- modernize-refine: end -->
