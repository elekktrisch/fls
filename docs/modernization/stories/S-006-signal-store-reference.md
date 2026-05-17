---
id: S-006
title: Reference NgRx Signal Store + session store skeleton
epic: E-01
status: in_progress
started_at: 2026-05-17
github_issue: 52
github_pr: 53
depends_on: [S-002, S-004]
acceptance:
  - A reference `SignalStore` (e.g. `HelloStore`) is committed under `next/web/src/app/hello/` demonstrating: `withState`, `withComputed`, `withMethods`, `rxMethod`, `withEntities` (if list-shaped), pagination, error/loading signals.
  - A `SessionStore` (or `AuthStore`) is scaffolded with `providedIn: 'root'`; exposes signals for `authenticatedUser`, `currentClubId`, `isClubAdmin`, `isSystemAdmin`.
  - A route-guard pattern (replacing legacy `userAuth` resolve) reads from the SessionStore; documented in `next/web/src/app/auth/README.md`.
  - A lint rule or code-review checklist asserts: components only inject stores, never call `HttpClient` directly.
estimate: M
adr_refs: [0006, 0007]
parity_test: none
refined: true
refined_at: 2026-05-17
refined_specialists: [requirements-engineer, solution-architect, qa-engineer, security-engineer, performance-engineer]
context7_last_checked: 2026-05-17
---

## Context
ADR 0006 chose NgRx Signal Store. Every domain follows the per-domain-store pattern. This story is the template all later domain stores copy.

## Acceptance criteria
See frontmatter.

## Tasks

Superseded by acceptance criteria.

## Notes
S-021 replaces the placeholder `SessionStore` body with real OIDC. The store shape stays.

The 2026-05-15b vision amendment (§F11/§F15/§F12 mobile-first directives) is folded natively into the refined design below — AC-DIR-1 (aggressive prefetch), AC-DIR-2 (offline-aware refetch), AC-DIR-3 (signal-driven conditional render), AC-DIR-4 (MutationBus + lint extension).

<!-- modernize-refine: start -->

## Design notes

### File layout

