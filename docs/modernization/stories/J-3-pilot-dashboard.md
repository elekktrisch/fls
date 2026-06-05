---
id: J-3
title: Dashboard / home — role variants + live updates (/start)
epic: E-07
status: in_progress
started_at: 2026-06-04
journey0: false
carved: true
depends_on: [J-2]
rolls_up: [S-176, S-166, S-167]   # S-165 (pilot MVP + /me) already done+merged — reused, not rolled up
acceptance:
  - Pilot logs in → `/start` renders the pilot variant: localized greeting + "your last flight" card + quick actions (S-165 baseline, re-asserted green). [happy]
  - A CLUB_ADMINISTRATOR sees the club-admin variant at the same `/start` route — admin tiles (today's club flights + a flights-pending-validation count) instead of / alongside the pilot hero; a "Pilot view" toggle falls back to the pilot variant. [happy]
  - A SYSTEM_ADMINISTRATOR sees the sysadmin variant — cross-tenant tiles (total clubs / users / flights) + an entry point to switch into a tenant. [happy]
  - SSE live update: with the dashboard open, a server-published event over `GET /api/v1/me/events` updates a dashboard tile **without a reload** (e.g. a `flight.created` event refreshes the pilot's last-flight card / the admin's today-count). [happy]
  - SSE auth: `/api/v1/me/events` requires a valid Bearer token — an anonymous / no-token connection is rejected (no anonymous stream). [key-error]
  - Tenant scope: admin tiles count only the caller's own club; the sysadmin variant aggregates across clubs; a pilot never sees admin/sysadmin tiles (role-gated FE + tenant-gated counts). [key-error]
  - SSE resilience: an idle stream stays open across the heartbeat interval; the client transparently reconnects after a transient drop and resumes receiving events. [edge]
  - Showcase seed: loading the showcase seed (one command) yields a populated baseline — every dashboard variant renders **real rows, not empty states** (pilot last-flight card filled; admin counts non-zero; sysadmin aggregates span ≥2 clubs) — and the already-built J-0/J-1/J-2 lists+forms render populated from the same dataset. [happy]
screen: /start   # replacing legacy main/dashboard/ — intentionally diverged (vision §F); legacy stats surfaces NOT ported
headless_pulled_in: SSE channel (MePrincipalEventBus + GET /api/v1/me/events, S-176) — homed on this screen as its first product consumer (the live-updating dashboard tile); the cumulative **showcase seed** (realistic reusable dataset) is also homed here — the dashboard is the cross-cutting consumer that needs every entity populated across clubs/roles/states
migration: N/A — greenfield (legacy dashboard intentionally not ported; no mapper, AlpenFlight-only gallery)
parity_test: alpenflight/web/e2e/tests/start/start.spec.ts   # extend the existing S-165 smoke spec
adr_refs: [0007, 0008, 0017, 0011]
---

## Context

`/start` is the first surface a user sees after Keycloak login. S-165 shipped the
walking-skeleton **pilot** variant (greeting + last-flight card + quick actions +
`GET /api/v1/me`) and it's merged. J-3 completes the screen: **role-switched variants**
(club-admin S-166, sysadmin S-167) on the same route, and the **live-update channel**
(S-176 SSE) that makes a dashboard tile refresh in place without a poll or reload — the
foundational transport later consumed by the in-app inbox, join-request approvals (J-12),
freemium prompts, and reservation alerts. The legacy dashboard (flight chart, safety
gauge, license/medical expiry, club aggregates) is **deliberately not ported** (vision §F,
recorded in S-165) — J-3 does not resurrect it.

## Spec must assert

The Playwright run (extending `start.spec.ts`, real-idp) proves, end to end:

1. **Pilot variant (S-165 re-assert):** login → `/start` → greeting (`Good {morning|afternoon|evening}, {name}`) + last-flight card resolves a real migrated/seeded flight + quick actions route to `/flights` and `/flights/new`. Empty state when the user has no flights or no `personId`.
2. **Club-admin variant (S-166):** a `CLUB_ADMINISTRATOR` principal at `/start` renders admin tiles — **today's club flight count** and **flights-pending-validation count** (`NotProcessed` + `Invalid`), both scoped to the caller's club; the "Pilot view" toggle swaps to the pilot variant. (MVP tile set — see Assumptions.)
3. **Sysadmin variant (S-167):** a `SYSTEM_ADMINISTRATOR` principal renders cross-tenant tiles — total clubs / users / flights — and a control that enters a tenant context. (MVP tile set — see Assumptions.)
4. **Live update (S-176):** dashboard open in one context; a `flight.created` event is published to the principal's channel (by creating a flight in a second context / via API); the relevant tile updates **without reload** within a few seconds. Assert the DOM changes, not a network poll.
5. **SSE Bearer auth:** `GET /api/v1/me/events` with no/invalid token → rejected (401, no stream). With a valid token → `200 text/event-stream` and the first event arrives.
6. **Tenant + role gating:** admin counts match only the caller's club (cross-club flights excluded); the pilot principal sees no admin tiles; the sysadmin aggregate spans clubs.

Heartbeat + reconnect (AC7) is asserted at the IT layer where a fixture timer is controllable; the e2e proves the happy live-update + auth reject.

## Notes

**Migration: none.** Greenfield screen — no legacy mapper, no fanout run required; the
done-bar demonstrability is the **AlpenFlight pass video + screenshots** in the gallery
(no paired legacy form — the legacy dashboard is an intentional non-port). Surface that
gallery early per do-ship §3.

**SSE transport — load-bearing carve decisions (ship-time confirms with operator):**
- **Server stack:** AlpenFlight is a **Servlet-stack** Spring modulith. Carve assumption:
  implement the stream with Spring MVC **`SseEmitter`** (Servlet 3 async) + a plain
  in-memory `MePrincipalEventBus` (a `ConcurrentMap<kcSub, Set<SseEmitter>>`), **not**
  WebFlux/`Flux` — avoid dragging a reactive runtime into one endpoint. S-176's task
  wording mentions Flux; this carve overrides toward the in-stack `SseEmitter` path.
  Pin at refine; if a strong reason to pull WebFlux emerges, **escalate for an ADR**
  (don't pull a runtime silently).
- **Browser auth:** native `EventSource` can't set `Authorization`. Carve assumption:
  **`@microsoft/fetch-event-source`** (fetch-stream reader) so the existing Bearer
  interceptor path carries the token — over the one-shot `?ticket=` query-param
  alternative. Pin the polyfill at refine.
- **Demonstrable event:** `flight.created` published to the creating principal's sub is
  the thinnest concrete consumer that proves the channel **without** waiting on S-178/J-12
  (whose `join-request.status-changed` is the eventual first real event). Publish from the
  flight-create application service via `MePrincipalEventBus.publish(sub, "flight.created", …)`.
- In-memory only, no replay across restart; every tile must also load its state via a
  normal GET (SSE is the change overlay, not the source of truth) — so the dashboard is
  correct on first paint and SSE only animates subsequent changes.

**Showcase seed — a cumulative, reusable demo dataset (operator ask, homed here).**
The dashboard variants only prove their worth against realistic data — an empty
last-flight card, zero admin counts, or single-club sysadmin aggregates demonstrate
nothing. J-3 establishes a **showcase seed**: a deterministic, curated dataset loaded in
**one command** that brings up a fully-populated environment, so we don't rebuild a
realistic starting position from scratch each time.
- **Distinct from the lean per-IT test seed** (ADR 0021 isolation stays intact — do NOT
  fatten the always-on Flyway dev seeds or every IT slows). The showcase seed is an
  **on-demand profile**, loaded only for local dev / demo / the e2e *display* run — not
  during IT bootstrap. Distinct too from the migrated (fanout) data: the showcase seed is
  deterministic and dial-able (you can demand specific variations/edge states); the export
  is realistic but non-curated and only exists in the fanout chain.
- **Scope = everything built so far, cumulatively:** ≥2 clubs (cross-tenant aggregates +
  fan-out realism); all 3 roles (pilot / club-admin / sysadmin) with at least one pilot
  who HAS flights and one who has NONE (empty-state still reachable); aircraft variants
  (glider / tow / motor / charter); locations; and flights in **every variation × state ×
  date** — glider+tow paired and motor; `NotProcessed`/`Valid`/`Invalid`/`Locked`/
  `DeliveryBooked`; dated today, within the 2-day lock gate, and past the threshold. This
  backfills the J-0 (locations), J-1 (aircraft), J-2 (flights) lists+forms too.
- **Convention (precedent J-3 sets):** each FUTURE journey **extends** the showcase seed
  with its entity's realistic variations (reservations → J-5, planning days → J-6,
  deliveries → J-10, articles → J-11, …) — the same per-journey-contribution pattern as
  the per-entity legacy seed + mapper. Record the convention in the seed module's README.
- **Determinism:** fixed UUIDs / stable data so the e2e spec can assert against known
  showcase rows and the dashboard counts are predictable.

**Boyscout riders to fold (this journey runs the gate + touches e2e/CI/gallery):**
- **modernize-\* sunset** — **trigger now met** (J-2 was the first non-migration feature
  journey; this is the second). Delete the 9 `modernize-*` skills + ~12 agents + prune
  `rolled_up_into:` stories (~21 files, mechanical). Good fit to ride J-3.
- **"Run Playwright" → required `ci` gate** — structural CI fix (operator, J-2 retro);
  this journey's done-bar leans on the mock-auth e2e, so wire it required here.
- **Collapse the two proof galleries into one** — drop the ci.yml AlpenFlight-only gallery,
  keep the full one; greenfield J-3 is AlpenFlight-only so it's a clean place to do it.
- **e2e tsc-strictness + prettier-glob** — J-3 edits `start.spec.ts`; fold the e2e
  format/tsc normalization if it stays bounded.
- ~~**Role-vocab single-source** (S-165 open q): `KNOWN_REALM_ROLES` (BE `MeService`) ↔ FE
  `AppRole` ↔ `realm-export.json` are three hand-maintained copies — J-3's role-variant
  routing reads exactly this vocabulary, so a CI cross-check rides naturally here.~~
  **Shipped T-14** — `RoleVocabularySingleSourceTest` (server `arch` package) cross-checks
  `Role` enum == `KNOWN_REALM_ROLES` == `AppRole` (equality) + `Role` ⊆ realm-export realm roles.

**Likely task seams (non-binding, for /do-ship):** the `/start` role-switch shell
(routes off `me.roles`); the club-admin variant component + its tiles; the sysadmin
variant component + its tiles; the admin **count endpoints** (today's flights +
pending-validation, tenant-scoped — one resource cluster); the sysadmin **cross-tenant
aggregate** endpoint (one resource); `MePrincipalEventBus` + `MeEventsController`
(`GET /api/v1/me/events`, one backend seam); the `MeEventsService` Angular client (one
service); the `flight.created` publish wired into the flight-create service (one
publish-point); the `start.spec.ts` thickening (one spec). **Showcase seed** seams: the
seed loader/runner + invocation command (one infra seam); the seed dataset itself, which
may split per entity-area if one task overflows — clubs+users+roles, aircraft+locations,
flights-across-states (carve finer at ship time if a single seed task is too big).

## Assumptions made

- **Route is `/start`**, not the roadmap's stale `/dashboard` (S-165 shipped `/start`).
- **Club-admin MVP tiles** = today's club flight count + flights-pending-validation count;
  **sysadmin MVP tiles** = total clubs/users/flights + tenant entry point. Both stories say
  "scope TBD at refine" — these are the thinnest provable picks from their candidate lists;
  refine/operator may swap tiles without changing the journey shape.
- **SSE first event = `flight.created`** (see above) — chosen so J-3 doesn't depend on the
  unbuilt join-request slice.
- Heartbeat 25s + browser-default EventSource reconnect backoff (S-176 defaults), per-sub
  connection cap 8.
- **Showcase-seed mechanism** = a dedicated on-demand loader (recommended: a Gradle task
  like `seedShowcase` / a `@Profile("showcase")` `ApplicationRunner` running idempotent
  upserts, or curated SQL fixtures), **not** Flyway `V__` dev migrations (keeps ITs lean,
  ADR 0021). It must also provision the matching **Keycloak users** for its seeded
  principals (reuse the J-0c/J-2 realm-export + JIT path) so the e2e can actually log in
  as the showcase pilot / club-admin / sysadmin. Pin the exact mechanism at refine/ship;
  if it implies a standing dev-data convention worth an ADR, ship-time escalates.

## Tasks

Ordered; each runs in a fresh `/do-task` worker on `integration/J-3`. Existing surfaces
(Explore map): `/start` = `features/start/start.page.ts` + `start.store.ts` + `start.routes.ts`
(single pilot component, variant seam net-new); `/me` = `me/web/MeController` + `me/application/MeService`,
roles via JWT `realm_access` filtered by `KNOWN_REALM_ROLES`; FE `SessionStore` already has
`isClubAdmin`/`isSystemAdmin` computed. Flight create = `flights/application/FlightsService.createFlight`
(already publishes via Spring `ApplicationEventPublisher` for audit — the `flight.created` seam).
Servlet MVC (no WebFlux) → `SseEmitter`. Seed today = `seed-club-1` only, **no flights**.

- [x] **T-01** — Spec stub (real-idp): structure + selectors for the 3 variants (`start-variant-{pilot|clubadmin|sysadmin}` testids), the SSE live-tile testid, login-as-role flow, thin assertions. NEW real-idp file `alpenflight/web/e2e/tests/real-idp/start-dashboard.spec.ts` (NOT extending `tests/start/start.spec.ts` — that's the mock `chromium` project per playwright.config testMatch; its S-165 pilot assertions stay there untouched). *(seam: one spec file)*
- [x] **T-02** — Showcase-seed scaffold + tenancy: the on-demand loader mechanism (Gradle `seedShowcase` task / `@Profile("showcase")` runner, idempotent, deterministic UUIDs, **not** Flyway `V__`) + a **2nd showcase club** + realm/`t_user` users for all 3 roles in both clubs (incl. one pilot with NO flights). Seed-module README records the per-journey-extension convention. *(seam: showcase-seed loader + clubs/users/roles + realm-export additions)*
- [ ] ~~**T-03**~~ (split → T-03a + T-03b on overflow; see note)  — Showcase-seed data: aircraft + locations + the flight matrix. Three aggregate construction-paths in one task exceeded caps.
  - OVERFLOW: three distinct aggregate construction-paths + reference-UUID sets in one task, > caps. Locations (`Location.create`, `@TenantId`-scoped, location_type/country/unit UUIDs) AND Aircraft (`Aircraft.register`, cross-tenant, aircraft_type UUIDs + state-history) AND the full Flight matrix (`Flight.createGlider/Tow/Motor` + 26-field `FlightOperationalData` + `replaceCrew` + `FlightStateTransitionService` transitions through the time-gate, run under `Tenants.runAs` per club — 3 variants × 5 states × 3 date-buckets × 2 pilots, paired-aerotow links, deterministic counts). Requires refactoring the T-02 JDBC-direct seeder to domain-driven persistence + a much larger count-asserting IT. Three logical changes, can't describe without "and". Suggest split: **T-03a = locations + aircraft** (both clubs, all variants, deterministic ids; seeds the J-0/J-1 lists populated) ; **T-03b = the flight matrix** (variants × states × dates × crew, through the domain + transitions, depends on T-03a's aircraft/location ids; seeds the J-2 list + dashboard counts; documents the exact counts in the README).
- [x] **T-03a** — Showcase-seed: locations + aircraft for both clubs. Locations (`Location.create`, `@TenantId`-scoped, a home airfield + a couple destinations per club) + aircraft covering all fleet variants (glider / tow / motor-TMG / charter cross-club), built through the domain aggregates (not raw INSERT), deterministic ids/immats. Backfills the J-0 (locations) + J-1 (aircraft) lists populated. Extend `ShowcaseSeederIT` to assert per-club location/aircraft counts + idempotency. *(seam: showcase-seed locations+aircraft contribution)*
- [x] **T-03b** — Showcase-seed: the flight matrix. For `pilot1`/club-1 + `pilot-c2`/club-2 (and `pilot-empty1` stays empty): glider+tow paired (aerotow, linked per S-063), winch glider, motor; across NotProcessed/Valid/Invalid/Locked/DeliveryBooked **via real `FlightStateTransitionService` transitions** (respect the S-061 time-gate — Locked needs `flight_date ≤ today-2d`, DeliveryBooked needs `locked_at ≤ today-3d`); dated today / within-2d / past-threshold; FlightCrew rows so the pilots are PIC (drives the S-165 last-flight card). Depends on T-03a ids. May require persisting showcase entities through the domain under `Tenants.runAs(clubId,…)` rather than the T-02 JDBC path — refactor as needed. Document exact per-club / per-state / today counts in the seed README; extend `ShowcaseSeederIT` to assert them + `pilot-empty1` has zero. *(seam: showcase-seed flight matrix through domain transitions)*
- [x] **T-03c** — Gate-revealed (full `./gradlew build` red; passed in isolation, failed in the full suite): fix the 4 showcase-seed test failures. (1) **`NativeSqlRegisterTest`** — `ShowcaseSeeder` does native-SQL INSERTs on tenant-scoped tables (`t_location`, and likely `t_aircraft`/`t_flight`) via the deterministic-id JDBC pattern, tripping the tenant-scoping guard. **Register** those call sites in `native-sql-register.md` (the documented escape — explicit `club_id`, deterministic showcase ids, seed-only), or rewrite if cleaner; don't weaken the guard. (2) **`ShowcaseSeederIT`** (3 cases: `seedsLocationsPerClub`/`seedsAircraftFleetVariants`/idempotency) assert **absolute** location/aircraft counts (3) but the full suite shares a Testcontainers DB other ITs populate → saw 15/16. Make the assertions **isolation-robust**: scope to the showcase club ids / its own seeded rows (or run the IT against a clean/isolated DB), not whole-DB counts. Make `./gradlew build` green. *(seam: native-sql-register.md + ShowcaseSeederIT isolation)*
- [x] **T-04** — SSE transport (backend): `MePrincipalEventBus` (in-memory `ConcurrentMap<sub, Set<SseEmitter>>`) + `MeEventsController` `GET /api/v1/me/events` (`SseEmitter`, 25s heartbeat, per-sub cap 8) under the existing Bearer filter chain. IT: open stream, publish via bus, assert receipt + heartbeat; assert no-token → 401. *(seam: me-events controller + bus + IT)*
- [x] **T-05** — SSE publish-point: publish `flight.created` from `FlightsService.createFlight` via the existing `ApplicationEventPublisher` + an `@TransactionalEventListener(AFTER_COMMIT)` that fans the event to the principal's bus connections. *(seam: flight-create publish + SSE fanout listener)*
- [x] **T-06** — `MeEventsService` (FE): Angular service opening the stream after the OIDC session authenticates, via `@microsoft/fetch-event-source` (carries the Bearer header), RxJS subject per event-kind, transparent reconnect. *(seam: one Angular service)*
- [x] **T-07** — `/start` role-switch shell: route renders pilot variant by default and swaps to clubadmin/sysadmin variant off `SessionStore` roles; a "Pilot view" toggle for admins falls back to the pilot variant. Pilot variant stays S-165 as-is. *(seam: start route/shell component)*
- [x] **T-08** — Club-admin count endpoints (backend): today's club flight count + flights-pending-validation count (`NotProcessed`+`Invalid`), tenant-scoped (`@TenantId`). *(seam: one endpoint cluster + IT)*
- [x] **T-09** — Club-admin variant component + tiles: renders the two counts from T-08, live-updates the today-count on a `flight.created` event via `MeEventsService` (T-06). *(seam: one component)*
- [x] **T-10** — Sysadmin cross-tenant aggregate endpoint (backend): total clubs / users / flights across tenants (SYSTEM_ADMINISTRATOR-gated, deliberately tenant-unscoped). *(seam: one endpoint + IT)*
- [x] **T-11** — Sysadmin variant component + tiles + a control that enters a tenant context. *(seam: one component)*
- [ ] **T-12** — Rider: add "Run Playwright" to the required `ci` gate (operator, J-2 retro — structural). *(seam: .github/workflows/ci.yml required aggregator)* — clears the `_BOYSCOUT` bullet.
- [ ] **T-13** — Rider: collapse the two proof galleries into one — drop the ci.yml AlpenFlight-only deploy + its sticky comment, keep the full `legacy-parity` gallery as THE gallery. *(seam: ci.yml gallery job + fanout deploy + the two comment upserts)* — clears the `_BOYSCOUT` bullet.
- [x] **T-14** — Rider: role-vocab single-source CI cross-check — assert `Role.java` ↔ FE `AppRole` ↔ `realm-export.json` agree (ArchUnit/CI test), since J-3's variant routing reads this vocabulary. *(seam: one cross-check test)* — clears the S-165 open-q.
- [ ] **T-15** — Rider: modernize-* sunset (trigger met) — delete the 9 `modernize-*` skills + ~12 modernize agents + prune `rolled_up_into:` horizontal stories. Mechanical (one logical change: deletion). *(seam: .claude/skills/modernize-*, .claude/agents/*, rolled_up_into stories)* — clears the `_BOYSCOUT` bullet.
- [ ] **T-16** — Thicken spec to full real-idp assertions: all 3 variants render **populated** from the showcase seed (counts non-zero, ≥2 clubs in the sysadmin aggregate, pilot last-flight filled) + SSE live tile update without reload + Bearer reject + role/tenant gating; capture the AlpenFlight gallery (surface early per do-ship §3). Fold the bounded e2e prettier/tsc normalization on touched specs. *(seam: spec thicken + gallery capture)*
- [x] **T-17** — Gate-revealed (full ci on HEAD; real-idp proof GREEN, two suite/spec gaps): fix the 2 reds. (a) **`TenantsRunAsAllowlistTest`** fails — T-10's `SystemDashboardService` calls `Tenants.runAs` (cross-tenant flight count) but isn't allow-listed; add its FQN to the allowlist (deliberate, legitimate SYSTEM_ADMINISTRATOR-only caller — document per the class javadoc). (b) **mock-auth `start.spec.ts:254`** ("quick-action buttons") — `getByTestId('start-quick-log-flight')` times out because the T-07 shell lands the admin mock principal on the sysadmin variant; this case missed the `gotoPilotView(page)` helper the rest of the spec uses — add it before the pilot quick-action clicks. Verify `TenantsRunAsAllowlistTest` + the touched e2e spec. *(seam: Tenants.runAs allowlist + start.spec.ts:254 gotoPilotView)*
