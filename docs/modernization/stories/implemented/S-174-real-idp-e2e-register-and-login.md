---
id: S-174
title: Playwright e2e — real-Keycloak register + login (no mock)
epic: E-13
status: done
started_at: 2026-05-28
done_at: 2026-05-28
estimate: M
depends_on: [S-021, S-134, S-171, S-172]
integration_base: integration/users-suite
adr_refs: [0007]
refined: true
refined_at: 2026-05-27
refined_specialists: [requirements, solution, qa, security]
context7_last_checked: 2026-05-27
github_issue: 149
github_pr: 150
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

## Cross-story contracts (produced for S-175 + linking-UI follow-up)

- **Network / scripts:** consumes S-172's `alpenflight_shared` + `dev-up-infra.sh` + `dev-up-alpenflight.sh`. Consumes S-019's seed users + `alpenflight-backend-admin` client; S-021's SessionStore + post-login redirect; S-134's `/signup` + signup-pending one-shot; S-171's theme reference.
- **Helpers (reusable):** `alpenflight/web/e2e/tests/real-idp/_helpers/keycloak-admin.ts` (client-credentials cache + `findUserByEmail` / `createUser` / `ensureUser` / `deleteUser` / `sweepE2eUsers` — every admin call re-asserts `assertLocalhostIssuer()`), `mailpit-client.ts` (poll-by-to + verify-link regex extraction), `test-user.ts` (UUID email + canned password + cleanup-predicate guard), `kc-form.ts` (form-scoped submit + broad error-selector union), `probes.ts` (four parallelized probes).
- **Cleanup contract (load-bearing):** every DELETE candidate must satisfy `email.startsWith('e2e-') && email.endsWith('@example.com')` — seed users share the `@example.com` suffix, so the prefix is the safety pin. Helper enforces or throws.
- **Localhost-issuer boundary:** `assertLocalhostIssuer()` fires inside `adminRequest()`, not just at setup — the committed dev secret can never reach a deployed Keycloak.

## Open design questions

The grill resolved every design fork; the items below are **AC drift surfaced for `/modernize-finalize`** — text edits + a follow-up story file.

1. **Login AC** — drop `clubId=club-1 visible in the token`; rewrite to assert login-succeeded + authed-root + SessionStore populated. File a new sibling story for the user→person→club linking UI (it will own the clubId-visible assertion against a real UI surface).
2. **Google IdP AC** — drop the `test.skip(!E2E_GOOGLE_TEST_USER)` env-gate; rewrite to "unconditional click-redirect smoke; assertion target is `accounts.google.com` host prefix."
3. **Notes** — drop "visual-regression at low resolution as a smoke" (conflicts with `alpenflight/web/CLAUDE.md` §8); replace with "screenshots written as diagnostic output; theme parity asserted structurally."
4. **File S-175** — token-lifecycle real-IdP harness (silent refresh via Admin-API `accessTokenLifespan` shortening, multi-tab logout, hard-401 redirect via Admin-API user-disable, Bearer scoping). Depends on S-174; likely needs `manage-realm` scope expansion — surface in S-175's security plan.

Performance / Security: N/A — pure e2e test infrastructure; no hot path, no auth surface beyond what S-019/S-134/S-171 already ship. ADR 0022 directive 2 N/A (no schema).

<!-- modernize-refine: end -->
