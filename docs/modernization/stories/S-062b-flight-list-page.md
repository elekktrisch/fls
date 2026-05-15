---
id: S-062b
title: Flight list page (paginated, filterable)
epic: E-07
status: todo
depends_on: [S-062a, S-006, S-008]
acceptance:
  - `/flights` route renders a paginated `<fls-data-table>` over `POST /api/v1/flights/search` (the endpoint built in S-062a).
  - Filter bar covers the legacy filter set: immatriculation substring, pilot name substring, flight date range, `FlightAirState` dropdown, `FlightProcessState` dropdown, FlightType, StartLocation. Filters round-trip via `FlightSearchFilterDto`.
  - `FlightStore` (NgRx Signal Store, per S-006) wraps list state: `withEntities` for paginated rows, page/size/filter/sort persisted to `TableSettingsCache` (one settings bucket per user × table, parity with legacy `TableSettingsCacheFactory`).
  - Refetch policy: refetch on route re-visit, refetch on mutation event (broadcasted by S-062c), refetch on `clubId` switch (system-admin impersonation safety — see Performance plan).
  - "New flight" button navigates to `/flights/new` (handed off to S-062c). "Edit" row action navigates to `/flights/:id`. "Copy" row action navigates to `/flights/copy/:id`. Routes wired; destinations rendered by S-062c.
  - List shows computed `FlightAirState` (from S-060 via the DTO) — not stored on the entity.
  - Selector adaptation: every interactive element has a stable `data-testid` so S-109 can port the legacy Playwright list smoke without surgery.
  - p95 cold-cache page-load for `/flights` < **3s on throttled Fast 3G** (Playwright + browser-perf-trace, per S-108 baseline).
  - k6 load: 10 VUs, ramp 0→10 over 30s, hold 5 min on the search endpoint with mixed filters. Pass: p95 < 250ms, p99 < 500ms, error < 0.1%.
estimate: M
adr_refs: [0005, 0008]
parity_test: tests/flights/01-flights-list-smoke.spec.ts (handoff to S-109; selector adaptation only)
refined: true
refined_at: 2026-05-14
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
split_from: S-062
---

## Context

Second of three sub-stories splitting the original S-062 (see [S-062a](S-062a-flight-crud-backend.md) and [S-062c](S-062c-flight-edit-forms.md)). The backend list endpoint is already green from S-062a; this story builds the SPA list page on top — paginated table, filter bar, FlightStore skeleton, routing entry points for the create/edit flows S-062c will fill in.

Ships independently of S-062c: a user can browse and filter flights but cannot yet edit them (clicking a row navigates to a placeholder route that S-062c replaces).

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] `flight-routes.ts` — Angular standalone route config: `/flights` (list), `/flights/new` (placeholder), `/flights/:id` (placeholder), `/flights/copy/:id` (placeholder). Placeholders show a "coming in S-062c" message + link back to `/flights` for reviewer sanity until S-062c lands.
- [ ] `FlightStore` skeleton — `withEntities<FlightListItem>`, `withState({ page, size, sort, filter, loading, error })`, `withMethods({ loadPage, applyFilter, applySort, invalidate })`. Detail-state slice deferred to S-062c.
- [ ] `services/flight-api.ts` — thin wrapper over the orval-generated client. Isolates the store from generated-client churn.
- [ ] `pages/flight-list/flight-list.component.ts` + `.html` — paginated `<fls-data-table>`, filter bar, "new flight" button.
- [ ] `pages/flight-list/flight-list-filter.ts` — typed filter form (Reactive Forms).
- [ ] `pages/flight-list/filter-dropdowns/` — `<air-state-filter>` and `<process-state-filter>` components mirroring legacy `AirStateFilterDropdownDirective` / `ProcessStateFilterDropdownDirective`.
- [ ] `TableSettingsCache` integration — persist page/size/sort/filter per user × table to local storage (per S-008 primitives kit pattern).
- [ ] Refetch hooks: route entry (on visibility), `MutationBus` event subscription (broadcast by S-062c on save/delete), `clubId` change.
- [ ] Vitest unit tests for the FlightStore (filter round-trip, mutation invalidation, clubId switch cache wipe).
- [ ] Playwright smoke spec `01-flights-list-smoke.spec.ts` — login → `/flights` → assert at least one row from fixture → apply a filter → assert row count decreases → clear filter. Built fresh in this story; S-109 absorbs into its corpus.
- [ ] k6 load script + CI hook (consumed by S-111).

