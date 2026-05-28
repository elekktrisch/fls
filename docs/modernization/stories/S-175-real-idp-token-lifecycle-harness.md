---
id: S-175
title: Playwright e2e — real-IdP token lifecycle (silent refresh, multi-tab logout, hard 401, Bearer scoping)
epic: E-13
status: done
started_at: 2026-05-28
done_at: 2026-05-28
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

S-174 shipped the real-IdP Playwright harness (helpers, `real-idp` project,
nightly workflow) with register + login + locale + Google-redirect coverage.
S-175 picks up the four token-lifecycle items that the mock-auth project
cannot exercise — silent refresh races against the OIDC library, multi-tab
session linkage via shared live `localStorage`, hard-401 redirects triggered
by admin-disabled users, and Bearer-token scoping to `/api/v1/*` only. No new
infrastructure; reuses S-174's helpers and runs in the same nightly lane.

## Acceptance criteria

### Silent refresh

- [x] Spec mutates the realm via the Admin REST API to shorten
      `accessTokenLifespan` to a small value, logs in, waits past the
      access-token expiry, and asserts the SPA stays authenticated and
      that an authed-only route still renders.
- [x] After the spec, the prior `accessTokenLifespan` value is restored
      automatically via the `withRealmPatch` HOF's try/finally — the
      pre-patch value is snapshotted, never hardcoded.
- [x] `globalTeardown` is the safety net for SIGKILL: re-fetches
      `accessTokenLifespan` and PUTs the canonical value back if drifted.

### Multi-tab logout

- [x] Two `context.newPage()` calls from the SAME Playwright
      `BrowserContext` share live `localStorage` — that's where the OIDC
      client persists tokens (`AbstractSecurityStorage` →
      `DefaultLocalStorageService` binding in `app.config.ts`). NOT
      `browser.newContext({ storageState })` — that's a snapshot, not a
      live-storage subscription.
- [x] Tab A invokes the SPA's user-visible logout (RP-initiated
      `oidcSecurity.logoff()` via the `/auth/logout` route, NOT
      `logoffLocal()`). Tab B's next route navigation finds no tokens
      and redirects to login or to the public landing — asserted via
      URL transition, not by reaching into the OIDC session bridge.

### Hard 401 redirect

- [x] Spec logs in as a per-test ephemeral `e2e-<uuid>` user, disables
      the user via the Admin REST API, then waits for the next refresh
      rotation. The 401 from the resource server OR the
      `SilentRenewFailed` from the refresh-grant denial both surface as
      an SPA URL transition (KC login OR public landing).
- [x] Cleanup: user is deleted in the spec's `finally` block via the
      predicate-guarded helper. No re-enable.

### Bearer scoping

- [x] Spec inspects outbound requests via `page.on('request')` during
      normal app navigation. Asserts:
      - Every `/api/v1/*` request carries `Authorization: Bearer <jwt>`.
      - No request to the Keycloak origin carries an `Authorization`
        header.
      - Both partitions populate (empty observed-list = spec bug).

### Public-route-stays-public

- [x] Anonymous navigation to every `publicAccess: true` route
      (`/`, `/signup`, `/scenic-flight`, `/discovery-flight`,
      `/auth/callback`, `/auth/logout`) does NOT redirect to KC AND
      triggers no `/api/v1/*` calls (the SessionStore prefetch is gated
      on `isAuthenticated()`; a regression there would surface here).

### CI

- [x] Specs land under `tests/real-idp/` and are picked up by the
      existing `--project=real-idp` filter. No new workflow file.
- [x] Realm-mutating specs are wrapped in
      `test.describe.configure({ mode: 'serial' })` so they don't race
      other specs in the project's `workers: 1` queue.

## Cross-story contracts produced

The helper additions in `_helpers/keycloak-admin.ts` are reusable by future
stories that need realm-policy mutation or per-user `enabled` toggling:

- `withRealmPatch<T>(partial, fn)` — snapshot-apply-restore HOF; refuses to
  patch a key with no prior realm value (the silent-no-op trap from
  `JSON.stringify` stripping `undefined`).
- `setUserEnabled(id, enabled, emailForGuard)` — predicate-guarded
  identically to `deleteUser`; payload pinned to `{ enabled }` only.
- `SHORTENED_ACCESS_TOKEN_LIFESPAN_SECONDS` constant.
- `globalTeardown`'s `restoreCanonicalAccessTokenLifespan` safety net.

## Notes

- The `manage-realm` role on `alpenflight-backend-admin`'s service account is
  a dev + nightly-CI privilege only — the `assertLocalhostIssuer()` guard
  contains it. Production posture (S-151 deployment) MUST NOT carry
  `manage-realm`; the realm shape is driven by committed-export + CI
  redeploy, not REST mutation.
- The serial-describe + single-instance invariant for realm-mutating specs
  is documented in `alpenflight/web/e2e/README.md` § Realm-mutating specs.