| Path | New / Touch | Role |
|---|---|---|
| `next/web/src/app/core/session/session.store.ts` | new | Root `SessionStore` — placeholder auth state + `bootstrapPrefetch()` seam + `sessionStatus` for loading-state disambiguation. |
| `next/web/src/app/core/session/session.guard.ts` | new | Functional `authGuard: CanActivateFn` reading `SessionStore.isAuthenticated()`. Default-deny. |
| `next/web/src/app/core/session/session.store.spec.ts` | new | Vitest logic-only spec. |
| `next/web/src/app/core/session/index.ts` | new | Barrel. |
| `next/web/src/app/core/mutation-bus/mutation-bus.ts` | new | `MUTATION_BUS` `InjectionToken<Subject<MutationEvent>>` + `MutationEvent` discriminated-union type. |
| `next/web/src/app/core/mutation-bus/README.md` | new | Convention doc — "domain stores subscribe via this bus, never inject sibling stores"; AC-DIR-4 review discipline; event-name convention. |
| `next/web/src/app/core/network-status/network-status.store.ts` | new | Wraps `fromEvent(window, 'online'|'offline')` into a `networkOnline()` signal — interim until S-117 PWA layer. |
| `next/web/src/app/auth/README.md` | new | Auth + SessionStore + guard convention + prefetch lifecycle + PII discipline. |
| `next/web/src/app/features/hello/hello.store.ts` | new | Reference `HelloStore` — state / computed / methods / rxMethod / hooks. |
| `next/web/src/app/features/hello/hello.store.spec.ts` | new | Vitest logic-only spec — load happy / error / offline paths, mutation-bus subscription. |
| `next/web/src/app/features/hello/hello.component.ts` | touch | Refactor: inject `HelloStore`; remove direct `helloResource()` call. |
| `next/web/src/app/app.config.ts` | touch | Provide `MUTATION_BUS` token (Subject instance) explicitly. |
| `next/web/CLAUDE.md` | touch | §4 (signals-first) — "components consume stores, not HttpClient"; "templates always invoke signal: `@if (store.showX())`, never `@if (store.showX)`"; new §5b "Refetch policy & prefetch contract"; §10 (don't list) — HttpClient injection in components. |
| `next/web/eslint.config.mjs` | touch | `no-restricted-imports`: ban `@angular/common/http` from `features/**/*.component.ts`; ban deep cross-feature `../*.store` imports from sibling feature stores. |
| `.github/workflows/ci.yml` | touch | Re-enable `pnpm test` in the `next-build` job (the first logic spec ships in this story). The `@angular/build:unit-test` builder rejects vitest's `--run` flag; in non-TTY CI watch defaults to `false`, so plain `pnpm test` is the correct invocation. |

No backend, no Flyway. Per ADR 0022 directive 2 — zero schema touch.

### `SessionStore` shape

```ts
// core/session/session.store.ts
export interface User {
  id: string;
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  clubId: string;
  roles: ReadonlyArray<'CLUB_ADMIN' | 'SYSTEM_ADMIN' | 'MEMBER'>;
}

// SECURITY: state declares ONLY claims-derived data. NEVER add access_token,
// refresh_token, or id_token here — those live in the OIDC library's storage
// layer (S-021 selects iframe vs cookie). Signals are trivially readable from
// dev tools.
type SessionState = {
  authenticatedUser: User | null;
  currentClubId: string | null;
  sessionStatus: 'idle' | 'loading' | 'authenticated' | 'unauthenticated';
  bootstrapStartedAt: number | null;
};

const initial: SessionState = {
  authenticatedUser: null,
  currentClubId: null,
  sessionStatus: 'idle',
  bootstrapStartedAt: null,
};

export const SessionStore = signalStore(
  { providedIn: 'root' },
  withState(initial),
  withComputed(({ authenticatedUser, sessionStatus }) => ({
    isAuthenticated: computed(() => sessionStatus() === 'authenticated' && authenticatedUser() !== null),
    isLoadingSession: computed(() => sessionStatus() === 'idle' || sessionStatus() === 'loading'),
    isClubAdmin:   computed(() => authenticatedUser()?.roles.includes('CLUB_ADMIN') ?? false),
    isSystemAdmin: computed(() => authenticatedUser()?.roles.includes('SYSTEM_ADMIN') ?? false),
  })),
  withMethods((store, bus = inject(MUTATION_BUS)) => ({
    // S-021 replaces the body; the signatures are the contract.
    login(user: User, clubId: string) {
      patchState(store, { authenticatedUser: user, currentClubId: clubId, sessionStatus: 'authenticated' });
    },
    logout() {
      patchState(store, { ...initial, sessionStatus: 'unauthenticated' });
      bus.next({ kind: 'session.logout' });   // domain stores self-clear
    },
    /** AC-DIR-1. Called by S-021's OIDC success handler + by tenant-switch UI.
     *  Today: gates on isAuthenticated; sets bootstrapStartedAt; logs a marker.
     *  Per-domain prefetch wiring lands in the first masterdata stories (S-047 +). */
    bootstrapPrefetch() {
      if (!store.isAuthenticated()) return;
      patchState(store, { bootstrapStartedAt: Date.now() });
      // TODO(S-021 + S-047): inject(AircraftsStore).loadAll(), inject(PersonsStore).loadAll(), …
      // — fire as `forkJoin` per-store prefetch; cancellation via session.logout bus event.
    },
  })),
);
```

**Placeholder discipline:** every method body opens with a `// TODO(S-021)` comment so reviewers see the seam at a glance. `sessionStatus` defaults to `'idle'` (not `'unauthenticated'`) so guards can distinguish "OIDC still resolving" from "definitely logged out" — prevents the race where a hard refresh redirects to `/login` mid-init.

### `HelloStore` shape (reference)

```ts
// features/hello/hello.store.ts
type Item = HelloResponse;

export const HelloStore = signalStore(
  { providedIn: 'root' },   // route-scoped variant documented in README
  withState({
    items: [] as Item[],
    selectedId: null as string | null,
    isLoading: false,
    loadError: null as string | null,        // per-method error per requirements-engineer
    saveError: null as string | null,        // placeholder for future mutations
    offline: false,
    lastRefreshedAt: null as number | null,
    filter: { query: '' },                    // ALWAYS init objects (DeepSignal trap)
    pagination: { page: 1, pageSize: 20, total: 0 },
  }),
  withComputed(({ items, loadError, saveError, pagination, filter }) => ({
    isEmpty:      computed(() => items().length === 0),
    hasError:     computed(() => loadError() !== null || saveError() !== null),
    pageCount:    computed(() => Math.max(1, Math.ceil(pagination.total() / pagination.pageSize()))),
    // AC-DIR-3 marker: a store-derived visibility signal templates bind via `@if`.
    showAdvanced: computed(() => filter.query().length > 0),
  })),
  withMethods((store, helloApi = inject(HelloService), bus = inject(MUTATION_BUS)) => ({
    setQuery(query: string) {
      patchState(store, (s) => ({ filter: { ...s.filter, query } }));
    },
    clear() {
      patchState(store, { items: [], loadError: null, offline: false });
    },
    loadHello: rxMethod<void>(pipe(
      tap(() => patchState(store, { isLoading: true, loadError: null })),
      switchMap(() => helloApi.hello().pipe(
        tapResponse({
          next: (r) => patchState(store, {
            items: [r], isLoading: false, offline: false, lastRefreshedAt: Date.now(),
          }),
          error: (e: HttpErrorResponse) => {
            if (e.status === 0) {
              // AC-DIR-2: network unreachable. S-117 hydrates from IndexedDB.
              patchState(store, { offline: true, isLoading: false });
              return;
            }
            patchState(store, { loadError: e.message, isLoading: false });
          },
        }),
      )),
    )),
    refresh() { this.loadHello(); },
    /** Optimistic-update template. /api/v1/hello has no mutation;
     *  first real demonstration lands at S-047 (Countries CRUD).
     *  Pattern:
     *    1. snapshot prev = items()
     *    2. patchState optimistic
     *    3. rxMethod POST; on error revert to prev + set saveError
     *    4. on success: bus.next({ kind: '<domain>.updated', id })
     */
    markFavorite(_id: string, _fav: boolean) { /* placeholder */ },
  })),
  withHooks({
    onInit(store, bus = inject(MUTATION_BUS)) {
      store.loadHello();
      bus.pipe(takeUntilDestroyed()).subscribe((evt) => {
        switch (evt.kind) {
          case 'session.logout':
          case 'session.tenantSwitch':
            store.clear();
            break;
          // refetch-on-mutation for sibling-domain events (none today)
        }
      });
    },
  }),
);
```

**Deferred patterns** (per the requirements-engineer's gap analysis — hello has no list / no id / no mutation):

- `withEntities` — needs `{ id: string | number }` on the entity. Hello lacks one. First real entity (S-047 Countries) introduces the pattern.
- Real pagination / sort / filter — pagination state is in place as a placeholder; first list endpoint exercises it.
- Optimistic update with rollback — pattern documented in `markFavorite` JSDoc; first real mutation (S-047) implements it.

Per ADR 0022 directive 1: don't fake list-shape against a single-record endpoint just to demonstrate. Document the deferral with `TODO(S-047)` markers so future implementers don't copy the placeholder.

### `MutationBus` shape

```ts
// core/mutation-bus/mutation-bus.ts
export type MutationEvent =
  | { kind: 'session.logout' }
  | { kind: 'session.tenantSwitch'; clubId: string }
  | { kind: 'aircraft.created'; aircraftId: string }
  | { kind: 'aircraft.updated'; aircraftId: string }
  | { kind: 'flight.booked'; flightId: string }
  // extend per new mutation surface — discriminated-union keeps consumers exhaustive
  ;

export const MUTATION_BUS = new InjectionToken<Subject<MutationEvent>>('MUTATION_BUS', {
  factory: () => new Subject<MutationEvent>(),
});
```

**Why `Subject`, not a `signalStore`:** events are fire-and-forget; a SignalStore would convert event-stream into latched state — wrong shape. Documented in `core/mutation-bus/README.md`.

**Event-name convention:** `<domain>.<past-tense-verb>` (e.g. `aircraft.created`). IDs only — no PII, no full entities, no tokens. The TS discriminated-union structurally prevents `payload`/`body`/`user` fields landing on event types.

### Route guard pattern

```ts
// core/session/session.guard.ts
export const authGuard: CanActivateFn = (route) => {
  const session = inject(SessionStore);
  const router  = inject(Router);

  if (route.data['publicAccess'] === true) return true;
  if (session.isLoadingSession()) {
    // Defer; the OIDC init effect (S-021) will re-trigger navigation when settled.
    return false;
  }
  return session.isAuthenticated() ? true : router.createUrlTree(['/login']);
};
```

`auth/README.md` documents:
- Default-deny posture (`isAuthenticated()` is `false` until `login()` is called — placeholder today, real OIDC in S-021).
- Public-flow opt-out via `data: { publicAccess: true }` on routes (landing, demo, /login).
- The `isLoadingSession()` branch prevents the "refresh redirects to login mid-init" race.
- Prefetch lifecycle (when, idempotence, cancellation, public-flow skip).

### Refetch policy convention (CLAUDE.md §5b)

| Domain class | Policy | Trigger | Budget |
|---|---|---|---|
| Masterdata (aircraft / persons / locations / flight-types / routes) | Cache-long; TTL ≈ 1h | `SessionStore.bootstrapPrefetch()` on auth + tenant-switch | < 1.5s all-parallel |
| Flights | Refetch-on-visibility | `document.visibilitychange` → `store.refresh()` | < 500ms p95 |
| Deliveries | Refetch-on-mutation | Subscribed to `MUTATION_BUS` `delivery.*` events | < 500ms p95 |
| Session-derived (current-user prefs) | One-shot | `bootstrapPrefetch` only | < 200ms |

No runtime registry; each store implements its own policy. The convention is the discipline.

### Aggressive-prefetch (AC-DIR-1)

`SessionStore.bootstrapPrefetch()` contract:
- **Caller:** S-021's OIDC callback handler after `login()`; tenant-switch UI after `currentClubId` change.
- **Idempotence:** `bootstrapStartedAt` state field gates repeat calls inside a session window.
- **Public flows skip:** routes with `data: { skipPrefetch: true }` bypass; `AppComponent` reads the active route's data and only calls bootstrap for prefetch-eligible flows.
- **Cancellation:** `logout()` emits `session.logout` on `MUTATION_BUS` → every domain store calls its own `clear()` → drops in-flight requests via `switchMap`'s built-in cancellation in `rxMethod`.
- **Parallelism:** uses RxJS `forkJoin([store1.loadAll(), store2.loadAll(), …])` with per-stream `catchError(() => of(null))` so one degraded endpoint doesn't stall the whole bootstrap.

### Offline-aware refetch (AC-DIR-2)

`HelloStore.loadHello`'s error branch:
- `HttpErrorResponse.status === 0` → `patchState(store, { offline: true, isLoading: false })`. Banner consumers read `store.offline()`.
- IndexedDB hydration hook — documented as a `TODO(S-117)` in the offline branch; PWA service worker owns the actual cache.

A `NetworkStatusStore` in `core/network-status/` wraps `fromEvent(window, 'online'|'offline')` into a `networkOnline()` signal as the interim signal — S-117 replaces with the service-worker-driven version. Domain stores can `inject(NetworkStatusStore)` and tie refresh-on-reconnect to `effect(() => { if (network.networkOnline()) store.refresh(); })`.

### Signal-driven conditional render (AC-DIR-3)

```html
<!-- features/hello/hello.component.ts template -->
@if (store.showAdvanced()) {
  <af-advanced-panel />
}
```

The `showAdvanced` signal is `computed()` inside the store, not a component-local signal. CLAUDE.md §4 gets a new bullet: **"Conditional visibility: when a render condition depends on domain state, expose it as a `computed()` on the store. Templates use `@if (store.showX())`. Avoid component-local signals that re-derive store state — duplicates the source of truth."**

Forward-looking: S-062c's flight-edit form drives N conditional sections (winch operator / observer / passenger / engine counters / invoice recipient / route fields) entirely from store-computed signals. The S-006 reference is the template.

### AC-DIR-4 lint + discipline

ESLint additions in `eslint.config.mjs`:

```js
{
  files: ['src/app/features/**/*.component.ts'],
  rules: {
    'no-restricted-imports': ['error', {
      paths: [{
        name: '@angular/common/http',
        message: 'Components consume Signal Stores, not HttpClient. See next/web/CLAUDE.md §4.',
      }],
    }],
  },
},
{
  files: ['src/app/features/**/*.store.ts'],
  rules: {
    'no-restricted-imports': ['error', {
      patterns: [{
        group: ['@features/*/!(index)', '../../**/*.store'],
        message: 'Domain stores do not import sibling stores. Coordinate via MUTATION_BUS. See core/mutation-bus/README.md.',
      }],
    }],
  },
},
```

The store-injects-store ban is structurally enforceable; the "bus-consumption-only" rule is the second half. `core/mutation-bus/README.md` carries the review-time discipline: "*If you're tempted to inject another domain store, emit/listen on `MUTATION_BUS` instead.*"

### Hello component refactor

```ts
@Component({
  selector: 'af-hello',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.isLoading()) {
      <p>Loading…</p>
    } @else if (store.hasError()) {
      <p class="text-red-600">Failed: {{ store.loadError() }}</p>
    } @else if (store.offline()) {
      <p>Offline — last refreshed {{ store.lastRefreshedAt() | date:'short' }}</p>
    } @else if (store.items()[0]; as r) {
      <h1 class="text-blue-600 text-3xl font-bold">{{ r.message }}</h1>
      <p>{{ r.timestamp | date:'medium' }}</p>
    }
    @if (store.showAdvanced()) {
      <p class="text-sm text-gray-500">Advanced view</p>
    }
  `,
})
export class HelloComponent {
  protected readonly store = inject(HelloStore);
}
```

No `helloResource()` call. No `HttpClient`. No `ngOnInit`. The store's `withHooks(onInit)` triggers the load.

### What this story does NOT include

- Real OIDC auth + interceptor (S-021).
- Real domain entities / list endpoints / `withEntities`-shaped stores (S-047 onward).
- PWA service worker + IndexedDB cache layer (S-117).
- Tenant-switching UI (future; bus contract is in place).
- First real mutation + optimistic-update demonstration (S-047 / S-049).
- `af-banner` atom for the offline banner — placeholder `<p>` only (S-008 ships atoms).
- Real bootstrap-latency measurement (S-047 + S-062c).
- Bundle-size CI gate (S-108).

### Alternatives considered

- **Built-in `resource()` / `rxResource()`** (ADR 0006 option A) — rejected per ADR 0006 to lock the per-domain Signal Store shape across all features.
- **Combined `MutationBus` as a SignalStore** vs `Subject` token — rejected; SignalStore latches state, but the bus is event-stream-shaped. `Subject` is the right primitive.
- **Cross-store coupling via direct injection** (StoreA injects StoreB) — rejected. Couples lifecycles + tests; ESLint pattern enforces. MutationBus is the seam.
- **Class-based `AuthGuard implements CanActivate`** — rejected. Angular 21 idiom is functional `CanActivateFn` + `inject()`.
- **Demonstrate `withEntities` against a synthesized id on `HelloResponse`** — rejected. Fakes the shape; copy-paste hazard. Defer real demonstration to S-047.

### Per ADR 0022 directive 2

Zero schema/migration touch. Frontend-only story.

## Edge cases & hidden requirements

- **Generated client choice (`HelloService` vs `helloResource()`):** the store injects `HelloService` (RxJS `Observable<HelloResponse>`) inside `rxMethod`'s `switchMap`. `helloResource()` returns a component-level `HttpResourceRef` — not DI-injectable; mixing the two surfaces in one store risks double-fetch. Document in store JSDoc.
- **`SessionStore` "loading vs no session" ambiguity:** without a `sessionStatus` signal, guards can't distinguish "OIDC still resolving" from "definitely logged out." Reference adds the `sessionStatus: 'idle' | 'loading' | 'authenticated' | 'unauthenticated'` signal; guard returns `false` (not redirect) in `'idle'`/`'loading'`.
- **`currentClubId` defaults to `null`, not `undefined`** — `undefined` breaks DeepSignal. Convention: always initialize objects.
- **Route guard idiom:** Angular 21 functional `CanActivateFn` via `inject()`. No class. `data.publicAccess === true` is the opt-out flag.
- **`bootstrapPrefetch` trigger:** explicit call from the OIDC success handler (S-021) + tenant-switch UI. Not an `effect` inside `withHooks(onInit)` — the effect approach couples to signal-change ordering during init; the explicit call is legible and S-021-testable.
- **Idempotence:** `bootstrapStartedAt` state field gates repeat calls inside the session window. TTL applies per-domain via each store's own `needsRefresh = Date.now() - lastRefreshedAt() > TTL` computed.
- **Cancellation on logout:** `MUTATION_BUS` `session.logout` event reaches every domain store's `onInit` subscription → `clear()` → `switchMap` in `rxMethod` cancels in-flight requests. No explicit `cancel()` method needed.
- **Tenant switch re-fetch:** `session.tenantSwitch` event handled identically to logout: `clear()` then `loadHello()`. Even though today's auth is single-tenant per session, the contract is in place for system-admin impersonation.
- **Offline detection ownership:** `NetworkStatusStore` interim wraps `window.online`/`window.offline` events. S-117 (PWA) replaces. Domain stores `inject(NetworkStatusStore).networkOnline()` to react to reconnect.
- **IndexedDB fallback:** documented as a `TODO(S-117)` hook in the `offline` branch of `loadHello`. orval emits no IDB code; out of scope for S-006.
- **Signal-driven conditional render + Reactive Forms** (forward to S-062c): the bridge is `toSignal(form.valueChanges, { initialValue: form.value })` inside `withMethods` or `withHooks`; a `computed()` derives `showWinchOperator`. Reference store demonstrates the basic shape via `showAdvanced = computed(() => filter.query().length > 0)`.
- **Template signal-call trap:** `@if (store.showX())` invokes the signal; `@if (store.showX)` treats the function reference as truthy and always renders. Convention pinned in CLAUDE.md §4.
- **`MutationBus` shape:** `InjectionToken<Subject<MutationEvent>>` with a `factory` providing `new Subject()` — root-scoped by default; `app.config.ts` may add explicit `providers: [{ provide: MUTATION_BUS, useValue: new Subject() }]` for discoverability.
- **Cross-store coupling ban:** structurally enforced by ESLint `no-restricted-imports` on `features/**/*.store.ts` → `'../../**/*.store'`. Review-time discipline note in `core/mutation-bus/README.md`.
- **`MutationBus` payload discipline:** IDs only. No PII, no tokens, no entity bodies. The TS discriminated-union structurally enforces.
- **`HttpClient` ban in components:** ESLint `no-restricted-imports` on `features/**/*.component.ts`. Generated client folder is already ignored by the ESLint config (`src/app/api/generated/**`) so orval-emitted `HelloService` is exempt.
- **Error signal granularity:** per-method (`loadError`, `saveError`). Computed `hasError` covers both. Per-method shape lets components distinguish "list failed to refresh" from "save failed."
- **DeepSignal trap:** never initialize state with `undefined` leaves. Always `{ query: '' }`, not `{ query: undefined }`.
- **`withEntities` typing:** requires `id: string | number`. `HelloResponse` lacks one — defer to first real entity (S-047 Country). Document as a forward-looking constraint.
- **`providedIn: 'root'` survives HMR;** component-scoped stores don't. Domain stores that must survive navigation are root-scoped (the reference). Wizard / flow stores that reset on exit are component-scoped (future).
- **TTL-based refetch:** masterdata stores expose `needsRefresh = computed(() => Date.now() - lastRefreshedAt() > TTL)` and only fire `load()` when true. TTL = 1h for masterdata, defined per store.
- **Logout cleanup pattern:** domain stores observe `MutationBus` for `session.logout` + self-clear. `SessionStore.logout()` doesn't need to know which domain stores exist.
- **Public-route prefetch skip:** `data: { skipPrefetch: true }` on the route definition. `AppComponent` reads from the active route snapshot before invoking `bootstrapPrefetch`.
- **`auth/README.md` scope:** SessionStore shape + placeholder note + functional guard pattern + loading-state handling + `bootstrapPrefetch` lifecycle + `data.publicAccess` / `data.skipPrefetch` route conventions + logout-cleanup. "How to add a guarded route" guide, not an exhaustive spec.
- **Vitest testing convention** (per `[FE tests: unit for logic, Playwright for DOM]` saved memory): all S-006 specs are logic-only — store state transitions, computed selectors, `rxMethod` happy/error/offline paths, guard URL-tree logic, MutationBus invalidation subscriber. No `TestBed.createComponent` with DOM assertions.
- **Re-enable `pnpm test` in CI:** S-006's first logic spec lands the trigger to flip the disabled step in `.github/workflows/ci.yml` back on. The `--run` flag from upstream vitest doesn't pass through the `@angular/build:unit-test` builder; plain `pnpm test` is the right invocation under CI's non-TTY environment.

## Security plan

### Threat model

| Risk | Severity | Mitigation in S-006 |
|---|---|---|
| `SessionStore` placeholder mistaken for real auth. | High | Every method body opens with `// TODO(S-021)`; `isAuthenticated()` returns `false` until `login()` is called explicitly. `auth/README.md` opens with a "Placeholder until S-021" banner. |
| `authGuard` placeholder allows authenticated routes by default. | High | Default-deny: returns `UrlTree('/login')` unless `data.publicAccess === true`. S-021 inverts the predicate; the default-deny shape stays. |
| Token leak through SessionStore signals (forward-looking). | High | State type signature includes ONLY claims-derived data — `username`, `email`, `firstName`, `lastName`, `clubId`, `roles[]`. Raw `access_token` / `refresh_token` / `id_token` explicitly excluded. `// SECURITY:` block-comment locks the shape S-021 inherits. |
| `localStorage` / `sessionStorage` writes inside SessionStore or HelloStore. | Medium | ESLint `no-restricted-globals` (already in place from S-002) bans both. Placeholder uses in-memory `signal()` state only. S-021 is the only allowlist (typed wrapper). |
| `MutationBus` event payload leaks data. | Low | Bus events are `{ kind: '<domain>.<verb>', <id>: UUID }` shapes — IDs only. TS discriminated-union structurally bans `payload`/`body`/`token`/`user` fields. `core/mutation-bus/README.md` codifies. |
| Components calling `HttpClient` directly bypass the (S-021) auth interceptor. | Medium | ESLint `no-restricted-imports` denies `@angular/common/http` from `features/**/*.component.ts`. Generated client folder is already ignored (orval-emitted `HelloService` exempt). |
| Cross-tenant signal leak via `providedIn: 'root'` stores after tenant switch / logout. | High | `HelloStore.withHooks(onInit)` subscribes to `MUTATION_BUS` events `session.logout` + `session.tenantSwitch` and calls `clear()`. Documented as "every domain store MUST do this" in `auth/README.md`. |
| `bootstrapPrefetch()` fires before authentication completes or with the wrong tenant. | High | Gated on `isAuthenticated()` first. Server-side `@TenantId` (ADR 0008) resolves the tenant from the JWT — the FE never sends a tenant ID on the wire. In-flight requests cancelled via `MUTATION_BUS` on principal change. |
| Offline cache (IndexedDB) leaks data across logins on a shared device. | Medium (forward) | Out of scope for S-006. `auth/README.md` documents the requirement; S-117 (PWA service worker) test plan asserts cache purge on logout. |
| `@if (store.showWinchOperator())` template binding leaks role info into DOM. | Low | Angular `@if` removes the DOM subtree entirely (not `display:none` / `[hidden]`). Convention pinned in CLAUDE.md §4. |
| Error tracker (S-034 Glitchtip) exfiltrates SessionStore PII via breadcrumbs. | Medium (forward) | `auth/README.md` documents requirement: S-034 integration MUST configure `beforeSend` scrubber stripping `email`/`username`/`firstName`/`lastName`. |

### Authorization

- **`authGuard` (placeholder):** default-deny. Public routes opt in via `data.publicAccess === true`. S-021 inverts the body; the contract stays.
- **`roleGuard(role)` (deferred to S-021 / S-026):** reads `SessionStore.roles()`; placeholder roles is empty so role-gated routes are denied.
- **Store mutation methods:** no client-side authorization. Server enforces via `@PreAuthorize` (S-020 / S-026). Stores don't transmit `clubId` — server resolves from JWT principal.
- **UI role checks (`isClubAdmin()`):** advisory only. Server is authoritative. Document: "never gate a write client-side and skip the corresponding backend check."

### Input validation

- N/A in this story (Hello has no user input). Forward-looking: store mutation methods accept already-validated typed values; the generated OpenAPI TS client (S-004) enforces structural validity. Stores DON'T re-validate; the server is the boundary.
- AC-DIR-3 conditional render reads server-derived state, never raw user input. `[innerHTML]` already banned (CLAUDE.md §10).

### PII handling

- **SessionStore field surface** (forward-looking — shape set in this story so S-021 inherits correctly):
  - `username`, `email`, `firstName`, `lastName` — PII (Swiss FADP / GDPR). Never log; scrub from error-tracker breadcrumbs.
  - `clubId` — operational; not PII in isolation but a linkability risk if logged together with user-identifying fields.
  - `roles[]` — operational, not PII.
- `HelloStore.lastRefreshedAt` — timestamp, not PII.
- Code-review checklist (in `auth/README.md`): "any new SessionStore field gets a PII tag + logging policy."
- `MUTATION_BUS` event payloads: IDs only; structurally enforced by TS union.

### Audit-log events

- N/A. Frontend never emits audit events. Server is authoritative (S-027 owns the audit channel). `MUTATION_BUS` is client-side fan-out for cache invalidation; NOT an audit channel. Documented in `core/mutation-bus/README.md`.

### Cross-tenant leakage

- **Server side:** Hibernate `@TenantId` (ADR 0008) auto-filters tenant-scoped queries.
- **Client side (this story's dominant concern):** `providedIn: 'root'` stores survive logout / tenant switch and retain stale data unless cleared. Reference pattern: subscribe to `MUTATION_BUS` events → `clear()` → drops in-flight via `switchMap`.

### OWASP applicability

- **A01 Broken Access Control:** applies — placeholder guard could default wrong direction. Mitigation: default-deny + opt-in `publicAccess`.
- **A02 Cryptographic Failures:** applies forward — SessionStore type signature excludes raw tokens.
- **A04 Insecure Design:** applies — tenant-aware lifecycle (cancel on logout, refetch on tenant switch) designed-in from day one.
- **A07 Identification & Authentication Failures:** dominant. Default-deny is the structural fix to R10's "no global 401 handler."
- **A09 Security Logging & Monitoring Failures:** applies forward — S-034 must scrub SessionStore fields.

### CI / pre-commit guards

- ESLint `no-restricted-imports` denying `@angular/common/http` from `features/**/*.component.ts` (lands in this story).
- ESLint `no-restricted-imports` denying deep cross-feature `*.store` imports (lands in this story).
- ESLint `no-restricted-globals` for `localStorage` / `sessionStorage` (already in S-002).
- Code-review checklist in `auth/README.md`:
  1. Any new `SessionStore` field has a PII classification + logging policy.
  2. Any new domain store subscribes to `session.logout` + `session.tenantSwitch`.
  3. Any new `MUTATION_BUS` event carries IDs only.
  4. Any route added without `data.publicAccess` defaults to authenticated.

## Test plan

### Pyramid
- Unit: 12 vitest specs — HelloStore state machine (load happy / error / offline / debounce / optimistic rollback), SessionStore (initial state + logout + computed roles), authGuard (URL-tree logic), MutationBus invalidation subscriber.
- Integration: 0 — store HTTP seam is a spy-replaced service inside `TestBed`; no DB / no HTTP server.
- E2E: 1 Playwright spec — `hello-store.spec.ts` asserting loading → content render via `page.route` mock (may fold into existing `hello.spec.ts` if overlap total).
- Parity: 0 (`parity_test: none`; greenfield).

### Tests

All specs live alongside the subject file. All use `TestBed` + `provideZonelessChangeDetection()`.

**HelloStore (`hello.store.spec.ts`):**

1. `loadHello transitions isLoading false→true→false; items populated on success` — spy `HelloService` returns deterministic `HelloResponse`; assert state transitions.
2. `loadHello sets loadError on HTTP failure` — spy throws; assert `loadError()` populated, `isLoading()` false.
3. `loadHello sets offline:true on status-0 HttpErrorResponse` — spy throws `HttpErrorResponse({ status: 0 })`; assert `offline()` true.
4. `refresh clears loadError before refetch` — sequence: error → refresh; assert `loadError()` null at second-fetch start.
5. `setQuery debounces — 1 HTTP call per N rapid inputs` — `TestScheduler` virtual time; fire 5 calls inside `debounceTime`; assert spy called once. (Marked `test.skip` if `setQuery` debounce path isn't in S-006 scope.)
6. `markFavorite optimistic patch then rollback on error` — spy mutation throws; assert state patched then reverted. (Placeholder; the real spec lands with first mutation in S-047.)

**SessionStore (`session.store.spec.ts`):**

7. `Initial state: isAuthenticated false, sessionStatus 'idle', currentClubId null`.
8. `logout emits session.logout on MUTATION_BUS and resets state` — provide real `Subject<MutationEvent>` as token; assert `bus.next` called.
9. `isClubAdmin computed: true when roles include CLUB_ADMIN` — `patchState` to seed roles; assert computed.
10. `isSystemAdmin computed: same shape`.

**authGuard (`session.guard.spec.ts`):**

11. `Returns true when route data.publicAccess === true regardless of isAuthenticated`.
12. `Returns UrlTree('/login') when not authenticated and route is private`.
13. `Returns false (defer) when sessionStatus === 'idle'|'loading'` — prevents the OIDC-init race.

**MutationBus integration (in `hello.store.spec.ts`):**

14. `HelloStore subscribes to MUTATION_BUS and clears on session.logout` — emit event on real Subject; assert spy `HelloService.hello` not called again; assert `items()` empty.

### Fixtures

- **`helloServiceFactory(result: 'success' | 'error' | 'offline'): Provider`** — returns spy provider for `HelloService`.
- **`mutationBusFactory(): { provider: Provider; subject: Subject<MutationEvent> }`** — real `Subject` (vitest spy doesn't implement Observable contract; `.pipe()` throws).
- `TestBed.configureTestingModule` + `afterEach(() => TestBed.resetTestingModule())` — prevents `providedIn: 'root'` state leak across tests.

### Playwright e2e

- `hello-store.spec.ts` at `next/web/e2e/tests/`: `page.route('**/api/v1/hello', ...)` mock; navigate to `/hello`; assert loading visible briefly, content renders, offline banner shows when route mocked with `fulfill({ status: 0 })`. Note overlap with existing `hello.spec.ts` — fold rather than duplicate if patterns coincide.

### CI wire-up

S-006 re-enables `pnpm test` in `.github/workflows/ci.yml` (S-002 disabled it because the repo had no logic specs at the time and `ng test` exits 1 on "no tests found"). One-line addition before the existing `Lint + format + build next/web` step. Plain `pnpm test`, not `pnpm test --run` — the `@angular/build:unit-test` builder rejects the upstream-vitest `--run` flag; in CI's non-TTY environment, watch defaults to `false`.

### Coverage gaps (deferred)

- Real OIDC token validation → S-021.
- `SessionStore.login()` round-trip with real claims → S-021.
- First paginated domain store (`withEntities` + server-side sort/filter) → S-047 / S-049.
- IndexedDB-fallback round-trip → S-117 (PWA).
- Playwright cross-feature auth → protected-route integration → S-109 / S-110.
- `bootstrapPrefetch()` parallel-fetch cancellation under tenant switch → S-047 (first real domain store).

### Risks

- `rxMethod` + `TestScheduler`: zoneless Angular doesn't patch timers like zoned; the debounce test must use marble syntax explicitly, not `fakeAsync` alone.
- `providedIn: 'root'` stores cache across `TestBed.configureTestingModule` calls; `afterEach` reset is the convention.
- `MUTATION_BUS` token must be a real `Subject`, not a vitest spy — `pipe()` requires the Observable contract.
- `HttpErrorResponse({ status: 0 })` sentinel may differ under `withFetch()` (Angular's fetch backend) vs XHR. Smoke locally before committing the offline branch.

### Parity strategy

`parity_test: none`. Stores are state-shape; no legacy oracle.

## Performance plan

### Hot paths
- **Initial bootstrap (post-auth):** `SessionStore.bootstrapPrefetch()` fires ~5 parallel GETs (aircraft / persons / locations / flight-types / routes) via `forkJoin`. Budget: < 1.5s all-parallel on broadband (well within page-load p95 < 3s NFR).
- **Per-store refetch:** visibility-triggered (flights), mutation-triggered (deliveries), TTL-driven (masterdata). Budget: p95 < 500ms per call (matches NFR API-latency target).
- **Reactive recompute:** ~50 stores × ~10 computed each. @ngrx/signals computeds are pull-based + cached; untouched ones cost zero. Single state update propagates in < 16ms.

### HTTP-level optimizations
- `forkJoin` (parallel) for `bootstrapPrefetch()`; per-stream `catchError(() => of(null))` so one slow endpoint doesn't stall bootstrap.
- Per-store TTL cache: `needsRefresh = computed(() => Date.now() - lastRefreshedAt() > TTL)`. TTL = 1h for masterdata.
- `rxMethod` debouncing on filter-driven loads: `debounceTime(300) + distinctUntilChanged() + switchMap()`. Reference `HelloStore.setQuery()` demonstrates.
- `switchMap` not `mergeMap` for filter loads — cancels in-flight on next input.
- `forkJoin` gotcha: every inner observable must complete; never-completing streams hang bootstrap. Document in JSDoc.

### State-shape optimization
- `withEntities` for collections > 100 items: O(1) `entityMap[id]` lookup; O(N) iteration only when explicitly needed.
- Avoid `Array.prototype.find()` / `.filter()` in computeds over large collections — re-runs on every entity-shape change. Use `entityMap`.
- Avoid `JSON.stringify` in computeds — re-runs every parent change; defeats caching.
- Keep state shape flat; nested objects re-render subtree on shallow updates.

### Bundle size
- `@ngrx/signals` ~12 KB gzipped + `@ngrx/signals/entities` ~3 KB + `@ngrx/operators` ~2 KB ≈ +17 KB. Well inside the page-load p95 budget on marginal 3G (vision §F12).
- Don't install `@ngrx/store` / `@ngrx/effects` / `@ngrx/component-store` — Signal Store is standalone. PR review check.
- Capture post-S-006 `main-*.js` size as baseline for S-108.

### Memory
- `providedIn: 'root'` stores live for the entire app session. ~50 stores × small state ≈ low single-digit MB.
- `withEntities` over 10k+ rows = ~5MB depending on row shape. For flight-history (50k+ rows over years), reference must demonstrate **server-paginated views, NOT eager `setAll`**.
- `HelloStore.lastRefreshedAt: number` — 8 bytes. Trivial.

### Performance test plan
- **`rxMethod` debounce gate (lands in S-006):** vitest spec asserts single HTTP call after N rapid `setQuery` inputs.
- **Reactive-recompute synchronicity (lands in S-006):** vitest spec asserts computed updated within same tick (zoneless = synchronous).
- **Bootstrap latency:** Playwright timing assertion. **Deferred to S-047 + S-062c** (needs real masterdata + flight-edit form).
- **Bundle-size sanity check:** capture baseline kB in this story's review section. **Gate enforcement → S-108.**

### Risks
- `forkJoin` blocks on slowest endpoint — mitigated by `catchError(() => of(null))` per stream.
- Eager bootstrap on public/auth flows — mitigated by `isAuthenticated()` gate + `data.skipPrefetch` route flag.
- Stale cache after long idle (TTL only fires on next access) — masterdata is low-churn; flights uses visibility-refetch.
- Cross-tenant `withEntities` leak after switch — full reload intentional per ADR 0008.
- Computed fan-out cliff — keep computeds narrowly scoped to their store's state slice.

### Out of scope (deferred)
- Real bootstrap-latency measurement → S-047 + S-062c.
- Bundle-size CI gate → S-108.
- IndexedDB / service-worker cache → S-117.
- p99 measurement under sustained load → S-108.
- Worker-thread offload → not needed at current scope.

<!-- modernize-refine: end -->