## Notes

**Estimate calibration (M):**
- 1 page + 1 store + 1 api wrapper + 2 filter dropdowns + 1 filter form = ~6 components.
- ~3 unit tests (store), ~1 e2e smoke.
- ~250 lines of legacy code referenced (`FlightsController.js:50-150` for list mechanics, filter directives, `TableSettingsCacheFactory`).
- No backend work (S-062a owns it); no form mechanics (S-062c owns those).

**Why this story is M not S:** the data-table + signal-store + filter-bar + table-settings-cache + refetch-policy stack is the first instantiation of these patterns for a paginated list in the new SPA. Subsequent CRUD pages (reservations, deliveries, etc.) crib from this. Worth investing in the right abstraction.

**Out of scope:**
- Form-driven flows (S-062c).
- Bulk operations (no parity equivalent in legacy).
- Server-side export to Excel (S-093 inventory; S-095 if listed there).

<!-- modernize-refine: start -->

## Design notes

### Module layout — client-side only

`next/web/src/app/flights/`:
- `flight-routes.ts` — Angular standalone route config (`/flights`, `/flights/new` placeholder, `/flights/:id` placeholder, `/flights/copy/:id` placeholder).
- `flight.store.ts` — single `FlightStore` (`withEntities` for list slice; **detail slice added by S-062c**).
- `services/flight-api.ts` — thin wrapper over the orval-generated client.
- `pages/flight-list/flight-list.component.ts` + `.html` — paginated `<fls-data-table>`, filter bar, "new flight" button.
- `pages/flight-list/flight-list-filter.ts` — typed filter form.
- `pages/flight-list/filter-dropdowns/air-state-filter.component.ts`, `process-state-filter.component.ts`.
- `masterdata.signals.ts` — derived signals over existing master-data stores (`gliderAircrafts`, `gliderPilots`, `gliderFlightTypes`, etc.) — used by the filter bar's dropdown sources. Shared with S-062c.

### FlightStore shape

```ts
// flight.store.ts — list slice owned by S-062b; detail slice extended by S-062c
export const FlightStore = signalStore(
  { providedIn: 'root' },
  withEntities<FlightListItem>(),
  withState({
    page: 0,
    size: 50,
    sort: { field: 'flightDate', direction: 'desc' } as Sort,
    filter: emptyFilter(),
    totalElements: 0,
    loading: false,
    error: null as string | null,
    lastClubId: null as string | null,
  }),
  withMethods((store, api = inject(FlightApi), settings = inject(TableSettingsCache)) => ({
    loadPage: rxMethod<void>(...),
    applyFilter: (f: FlightSearchFilter) => { ... patchState + persist + loadPage },
    applySort:   (s: Sort)               => { ... },
    invalidate:  ()                       => { /* drop entities, reload */ },
    onClubSwitch: (newClubId: string)    => { /* wipe entities, reset filter, reload */ },
  })),
  withHooks({ onInit(store) { /* hydrate from TableSettingsCache, subscribe to MutationBus */ } }),
);
```

**Refetch policy** (per S-006 "flights refetch-on-visibility"):
- On route entry (component init).
- On `MutationBus.flightChanged$` (S-062c emits after save/delete; the store reloads the current page).
- On `clubId` switch — calls `onClubSwitch` which wipes entities BEFORE refetching. **Don't show stale tenant-A rows while tenant-B loads.**
- **Not** on timer.

### API integration

`flight-api.ts` wraps the orval-generated `FlightControllerApi.searchFlights(...)`. The wrapper:
- Maps the typed filter form into `FlightSearchFilterDto`.
- Maps `Page<FlightListItemDto>` back into a `{ items, totalElements }` shape the store consumes.
- Translates server errors to i18n keys (`MessageManager` parity).

