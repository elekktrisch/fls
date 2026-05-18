# Auth + SessionStore + guard

S-021 wired real OIDC. The SessionStore is now the single read seam for
app code; OIDC is the source of truth (via `OidcSessionBridge`). The
deep-dive on the OIDC integration itself lives in
`src/app/core/auth/README.md`.

## SessionStore (`core/session/session.store.ts`)

Root-scoped (`providedIn: 'root'`). Holds claims-derived state only — never
raw tokens. Field surface today:

| Field | Type | Notes |
|---|---|---|
| `authenticatedUser` | `User \| null` | `id`, `username`, `email`, `firstName`, `lastName`, `clubId`, `roles[]`. All of `username`/`email`/`firstName`/`lastName` are PII (FADP / GDPR). |
| `currentClubId` | `string \| null` | Tenant binding. Server enforces tenant from JWT — UI value is advisory. |
| `sessionStatus` | `'idle' \| 'loading' \| 'authenticated' \| 'unauthenticated'` | `'idle'` ≠ `'unauthenticated'` so guards can distinguish "OIDC still resolving" from "definitely logged out." |
| `bootstrapStartedAt` | `number \| null` | Idempotence gate for `bootstrapPrefetch()`. |

Computed signals (read-only views): `isAuthenticated`, `isLoadingSession`,
`isClubAdmin`, `isSystemAdmin`.

Methods:

- `login(user, clubId)` — promotes status to `'authenticated'`. Called by
  `OidcSessionBridge` when `oidcSecurity.userData()` emits valid claims.
- `logout()` — resets to `'unauthenticated'` and fires `session.logout`
  on `MUTATION_BUS`. Every domain store clears on this event. Wired to
  `PublicEventsService.SilentRenewFailed` and the `/auth/logout` route.
- `markUnauthenticated()` — cold-start path; settles status to
  `'unauthenticated'` without firing the bus event (no domain stores to
  clear). Exits the guard's loading-defer branch so
  `oidcSecurity.authorize()` can fire.
- `bootstrapPrefetch()` — AC-DIR-1 seam; only fires when authenticated,
  stamps `bootstrapStartedAt`. Wires real per-domain prefetch at S-047+.

### PII policy

Code-review checklist for every new field on `SessionStore`:

1. Classify (PII / operational / token).
2. Audit logging: never log PII; scrub from breadcrumbs (S-034 GlitchTip).
3. Tokens NEVER live here — they go in the OIDC library's storage layer.

## `authGuard` (`core/session/session.guard.ts`)

Functional `CanActivateFn`, default-deny. Decision table:

| Route data | sessionStatus | Result |
|---|---|---|
| `publicAccess === true` | any | `true` |
| any | `idle` / `loading` | `false` (defer; OIDC init settles via the bridge) |
| not public | `authenticated` | `true` |
| not public | `unauthenticated` | `oidcSecurity.authorize()` + `false` (hard redirect to Keycloak) |

The `false` (defer) branch normally never fires under the cold-start
path — `withAppInitializerAuthCheck()` blocks bootstrap until `checkAuth()`
resolves, and the bridge then calls `login()` or `markUnauthenticated()`
synchronously. It guards against post-init resolves (e.g. browser back
into an in-flight navigation).

### Adding a route

```ts
{
  path: 'flights',
  canActivate: [authGuard],
  loadChildren: () => import('@features/flights/flights.routes').then(m => m.FLIGHTS_ROUTES),
}
```

Public flows opt out via `data: { publicAccess: true }`. Prefetch-eligible
flows (post-auth landing) MAY annotate `data: { skipPrefetch: false }` —
defaults to skipping when no flag is present, so `AppComponent` calls
`bootstrapPrefetch()` only when the active route asks for it.

## MUTATION_BUS

See `core/mutation-bus/README.md`. Every domain store subscribes to
`session.logout` + `session.tenantSwitch` and clears its state. The bus is
the only legitimate seam between domain stores — never `inject(OtherStore)`
from a sibling store (lint enforces).

## Prefetch lifecycle

```
        login() ──▶ bootstrapPrefetch()
                     │
                     ▼
              forkJoin([
                aircraftsStore.loadAll(),  ◀── per ADR 0006 follow-up
                personsStore.loadAll(),
                locationsStore.loadAll(),
                flightTypesStore.loadAll(),
                routesStore.loadAll(),
              ])
              with per-stream catchError(() => of(null))

         logout() ──▶ MUTATION_BUS.next({ kind: 'session.logout' })
                     │
                     ▼
                each domain store .clear()
                rxMethod switchMap cancels in-flight requests
```

## Public flow skip

`/landing` and `/auth/*` (callback + logout) declare
`data: { publicAccess: true }`. The default-deny guard returns `true`
for them and `AppComponent` skips the prefetch call. Unauthenticated
visits to any other route trigger `oidcSecurity.authorize()` — a hard
redirect to Keycloak — instead of a local `/login` URL.

## Cross-tenant safety (forward-looking, S-021/S-047)

- `providedIn: 'root'` stores survive logout / tenant switch. Every store
  MUST subscribe to the bus and `.clear()` on `session.logout` +
  `session.tenantSwitch`.
- Server-side `@TenantId` (ADR 0008) auto-filters; the FE never sends a
  tenant ID on the wire — the server resolves it from the JWT.

## Code-review checklist

1. Any new `SessionStore` field has a PII classification + logging policy
   (this README) and a `// SECURITY:` comment when handling auth context.
2. Any new domain store subscribes to `session.logout` +
   `session.tenantSwitch` and calls `clear()`.
3. Any new `MUTATION_BUS` event carries IDs only — no PII, no tokens, no
   entity bodies.
4. Any route added without `data.publicAccess === true` defaults to the
   `authGuard` and is therefore default-deny.
