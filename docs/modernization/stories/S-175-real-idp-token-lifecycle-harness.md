---
id: S-175
title: Playwright e2e — real-IdP token lifecycle (silent refresh, multi-tab logout, hard 401, Bearer scoping)
epic: E-13
status: todo
estimate: M
depends_on: [S-174]
integration_base: integration/users-suite
adr_refs: [0007]
refined: true
refined_at: 2026-05-28
refined_specialists: [requirements, solution, qa, security]
context7_last_checked: 2026-05-28
github_issue: 153
github_pr: 154
origin: rework-meta
---

## Context

S-174 shipped the real-IdP Playwright harness (`alpenflight/web/e2e/tests/real-idp/`)
with register + login + locale + Google-redirect coverage. The Open design questions
in S-174's refinement deferred the token-lifecycle slice to this story; the same
deferral was originally surfaced in S-021's "Follow-up: real-OIDC Playwright harness"
section.

S-175 picks up the four token-lifecycle items by reusing S-174's helpers
(`_helpers/keycloak-admin.ts`, `mailpit-client.ts`, `test-user.ts`, `kc-form.ts`,
`probes.ts`), the `real-idp` Playwright project, and the nightly `alpenflight-e2e-real-idp.yml`
workflow. No new infrastructure. The harness is the contract; this story exercises
behaviors the mock-auth project cannot — silent refresh races against the OIDC
library, multi-tab session linkage via shared `storageState`, hard-401 redirects
triggered by admin-disabled users, and Bearer-token scoping to `/api/v1/*` only.

## Acceptance criteria

### Silent refresh

- [ ] Spec mutates the realm via the Admin REST API to shorten `accessTokenLifespan`
      to a small value (e.g. 30s), logs in, waits past the access-token expiry, and
      asserts the SPA stays authenticated (silent refresh succeeded) and that an
      authed-only route still renders.
- [ ] After the spec, the realm `accessTokenLifespan` is restored to its prior value
      in `afterEach` (or in a serial-`describe` `afterAll`) so subsequent specs aren't
      poisoned. Restoration is enforced by capturing the prior value in `beforeAll`.
- [ ] If `manage-realm` is not yet granted to `alpenflight-backend-admin`, the spec
      fails fast in `beforeAll` with a pointer to the realm-export edit that grants
      it. (Scope expansion is the load-bearing security decision — see Security plan.)

### Multi-tab logout

- [ ] Two `browser.newContext()` instances share `storageState` (or the SPA's
      session cookie/token store) after a single login. Logging out in one tab
      causes the other tab to detect the session loss on its next route navigation
      and redirect to login (or the unauthed landing).
- [ ] Multi-tab session-loss-detection seam is the SPA's auth-event channel
      (S-021's `OidcSessionBridge` emits a session-loss event observable from a
      `BroadcastChannel` or storage-event subscription); spec asserts the second
      tab's URL transitions, not the channel mechanics directly.

### Hard 401 redirect