No raw `$http` style calls. All list traffic goes through this wrapper.

### Table mechanics

- **Pagination**: server-side. `<fls-data-table>` exposes `page`, `size`, `total`, fires `(pageChange)`.
- **Sort**: server-side. `<fls-data-table>` fires `(sortChange)`.
- **Filter**: server-side. Filter form `valueChanges → debounceTime(300) → store.applyFilter`.
- **Column set** mirrors legacy `flights-list.html`:
  - Flight date, immatriculation, glider pilot, second crew (computed: instructor ‖ co-pilot ‖ passenger), start time, ldg time, duration, # landings, start location, ldg location, FlightAirState (badge), FlightProcessState (badge), edit / copy row actions.
- **Selector contract** — every column header, every filter input, every row action has `data-testid="flight-list-<field>"`. S-109 depends on this.

### Integration with other stories

**Inputs:**
- **S-062a**: backend endpoint + DTOs.
- **S-006**: NgRx Signal Store reference pattern.
- **S-008**: `<fls-data-table>`, `<fls-text-input>`, `<fls-select>`, `<fls-date-range-picker>` primitives.
- **S-007**: typed Reactive Forms for the filter bar.
- **S-005**: i18n keys for column headers, badge labels, filter placeholders.
- **S-021**: OIDC client (auth bearer attaches automatically).
- **S-060**: `FlightAirState` comes pre-computed on the DTO from S-062a's mapper.

**Outputs:**
- **S-062c**: extends `FlightStore` with detail-state slice + save/delete methods + emits to `MutationBus`. **The two stories share `flight.store.ts`.** S-062b ships the list-slice methods; S-062c adds detail-slice methods in the same file.
- **S-110** (T3 smoke): consumes the list page as a navigation step.

### Alternatives considered

**Q1 — One FlightStore or several?** **One `FlightStore`** with `withEntities` for list + `withState` for current detail. Reason: legacy `Flights` and `PagedFlights` cover the same logical entity; splitting list and detail forces invalidation choreography (the kind legacy already gets wrong). S-062c extends the same store.

**Q2 — Persist filter to URL or local storage?** **Local storage via `TableSettingsCache`** (parity). URL-encoded filter is more shareable but breaks legacy "I left, came back, my filter is still there" UX. URL routing already covers the page+size dimension; add filter-to-URL later if requested.

**Q3 — Filter form debounce vs. apply-button?** **Debounced 300ms** for text inputs (matches legacy `searchbar` directive feel); dropdowns apply immediately. No "Search" button — parity.

## Edge cases & hidden requirements

### Edge cases (per acceptance criterion)

**AC1 — Pagination**
- Empty result set: table shows i18n empty-state row, not a loading skeleton.
- Page out of range (filter narrows results during browse): server returns empty page; store falls back to page 0.
- Total count exceeds local-storage cached size: re-fetch on filter change; don't trust stale total.

**AC2 — Filter bar**
- Whitespace-only substring filters → treat as empty (legacy `StringUtils.containsIgnoreCase` shape).
- Date-range with `from > to`: client validates, shows i18n error; doesn't fire request.
- `FlightAirState` dropdown is **client-side only**: `FlightAirState` is computed (S-060), not stored. Server can't filter by it directly — the filter has to be applied **on the page that came back**, or computed server-side as a CASE expression. **Picked: server-side CASE expression** so pagination stays consistent. Cite: legacy `FlightAirStateFilterDropdownDirective` works server-side via the same shape.
- `FlightProcessState` dropdown: stored column; trivial server filter.
- Multi-select dropdowns OR ANDed: parity says ANDed (multiple selections in one dropdown OR'd; across dropdowns AND'd).

**AC3 — TableSettingsCache**
- Storage quota exceeded: silent fail; table works with defaults. No toast.
- Stale schema (legacy → new field names changed): version the cache bucket; bump version on schema change to clear stale entries.
- Cross-user contamination on shared workstation: bucket keyed by `(userId, tableName)`; logout clears nothing (parity), but `userId` keying isolates.