- [ ] Spec logs in as a per-test ephemeral `e2e-<uuid>` user (created via S-174's
      `createUser` helper), then disables the user via the Admin REST API
      (`PUT /admin/realms/alpenflight/users/{id}` with `enabled: false`), then
      triggers a fresh API call from the SPA. The 401 from the resource server
      surfaces as an SPA redirect to login (S-021's hard-401 contract).
- [ ] Cleanup: user is re-enabled or deleted in `afterEach` (delete is fine; the
      helper sweeps `e2e-*` users anyway).

### Bearer scoping

- [ ] Spec inspects outbound requests via `page.on('request')` during normal app
      navigation. Assertions:
      - Every `/api/v1/*` request carries `Authorization: Bearer <jwt>`.
      - No request to a non-`/api/v1/*` path (Keycloak's own endpoints, static
        assets, third-party CDNs if any) carries an `Authorization: Bearer` header.
- [ ] Public routes (landing, /signup, /auth/* callbacks) do NOT trigger any
      `/api/v1/*` calls — the Bearer interceptor only fires after auth.

### Public-route-stays-public

- [ ] Anonymous navigation to `/`, `/signup`, `/auth/logout` (post-logout terminal),
      and any other route flagged `publicAccess: true` does NOT redirect to KC. The
      SPA renders the public chrome.

### CI

- [ ] Specs land under `tests/real-idp/` and are picked up by the existing
      `--project=real-idp` filter. No new workflow file needed — runs in the
      `alpenflight-e2e-real-idp.yml` nightly + workflow_dispatch lane.
- [ ] Realm-mutating specs (silent refresh, hard-401) are wrapped in a
      `test.describe.serial(...)` block so they don't race other specs in the
      same project's `workers: 1` queue.

## Tasks

- [ ] Add the Admin REST `manage-realm` role to `alpenflight-backend-admin`'s
      service account in `realm-export.json` (S-019's file); update `check-realm-shape.sh`
      if the asserted role set is enumerated.
- [ ] Extend `_helpers/keycloak-admin.ts` with `getRealm()` + `updateRealm(patch)` +
      `setUserEnabled(id, enabled)` (all guarded by the same cleanup-predicate /
      localhost-issuer pattern S-174 introduced).
- [ ] Author `tests/real-idp/token-lifecycle.spec.ts` covering the four flows.
- [ ] Author `tests/real-idp/public-routes.spec.ts` for the public-route assertion.
- [ ] Doc the realm-mutating-spec serial-describe convention in
      `alpenflight/web/e2e/README.md`.

## Notes

- The `accessTokenLifespan` mutation is the most invasive change in this story;
  it is realm-wide and survives the spec run if `afterEach` doesn't restore. The
  serial-describe + afterAll-restore + beforeAll-snapshot pattern is the contract.
- Multi-tab logout uses `BrowserContext.storageState()` to capture the post-login
  state, then re-hydrates it in a second context via `browser.newContext({ storageState })`.
  See https://playwright.dev/docs/auth#multiple-signed-in-roles for the pattern.
- Bearer-scoping is an SPA-side interceptor contract from S-021 — the test
  observes outbound requests, not internal interceptor state.
- `manage-realm` is a privilege expansion vs. S-174's `manage-users` / `view-users`
  / `query-users`. The localhost-issuer guard already in
  `_helpers/keycloak-admin.ts:adminRequest()` is the security boundary that
  contains the expanded scope to local development + nightly CI.

<!-- modernize-refine: start -->

## Design notes

### Cross-story contracts
- **Consumes (S-174):** `_helpers/keycloak-admin.ts` (`adminRequest()` with `assertLocalhostIssuer()` re-asserted per call, `createUser`/`deleteUser`/`sweepE2eUsers`, `isCleanupCandidate` predicate), `kc-form.ts`, `probes.ts`, the `real-idp` Playwright project (`workers: 1`), nightly `alpenflight-e2e-real-idp.yml` workflow. No new infrastructure.
- **Consumes (S-021):** Bearer interceptor scope `secureRoutes: ['/api/v1/']` (`auth.config.ts:57`); hard-401 redirect contract (`core/session/session.guard.ts`); `oidcSecurity.logoff()` RP-initiated logout.
- **Produces:** `getRealm()`, `withRealmPatch(partial, fn)` HOF (snapshot + try/finally restore + module-scoped mutex), `setUserEnabled(id, enabled, emailForGuard)` (predicate-guarded same as `deleteUser`). Reusable by future stories that need realm-policy mutation or user-disable.

### Helper extension shape (`_helpers/keycloak-admin.ts`)
- `withRealmPatch<T>(partial, fn): Promise<T>` is the load-bearing HOF — snapshot the affected keys via `getRealm()`, apply the patch, run `fn`, restore in `finally`. Module-scoped mutex refuses concurrent invocation (a dev-machine net; `workers: 1` makes contention impossible in CI). Specs NEVER call `updateRealm()` directly.
- `setUserEnabled` enforces `isCleanupCandidate(emailForGuard)` or throws — seed users (`pilot1`, `clubadmin1`, `sysadmin`) cannot be disabled through this helper even by typo.

### Realm-export scope expansion (load-bearing edit)
- Add `"manage-realm"` to `alpenflight-backend-admin`'s service-account `clientRoles["realm-management"]` array in `alpenflight/auth/realm-export.json`. **Required for `accessTokenLifespan` mutation**; not granted in S-174.
- Update `alpenflight/auth/scripts/check-realm-shape.sh:83` `EXPECTED_SA_ROLES` from `"manage-users,query-users,view-users"` to `"manage-realm,manage-users,query-users,view-users"` (alphabetical; the script's equality check stays — explicit list, no looseness). Comment-cite S-175 next to the new role in the export so a future reader sees why.

### Realm-mutation safety (two layers)
- `withRealmPatch`'s `try { … } finally { restore() }` is the in-spec contract.
- `globalTeardown` (S-174's existing one) extended: re-fetch `accessTokenLifespan` and PUT it back to the canonical 900 (per `check-realm-shape.sh:142-147`) if drifted. Covers worker-SIGKILL where `finally` doesn't run.
- Pin `SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS = 30` as a named constant in the helper; don't scatter literals.

### Multi-tab test fixture (load-bearing — AC says wrong thing)
- Use `context.newPage()` twice from the SAME Playwright `BrowserContext`. Same context = live shared `localStorage` (which is where the OIDC client persists tokens per `app.config.ts:39-44`). **NOT** `browser.newContext({ storageState })` — that's a snapshot, no live storage events. The AC's wording is mistaken (`browser.newContext()`); see Open design questions for the AC-text fix at finalize.
- Trigger: tab A invokes the SPA's user-visible logout (RP-initiated `oidcSecurity.logoff()`, NOT `logoffLocal()` — otherwise KC's SSO cookie keeps tab B silently re-authenticated).
- Assertion: tab B's URL transition after its next route navigation. Never assert on internal `OidcSessionBridge` events; the AC's "BroadcastChannel or storage-event subscription" mechanism does not exist in code — the trigger is library-internal storage-poll on `checkAuth()`.

### Hard-401 mechanics
- KC user-disable does NOT instantly invalidate already-issued JWTs (offline JWT validation). 401 surfaces on next refresh-rotation OR token expiry. Spec uses `withRealmPatch({ accessTokenLifespan: 30 })` to force renewal within the test window — accepts either trigger (silent-refresh-failure redirect OR direct 401-redirect) via URL-transition assertion. Less fixture coupling than waiting for a specific path.
- Cleanup: `afterEach` deletes via S-174's helper (predicate-guarded). No re-enable.

### Bearer-scoping observability (partition predicate)
- `page.on('request', req => …)` records every outbound during a navigation. Partition:
  - **must-have-Bearer:** `req.url().includes('/api/v1/')` AND same-origin as the page.
  - **must-NOT-have-Bearer:** `new URL(req.url()).host === 'localhost:8090'` (KC origin).
  - **indifferent:** everything else (assets, hot-reload).
- Spec asserts both partitions per user-visible navigation; empty observed-list per partition = spec bug, not a pass.

### Public-route-stays-public (hardcoded list)
- Spec hardcodes the route list — runtime derivation would couple e2e build to app build. Current set: `/`, `/signup`, `/scenic-flight`, `/discovery-flight`, `/auth/callback`, `/auth/logout`. Excludes `/dev/primitives`.
- For each route: anonymous `context.newPage()` → navigate → assert URL doesn't match `/realms/alpenflight/protocol/openid-connect/auth` AND no `/api/v1/*` requests observed. The check is a regression net on TWO contracts: route-flag honored AND `session.store.ts:152-160` prefetch-gate on `isAuthenticated()`.

### Schema check (ADR 0022 directive 2)
- N/A — pure e2e infrastructure.

## Edge cases & hidden requirements

- **`test.describe.configure({ mode: 'serial' })`** matters beyond `workers: 1`: it stops the describe block on first failure, protecting the realm-restore step from running against a half-mutated realm in a follow-on spec.
- **`withRealmPatch` + globalTeardown — both required**: try/finally covers thrown assertions; globalTeardown covers SIGKILL / Playwright timeout. Restore target is the value captured in `beforeAll`, NOT a hardcoded constant — protects against future ADR 0007 token-policy bumps.
- **`oidcSecurity.logoff()` not `logoffLocal()`** in the multi-tab spec: RP-initiated logout hits KC's `end_session_endpoint` and kills the SSO cookie; local-only logout leaves KC's SSO live and tab B's `checkAuth()` silently re-authenticates, masking the test.
- **`check-realm-shape.sh:83` equality check**: anchored equality, not subset. The script will fail loud on the `manage-realm` addition unless `EXPECTED_SA_ROLES` is updated in lockstep — that's the contract, keep it.
- **Hard-401 vs silent-refresh race**: the 60s pre-expiry silent renew (`auth.config.ts:48` `renewTimeBeforeTokenExpiresInSeconds: 60`) means user-disable-while-token-valid may surface as `SilentRenewFailed` (refresh grant denied for disabled user) rather than a direct API 401. URL-transition assertion accepts either path; observable outcome is identical.
- **Bearer-scoping noise**: vite source maps, hot-reload websockets, `/.well-known/openid-configuration`, JWKS. The partition predicate (above) classifies them as indifferent — don't expand assertion to "every request without Bearer", that's brittle.
- **Concurrency surface**: `--shard` flag in CI matrix, a second nightly job sharing the KC instance, or running `--project=real-idp` concurrently with anything else against the same realm would break realm-mutating specs. Document the invariant in `alpenflight/web/e2e/README.md` alongside the serial-describe note: real-idp is single-instance against one KC.

## Security plan

- **`manage-realm` scope expansion — dev + nightly-CI only.** The `assertLocalhostIssuer()` re-assertion per `adminRequest()` (S-174) is the boundary that contains the committed dev secret. Production posture (S-151 deployment): `alpenflight-backend-admin` MUST NOT carry `manage-realm` in any deployed realm; production realm shape is driven by committed-export + CI redeploy, not REST mutation. Surface this rule for the S-151 security plan.
- **`check-realm-shape.sh` enumerates the expanded role set.** The equality check (not subset) at line 83 catches any future drift — a silent addition of e.g. `manage-clients` would still fail the gate. Keep equality.
- **`setUserEnabled` predicate-guarded.** Same `isCleanupCandidate` gate as `deleteUser` — refuses on non-`e2e-*` candidates. Seed users can never be disabled by typo.
- **Realm-mutation blast radius.** `accessTokenLifespan` is realm-wide. `withRealmPatch` + globalTeardown safety net is the contract. Pinned `SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS` constant — single source.
- **Bearer-token values never persisted.** Spec asserts presence/absence only. S-174's `retain-on-failure` trace posture stays — do not extend.
- **Hard-401 timing acknowledgement.** KC user-disable does NOT instantly invalidate JWTs (offline validation per ADR 0007 + the realm's 15-min access tokens + refresh-token rotation). Document explicitly in the spec body so a reader doesn't expect instant invalidation; combine with `withRealmPatch({ accessTokenLifespan: SHORTENED })` to make the test deterministic in the nightly window.
- **No new CI secrets.** `ALPENFLIGHT_KC_ADMIN_CLIENT_SECRET` env-var unchanged. Default-dev-secret stderr-warning (S-174) covers the typo-on-override case.

## Test plan

- **Pyramid placement:** real-idp Playwright project only; opt-in nightly via the existing `alpenflight-e2e-real-idp.yml`. Mock-auth can't reach these flows (no real `exp`, no refresh, no KC SSO).
- **Parity:** N/A — greenfield; legacy FLS has no OIDC.
- **Spec layout:**
  - `tests/real-idp/token-lifecycle.spec.ts` with two `describe.serial` blocks: (a) silent-refresh + hard-401 (realm-mutating via `withRealmPatch`), (b) multi-tab logout + Bearer scoping (per-test user fixtures only).
  - `tests/real-idp/public-routes.spec.ts` (separate file; non-mutating; runs parallel to (b) in principle but `workers: 1` serializes anyway).
- **Mock vs live:** everything live. Zero `page.route()` interception.
- **Cleanup:**
  - Silent-refresh + hard-401 use `withRealmPatch({ accessTokenLifespan: 30 }, ...)` — auto-restore in `finally`. Plus globalTeardown safety net.
  - Hard-401 uses per-test `e2e-<uuid>` user → `afterEach` `deleteUser` (predicate-guarded). No re-enable.
  - Multi-tab logout + Bearer scoping use `pilot1` (read-only); no cleanup.
  - Public-route specs are anonymous; nothing to clean.
- **Stable assertions only:** URL host transitions, `page.on('request')` header presence/absence, decoded JWT `exp` arithmetic (silent-refresh asserts new `exp > original_exp`), HTTP status codes. NEVER: visible KC copy, screenshot diffs, internal SPA events.
- **Retry / timeout:** inherits project `retries: CI ? 1 : 0`, `timeout: 60_000`. Silent-refresh + hard-401 specs bump to `test.setTimeout(120_000)` (30s lifespan wait + assertion polling).
- **CI cadence:** nightly + workflow_dispatch (existing workflow). NOT a PR gate.

## Performance plan

(N/A — pure e2e test infrastructure; no hot path, no query budget. Wall-clock budgets are operational (`SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS = 30`, spec-local 120s timeout) and codified above.)

## Open design questions

The grill resolved every design fork; the items below are **AC drift surfaced for `/modernize-finalize`** — they require AC text edits that refine itself is out-of-scope for.

1. **Multi-tab AC wording — `browser.newContext()` is wrong.** Per `app.config.ts:39-44` (DefaultLocalStorageService) + Playwright's snapshot-vs-live semantics, the multi-tab spec MUST use `context.newPage()` (same Playwright BrowserContext) for shared live localStorage. **Action at finalize:** rewrite the multi-tab AC to "Use `context.newPage()` so both pages share live localStorage; logging out in tab A causes tab B's next authGuard-triggered navigation to find no tokens and redirect."
2. **Multi-tab AC mechanism reference — `BroadcastChannel` mention is misleading.** S-021's `oidc-session-bridge.ts` does not implement BroadcastChannel; cross-tab detection is library-internal (storage-poll on `checkAuth()`). **Action at finalize:** drop the "BroadcastChannel or storage-event subscription" sentence in the AC; replace with "asserted via URL transition in tab B after its next route navigation."
3. **Realm-export edit task — surface the `check-realm-shape.sh:83` companion edit.** Story tasks list mentions adding `manage-realm` to the SA role array but omits the lockstep `EXPECTED_SA_ROLES` update — the gate will fail loud otherwise. **Action at finalize:** add the companion task explicitly.

<!-- modernize-refine: end -->