**AC4 — Refetch hooks**
- Mutation event arrives while a different page is open: store still refetches its current page (might no longer contain the mutated row, but the list view stays consistent).
- `clubId` switch races with in-flight request: cancel the in-flight request before refetching.
- Route re-visit when no data has changed: refetch anyway (parity per S-006 "flights refetch-on-visibility"); server load is acceptable for the use case.

**AC5 — Selector contract**
- Selectize is hostile to Playwright (`04-flights-create.spec.ts:64` legacy workaround); the filter bar's dropdowns use the native-or-a11y-tested `<fls-select>` primitive (S-008), not selectize.
- Date-range picker `data-testid` slot for from/to inputs separately, not a single composite.

### Hidden requirements (legacy behavior the story doesn't mention)

- **`copyTowingFromLast` + `lastTowAircraftId` in localStorage** — these are workstation-level UX preferences keyed off the **edit form**, not the list. Mentioned here only to clarify: S-062b does **not** read/write them. Owned by S-062c.
- **`FlightStateMapper` enum drift (R5)** — both `FlightProcessState` (stored) and `FlightAirState` (computed) flow to the SPA. The new system derives both from the generated OpenAPI client (closing R5). Confirm `FlightAirState` is included in the OpenAPI spec as an enum, not stringified ad-hoc. S-062a's OpenAPI ops + S-004 codegen verify.

### Scope clarifications

**In:** paginated list, filter bar, `FlightStore` list-slice, `TableSettingsCache` integration, refetch policy, navigation entry points for create/edit/copy (placeholder destinations), `data-testid` contract.

**Out:** edit form, create form, copy form (S-062c); backend list endpoint (S-062a); Excel export (S-093/S-095); bulk operations (no legacy equivalent).

### NFR call-outs

- **Performance**: list page-load p95 < 3s on Fast 3G; server p95 < 250ms; k6 verification (see Performance plan).
- **Security**: `@TenantId` filtering is automatic server-side; client doesn't need to send `clubId` (it comes from the bearer). Cache **must** be wiped on `clubId` switch — silent cross-tenant cache bleed is the worst class of bug.
- **Accessibility**: WCAG 2.1 AA on the table + filter bar. Native semantics for sort headers (`<th aria-sort="ascending">`). Filter dropdowns keyboard-navigable.
- **i18n**: column headers, badge labels, filter placeholders, empty-state — all i18n keys.

## Security plan

### Threat model (list-page-specific)

- **Stale tenant cache on `clubId` switch (high)**: system admin impersonates clubs; FlightStore must wipe entities before reloading. Mitigation: `onClubSwitch` clears entities synchronously, then `loadPage` fires.
- **Filter injection via search substring (med)**: substring filters reach JPA `LIKE` predicates server-side. Mitigation: parameterized queries only (S-062a); client sends the substring as a body field, never URL-concatenated. Reject ASCII control chars except `\t\n\r`.
- **Information disclosure via badge enum (low)**: `FlightAirState` and `FlightProcessState` badges show legitimate state info. No PII. No mitigation needed.
- **PII echo in filter persistence (low)**: filter persisted to local storage may include a person-name substring. Acceptable per legacy parity; localStorage is per-user-per-browser.

### Authorization

- `POST /flights/search`: `isAuthenticated()` — already enforced by S-062a. List page doesn't add its own checks.
- "New flight" button visible to all authenticated users (parity). Edit row action shows only when `canUpdateRecord` flag is true on the DTO (server-supplied per S-062a).

### Input validation

- Filter form: type-safe via Reactive Forms. Date range, page, size validated client-side; server is the authority (S-062a).
- Substring max-length: 200 chars per field; client truncates.

### PII handling

- TableSettingsCache may contain a filter substring with someone's last name. Stored in localStorage scoped to the browser session. Logged out of? Yes per legacy. Cleared on logout? Legacy says no — keep parity. **Document this in user-facing privacy notice** if not already.

## Test plan

### Coverage contract

This story owns **list-page parity smoke + load characterization**. Form/edit coverage is S-062c; backend-only coverage is S-062a.

| Dimension | Owner |
|---|---|
| Backend endpoint correctness | S-062a |
| Cross-tenant list isolation | S-024 (CI) + S-106 (HTTP) |
| Filter rejection paths | S-101 (depth) |
| UI create/edit/copy parity | S-062c |
| Bulk operations | none — no legacy equivalent |

### Test pyramid

- **Unit (Vitest)**: ~6 — FlightStore loadPage, applyFilter, applySort, onClubSwitch (wipe + reload), mutation invalidation, TableSettingsCache round-trip.
- **Component (Angular Testing Library)**: ~4 — filter-bar value-change debounce, air-state-filter dropdown options, process-state-filter dropdown options, empty-state row render.
- **E2E (Playwright)**: 1 — `01-flights-list-smoke.spec.ts` end-to-end happy path.
- **k6**: load script in `e2e/load/flights-list.js`.

### Unit / component tests

- `FlightStore.loadPage_setsLoadingAndPopulatesEntities`.
- `FlightStore.applyFilter_persistsToSettingsCacheAndReloads`.
- `FlightStore.applySort_persistsToSettingsCacheAndReloads`.
- `FlightStore.onClubSwitch_wipesEntitiesBeforeReload`.
- `FlightStore.mutationBus_subscriptionReloadsCurrentPage`.
- `TableSettingsCache.versionBumpClearsStaleBuckets`.
- `FlightListFilter.dateRangeFromGreaterThanTo_emitsValidationError`.
- `AirStateFilter.options_reflectFlightAirStateEnum` (smoke against codegen output).
- `ProcessStateFilter.options_reflectFlightProcessStateEnum`.
- `FlightList.emptyState_renderedWhenNoEntities`.

### E2E

- `e2e/tests/new/01-flights-list-smoke.spec.ts`:
  1. Pre-clean + seed 5 flights via SQL pre-clean (`withPool` pattern from legacy).
  2. Login as a glider club user.
  3. Navigate `/flights` → assert 5 rows.
  4. Apply immatriculation substring filter → assert row count drops.
  5. Click `FlightAirState` dropdown, pick `Landed` → assert row count drops further.
  6. Clear filters → assert 5 rows again.
  7. Click "Edit" on a row → assert URL is `/flights/:id` (placeholder rendered by this story; S-062c replaces it).

### k6 load test

- 10 VUs, ramp 0→10 over 30s, hold 5 min, list endpoint with mixed filters.
- Pass: p95 < 250ms; p99 < 500ms; error < 0.1%.
- Repeat with 1 rps writer to simulate OGN. p95 ≤ 350ms acceptable under contention; **fail if > 500ms**.
- Consumed by S-111 verification.

### Cold-cache LCP

- Playwright + browser-perf-trace: navigate `/dashboard` → `/flights`.
- **LCP p95 < 3s on throttled Fast 3G**.

### Risks

- **Selectize-to-fls-select migration drift**: the legacy list filter uses selectize; new uses `<fls-select>` (S-008). Behavior parity for OR-within-dropdown / AND-across-dropdowns needs explicit unit coverage.
- **Server-side `FlightAirState` CASE expression** is new vs. legacy (which filtered client-side in the page). If the CASE doesn't match the S-060 derivation byte-for-byte, list filter results diverge from edit-page badges. Mitigation: parametrize the CASE off the same constants S-060 uses.

## Performance plan

### Hot paths

- **Page-load on `/flights`**: cold-cache LCP < 3s on Fast 3G. Bundle size + data-table render + initial fetch + auth bootstrap all share this budget.
- **Filter typing**: 300ms debounce → server fetch. p95 keyboard-to-row-update < 1s acceptable.

### Caching strategy (client-side, Signal Store per S-006)

- **Flights list store**: `withEntities` + paginated. Refetch policy: **on visibility** (route re-open), **on mutation** (after S-062c create/edit/delete), **on `clubId` switch** (wipe-then-reload). Don't refetch on timer.
- **Master-data reference stores** (aircraft, persons, locations, flight types) used by filter dropdowns: cache-long per S-006. Hydrate once.
- **Don't cache list across `clubId` switches** (system-admin impersonation): wipe on switch.

### Bundle size

- The list page must not pull in `flight-edit/` components — code-split via the route loader. Verify with `webpack-bundle-analyzer` or equivalent.
- Reactive-forms and `<fls-data-table>` are shared chunks — already amortized across other pages.

### Stress scenario — busy Saturday

10 operators paging list simultaneously while OGN writes 1 flight every 5–10s + 1–2 operators editing. Risks:
- **Refetch storm**: 10 SPAs all hearing the same `MutationBus` broadcast and refetching simultaneously. Mitigation: jitter (50–500ms random delay before refetch), or server-side `Cache-Control: max-age=2, stale-while-revalidate=10` on the search endpoint to absorb the burst.
- **TableSettingsCache writes** are local, no cross-SPA contention.

<!-- modernize-refine: end -->

<!-- amendment-2026-05-15b: start -->

## Amendment 2026-05-15b — Mobile-first / dense-desktop directive

Vision-doc amendment 2026-05-15b (see [`02-vision-and-constraints.md`](../02-vision-and-constraints.md) §C21–C24) designates the flight list as **the** airfield hot-path screen alongside flight-edit (S-062c). The list is what the operator sees first on flight days — its mobile UX matters as much as the form's.

**Layered acceptance criteria (additive):**

- **AC-DIR-1 (mobile-first card layout).** At viewports `<md` (< 768 px) the list renders as a stack of cards (one card per flight) showing the legacy-equivalent of "immat + pilot + start/landing times + state badge + actions menu". The `<fls-data-table>` primitive (S-008) supports a "card mode" at `<md` and reverts to a row-table at `≥md`. Same data, breakpoint-driven layout. (Vision §F2, §F3.)
- **AC-DIR-2 (dense-desktop variant).** At `≥lg`, the table renders with denser row padding + a compact filter bar (inline labels, smaller spacing). Same component, CSS-driven density (C22). The legacy "filter bar takes 30% of vertical real estate" pattern is not carried forward.
- **AC-DIR-3 (sticky filter + sticky pagination).** Filter bar sticks to the top of the viewport (under the nav-bar); pagination sticks to the bottom. On mobile, both collapse to a floating action button that expands a sheet. Prevents the user from scrolling a tall result set just to refine the filter.
- **AC-DIR-4 (touch targets on row actions).** Row actions (Edit / Copy / Delete) meet ≥ 44 × 44 CSS px on `<md`; on dense desktop ≥ 28 × 28 px for icon-only buttons. (§2 NFR "touch targets".)
- **AC-DIR-5 (keyboard-first dense navigation).** On `≥lg`: Tab/Shift+Tab walks rows; Enter = open edit on focused row; `n` = new flight; `/` = focus filter; arrow keys move row focus. Playwright spec asserts core navigation completes without mouse.
- **AC-DIR-6 (offline-aware list).** When offline, the list renders the last-cached page from IndexedDB (refresh on reconnect). A subtle "offline — last refreshed at HH:mm" banner sits under the filter. Consumes the PWA service worker (C17 / ADR 0014). The list must not present a blank state when offline if cached data exists.
- **AC-DIR-7 (mobile-class connectivity).** At simulated 200 ms RTT + intermittent loss: cached list renders immediately; refetch is non-blocking; no spinner > 3 s. (§2 NFR.)
- **AC-DIR-8 (saved-filter recency).** "Recently used filter combos" surface as one-tap chips above the filter bar (last 5 distinct combos per user). Generalizes the per-field-recency pattern from S-062c AC-DIR-6 to the filter bar. (§F8.)

**Refinement status flag:** Story was refined on 2026-05-14 — same caveat as S-062c. **Recommend `/modernize-refine S-062b` re-run** so the directive folds into the design + test + performance plans rather than living as an appended block.

**Inputs picked up from sibling stories:**

- S-008 — `<fls-data-table>` card-mode variant, density tokens, touch-target lint.
- S-006 — Signal-Store-backed offline cache; refetch-on-mutation policy already covers the offline-edge case (`MutationBus.flightChanged$` flushes the cache on the first reconnect refetch).
- S-067 / ADR 0014 — offline machinery.

<!-- amendment-2026-05-15b: end -->
