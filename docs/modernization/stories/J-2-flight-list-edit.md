---
id: J-2
title: Flight list + edit forms (airfield hot path)
epic: E-07
status: in_progress
started_at: 2026-06-03
journey0: false
carved: true
depends_on: [J-1]   # J-1 = aircraft register (flights FK aircraft); inherits J-0/J-0b migration-fan-out infra
rolls_up: [S-061, S-062d, S-062e, S-062f, S-062h, S-062i, S-064]  # reuses impl S-062a/b/c, S-063, S-067
acceptance:
  - Glider flight create via the 3-step wizard (Launch → Glider → Tow when start-type = aerotow); the new flight appears in the /flights list. [happy]
  - When start-type is aerotow, the tow flight is created + linked in the SAME save (paired glider↔tow): the glider row carries `towFlightId` to a distinct TOW-type row in the same club (S-063 parity). [happy]
  - Edit a flight; changes persist and re-render in the list. [happy]
  - Motor flight create/edit/list at /airmovements — same Flight backend with the MOTOR filter, no tow pairing (S-064). [happy]
  - Delete a flight; it leaves the list (CLUB_ADMINISTRATOR gate). [happy]
  - Tenant scope: a caller sees only their club's flights; a cross-tenant flight GET 404s (J-0 pattern). [key-error]
  - Time-gate (S-061, sacred cow): a flight too recent cannot transition Valid→Locked; one past the threshold can, and a Locked flight is read-only in the edit form. Boundary exact at the day gate. [key-error]
  - Optimistic concurrency (net-new affordance): a stale PUT (If-Match with an old version) returns 412 and opens the inline per-field conflict diff (keep-mine / keep-theirs, no auto-retry); a 409 state-gate reject shows a "reload latest" toast (S-062h). [key-error]
  - Real legacy Flight + FlightCrew data migrates into AlpenFlight and a migrated flight (with crew + tow link) renders in the owning club's /flights list — full legacy→migrate→Keycloak→UI chain green (reuses J-0c's harness; the real export validates the producer SELECT). [happy, real-data]
screen: /flights (+ /flights/new, /copy/:id, /:id/edit) and /airmovements — feature folder features/flights/ — replacing legacy flsweb/src/flights/ + flsweb/src/flights/airmovements/
headless_pulled_in: FlightGatePolicy time-gate beans + injected Clock (S-061) — pulled in by this screen, asserted per-flight via the process-state transition + locked-flight read-only. The BULK LockFlights / DeliveryCreation jobs do NOT home here — they ride J-15 (jobs console) / J-10 (deliveries) per the roadmap headless table.
migration: Flight, FlightCrew (tow self-FK two-pass UPDATE; air-state lossy — only the FlightPlanOpen timestamp survives, accepted per S-060)
parity_test: alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts
adr_refs: [0005, 0007, 0008, 0014, 0015, 0022, 0024]
---

## Context

The flight list + edit screen is **the** airfield hot-path screen (vision §C21–C24,
2026-05-15b amendment) — the one pilots and ops touch every flying day. A club records
glider flights (with an optional linked tow), motor "air movements", and edits/locks/bills
them through the process-state machine. Every downstream journey leans on it: J-3 dashboard,
J-5 reservations, J-7 reports, J-9/J-10 accounting all read flights. It's the first journey
after the masterdata pair (J-0 locations, J-1 aircraft), so it runs the **full real
legacy→migrate→Keycloak→UI chain** for `Flight` + `FlightCrew` as its done bar — the first
*transactional* (non-masterdata) entity to migrate end-to-end.

## Spec must assert

Grounded in legacy `flsweb/src/flights/` (list `flights.html`, glider form
`flight-edit-glider-form.html`, tow form `flight-edit-tow-form.html`) +
`flsweb/src/flights/airmovements/` (motor `air-movements.html`), the server gates in
`flsserver` (`FlightService.cs`, `Accounting/DeliveryService.cs`), and the existing rewrite
backend `FlightsController` (`/api/v1/flights`: GET list keyset-cursor / `/{id}` /
`/new-template` / `/{id}/copy-template` / `/last-context`, POST, PUT/{id} with If-Match→412,
DELETE/{id}, PATCH `/{id}/process-state`) + the `Flight` aggregate (`@Version`, `linkTow`,
computed `airState()`, `transition()` over `FlightTransitionMatrix`):

- **Glider + tow + motor CRUD** (happy): 3-step wizard (Launch / Glider / conditional Tow);
  paired glider↔tow save when start-type = aerotow (`needsTowplane`); motor flights at
  `/airmovements` (same backend, MOTOR discriminator, no tow); edit persists; delete
  (CLUB_ADMINISTRATOR). List columns mirror legacy `flights.html` (air-state + process-state
  badges, date, immat, pilot/second-crew, start location, start/landing/duration, tow cols).
- **Tenant scope** (key-error): own-club only; cross-tenant flight GET 404 (J-0 pattern).
- **Time-gate** (key-error, S-061 sacred cow): too-recent flight can't lock; past-threshold
  flight locks; Locked flight read-only (legacy `CanUpdateRecord=false`). Boundary exact at
  the day gate, "today" driven by the injected `Clock` (e2e fixture anchors 2026-01-01).
- **412 conflict diff** (key-error, S-062h): stale `If-Match` PUT → 412 → inline per-field
  keep-mine/keep-theirs dialog, no auto-retry; 409 state-gate reject → "reload latest" toast.
- **Real-data parity** (happy, real-data): a migrated legacy flight (crew + tow link) renders
  in the owning club's `/flights` list, post full chain.

**Parity to confirm at ship time (do NOT guess — dispatch `legacy-oracle`):**
- **Time-gate KEY column (load-bearing).** S-061's ACs say lock on `flight.flight_date ≤ today-2d`
  and bill on `flight.locked_at ≤ today-3d`. **Legacy disagrees:** `FlightService.cs:1157,1164`
  locks on `CreatedOn ≤ Today-2d` (record-creation date, `TruncateTime`) gated on
  `ProcessStateId == VALID(30)`; `DeliveryService.cs:65,97` bills on `CreatedOn ≤ Today-3d`
  gated on `LOCKED(40)`. `CreatedOn` (entry date) ≠ `flight_date` (flying date) ≠ `locked_at`.
  Resolve which key is correct before writing the boundary spec — `CreatedOn` is the legacy
  behavior; S-061's wording is likely a refinement simplification. This decides the gate.
- **Lock prerequisite.** Legacy lock also requires `ProcessStateId == VALID`; confirm the
  Valid prerequisite + whether J-2 proves the gate per-flight (PATCH process-state) vs needing
  the bulk job (homed on J-15).
- **412 is NET-NEW, not parity.** Legacy `Flight.cs` has **no** RowVersion/concurrency token —
  last-write-wins (oracle-confirmed). The `@Version`/If-Match/412 path + the conflict-diff UX
  (S-062h) are a new-stack affordance the rewrite adds; assert the *new* behavior, don't seek
  legacy parity for it.
- **af-date-picker range mode (S-062e).** S-062b reportedly worked around a zoneless deadlock
  by splitting the filter into two single pickers; the current code appears to use range mode.
  Verify whether the deadlock is fixed/reverted or still worked around before asserting the
  range filter (and whether `/dev/primitives` range showcase loads).

## Notes

- **Backend + frontend largely exist** (S-062a/b/c, S-063, S-067 are `implemented/`): `Flight`
  aggregate w/ `@Version` + If-Match→412 + `linkTow`/`unlinkTow` + computed `airState()` +
  `transition()`; `FlightsController` (all CRUD + templates + last-context + process-state);
  the NgRx signal store (`flight.store.ts`) already tracks `saveConflict` (412); the 3-step
  wizard + paired-create coordinator + `flight-form.model.ts`. **Net-new for J-2:**
  - **S-061** — `FlightGatePolicy` bean (`canLock`/`canBill`) + injected `Clock`, wired into
    the transition service; boundary unit/IT (1s before = reject, 1s after = allow).
  - **S-062h** — the **412 conflict-diff dialog** (`flight-conflict-prompt.component.ts`) +
    `FlightStore.save` wiring (store *tracks* the conflict; the diff UI isn't built) + 409 toast.
  - **S-064** — `/airmovements` route + page reusing the shared list/form parameterized by the
    MOTOR filter (legacy duplicated; rewrite must NOT — one parameterized list/form, two routes).
  - **S-062e** — af-date-picker `mode="range"` zoneless fix; **S-062f** — `flights-list.spec.ts`
    air-state dropdown reliability (green without retries).
  - **Flight + FlightCrew migration proof** (below).
- **Done bar = real chain** (retro Q1, [[feedback_demonstrable_proof_prefer_ui]]). The
  `FlightMapper` + `FlightCrewMapper` (Group.FLIGHT) **exist** from J-0b's per-entity authoring
  but are **UNPROVEN end-to-end** ([[verify_infra_is_run_not_just_authored]]) — size as
  build/verify, not wire. The **real legacy export validates the producer SELECT** that synth
  bundles can't ([[project_synth_bundle_doesnt_validate_producer_select]] — exactly the J-1 T-16
  catch; expect ≥1 Flight producer-column mismatch). Reuse J-0c's legacy→migrate→Keycloak→
  AlpenFlight harness + per-club video; synth-at-PR / real-export-at-nightly (J-0c/J-1 split).
- **Migration shape (parity-sensitive):** tow self-FK is NOT in `foreignKeys()` (would break
  the ArchUnit ingest-order rule) — it's a **two-pass UPDATE** (first pass writes the legacy
  GUID, second resolves after the FLIGHT pass walks `legacy_id_map_flight`; soft-deleted tow-ref
  keeps both rows tombstoned). Air-state is **lossy** — legacy `AirStateId` is not migrated
  (air-state is computed); only the `FlightPlanOpen(5)` timestamp survives → `flight_plan_opened_on`
  (accepted, S-060). Operating-club is **per-flight** (not denormalized from aircraft — charter
  case). Aircraft FK resolves cross-tenant (TENANT_BYPASS); Location FK is tenant-scoped (fan-out
  replica per operating club). **Carry forward the J-1 flag:** the J-1 `OwnerId`-vs-`AircraftOwnerClubId`
  fidelity note — weigh it where a migrated flight resolves its aircraft FK replica.
- **Headless homing:** the time-gate **policy beans + Clock** home on THIS screen (gate proven
  per-flight via the process-state transition + locked-flight read-only). The **bulk** LockFlights
  / DeliveryCreation **jobs** do NOT — they ride J-15 (jobs "run now") / J-10 (deliveries) per the
  roadmap headless table. Don't build a bulk-lock job here.
- **Deferred breadth — size at ship** ([[feedback_vertical_slices_first]]): the J-2 **gate** is
  glider+tow+motor CRUD + time-gate + 412 diff + paired save + migration real-chain. The
  pure-breadth ACs inside the rolled-up polish stories ride a later journey / boyscout, NOT the
  J-2 gate: **offline IndexedDB cache + service-worker sync + marginal-3G** (S-062d AC-DIR-6/7,
  S-062h SW-queue replay / re-auth / 3G), **saved-filter recency chips** (S-062d AC-DIR-8),
  **mobile-first card layout + sticky chrome + density variants** (S-062d AC-DIR-1..5),
  **cross-browser Ctrl+D matrix + 1–5 quick-select + slide-in focus jump + Shift+? help**
  (S-062i). `/do-ship` decides which slices ride the gate vs defer to a named follow-up.
- **Boyscout riders to fold** (from `_BOYSCOUT.md` — they ride this journey, not own stories;
  `/do-ship` sizes + clears the bullets):
  - **ci.yml docs-only path-filter** — skip the heavy `alpenflight build` + `alpenflight-proof`
    on docs/skill/story-only pushes (`docs/**`, `.claude/**`, root `*.md`), keeping the `required`
    aggregator green via the skipped-to-success pattern. (J-1 burned many cycles re-running the
    ~7-min proof on doc-only pushes.) *(seam: ci.yml path filter + required aggregator)*
  - **modernize-\* sunset** — J-2 is the **first non-migration-flavored feature journey**, which
    is the rider's "after do-* ships one non-migration journey" condition. Delete the 9
    `modernize-*` skills + ~12 modernize agents + prune the `rolled_up_into:` horizontal stories
    (47 `implemented/` stay as history). Mechanical, however many files. *(seam: .claude/skills/modernize-*,
    .claude/agents/*, rolled_up_into stories)* — fold only if it doesn't bloat the gate PR; else
    leave for the next feature journey.
- **Seam hints for `/do-ship`** (non-binding, one seam each): `FlightGatePolicy` bean + `Clock` +
  transition wiring (S-061) · 412 conflict-diff dialog component + `FlightStore.save` wiring +
  409 toast (S-062h) · `/airmovements` route + motor-filter param on the shared list/form (S-064) ·
  af-date-picker range zoneless fix (S-062e) · `flights-list.spec.ts` air-state dropdown
  reliability (S-062f) · Flight + FlightCrew migration bindings + producer SELECT + round-trip IT ·
  real-idp `flight-migration-parity.spec.ts` + flight parity bundle seeder · each boyscout rider.

## Parity decisions (ship-time — legacy-oracle 2026-06-03 + operator)

Resolved against legacy (`flsserver` `FlightService.cs`, `Accounting/DeliveryService.cs`,
`MappingExtensions.cs`) + operator adjudication:

- **Time-gate KEY — DELIBERATE DIVERGENCE from legacy (operator).** Legacy keys **both** gates on
  `CreatedOn` (record-entry date, `TruncateTime`'d, `DateTime.Today` local) — `FlightService.cs:1157,1164`
  (lock ≥2d) + `DeliveryService.cs:65,97` (bill ≥3d); there is **no `locked_at` column** in legacy.
  Operator chose S-061's wording instead: **lock on `flight_date ≤ today-2d`, bill on `locked_at ≤
  today-3d`** (lock 2 days after the flight flew, bill 3 days after locking). This **changes which
  flights become billable and when** → needs a **`locked_at` column (net-new, set on Valid→Locked)**
  + an **ADR amendment recording the divergence** (flag for `/do-retro`; do-ship does not auto-edit ADRs).
  `created_at` stays migratable for audit but no longer drives the gates.
- **412 / optimistic concurrency — NET-NEW, not parity.** Legacy `Flight` has no row-version/concurrency
  token (last-write-wins, oracle #25). The `@Version`/If-Match/412 + conflict-diff (S-062h) is an
  intended improvement; assert the *new* behavior.
- **Read-only rule — only `DeliveryBooked(60)`, NOT `Locked`** (`FlightService.cs:1276`, oracle #12).
  A Locked flight is still editable (the manual state-transition path relies on it). The
  `docs/legacy/server.md` "no edits when locked" prose is wrong — code wins. **Correct the J-2 AC:**
  the read-only assertion targets a `DeliveryBooked` flight (edit/delete → 4xx), not a Locked one.
- **Single-flight tenant scope — close the legacy leak (oracle #24).** Legacy `GetFlight(id)` /
  update / delete load by id with **no ClubId filter** (same hole J-1 found for Aircraft) → cross-tenant
  edit/delete possible. New stack scopes structurally (`@TenantId`, ADR 0008); assert cross-tenant 404.
- **Legacy bugs to NOT reproduce (assert corrected):** glider↔tow start-time/location mismatch →
  legacy `ValidationException`→HTTP **500** (oracle #15) should be a 4xx field error; tow-orphan on
  edit-away-from-towing (`UpdateFlightDetails` nulls the nav but skips the cascade `DeleteFlight` does,
  oracle #17) → new stack must delete/clean the orphan (assert no dangling tow row); empty-guid FKs
  (`00000000-…`) → null/absent (oracle #18).
- **Validate flow (reference, J-15 owns the bulk action):** `ValidateFlights` is club-wide,
  best-effort, always returns 200 (oracle #6,#9). J-2 proves the gate per-flight via the existing
  `FlightStateTransitionService` (PATCH `/{id}/process-state`); the bulk validate/lock **jobs** ride
  J-15. Manual transition matrix (oracle #11): only the legal source→target pairs transition; others 400.

## Tasks

Verify-wire-prove journey — backend CRUD/concurrency/tow-link/transition (S-062a/b/c, S-063, S-067,
S-059) + the 3-step wizard + the Flight/FlightCrew mappers all **exist**; net work is the time-gate
(net-new `locked_at` + policy), the 412 conflict UI, `/airmovements`, the migration **proof** (expect a
producer-SELECT catch like J-1 T-16), the real chain + gallery, and folded riders. Ordered:

- [ ] **T-01** — Real-idp Playwright spec **stub**: author `alpenflight/web/e2e/tests/real-idp/flight-migration-parity.spec.ts` structure + selectors + flow steps with **thin** assertions (clean-seed: glider create via 3-step wizard, paired tow, motor at `/airmovements`, edit, delete, cross-tenant 404, time-gate lock + `DeliveryBooked` read-only, 412 diff; then migrated-flight render). Commits the screen shape. *(seam: one spec file)*
- [ ] **T-02** — S-061 **time-gate backend** (operator divergence): `FlightGatePolicy` bean (`canLock`: `flight_date ≤ clock.today()-2d`; `canBill`: `locked_at ≤ clock.today()-3d`) + injected `java.time.Clock` bean; net-new **`locked_at` column** (Flyway V-migration + `Flight` field set on the Valid→Locked transition); wire the gate into the existing `FlightStateTransitionService` (reject too-recent lock/bill); boundary unit/IT (1d before reject, on/after allow, `Clock.fixed`). Record the divergence in this file's Parity decisions (done) + leave the ADR-amendment flag for `/do-retro`. *(seam: FlightGatePolicy + Clock + locked_at migration + transition wiring + boundary IT)*
- [ ] **T-03** — **Single-flight tenant scoping**: verify/confirm GET/PUT/DELETE `/{id}` scope by `@TenantId` (close the oracle #24 legacy leak); add a cross-tenant **404** IT (`FlightsAuthorizationIT` or equivalent). If the repo already filters via `@TenantId`, this is verify + test only. *(seam: single-id read/write path + tenant IT)*
- [ ] **T-04** — S-062h **412 conflict-diff dialog + 409 toast** (frontend, gate slice only — NO drafts/SW/3G breadth): `flight-conflict-prompt.component.ts` (`<af-dialog>` per-field keep-mine/keep-theirs, first conflict focused, Enter activates, **no auto-retry**) + wire `FlightStore.save` 412 → dialog (replace the placeholder toast), 409 → "reload latest" toast; Vitest for the conflict resolver. *(seam: conflict-prompt component + FlightStore.save wiring)*
- [ ] **T-05** — S-064 **`/airmovements`** route + motor-filtered **shared** list/form (no legacy-style duplication): one parameterized list/form, the `/airmovements` route binds the MOTOR filter + hides the tow step; nav entry + i18n (de/en/fr/it). *(seam: airmovements route + motor-filter param on the shared components)*
- [ ] **T-06** — S-062e **af-date-picker `mode="range"` zoneless fix**: verify whether the deadlock persists; if the list still uses the two-single-picker workaround, fix the primitive + revert to a single range picker; `/dev/primitives` range showcase loads < 2s. *(seam: af-date-picker primitive + flights-list filter revert)*
- [ ] **T-07** — **Flight + FlightCrew migration** bindings + producer SELECT: register/confirm FLIGHT (+ FLIGHT_CREW) in `MapperLegacyBindings` with the producer SELECT **reconciled against the real legacy `Flight`/`FlightCrew` MSSQL schema** (expect ≥1 column mismatch like J-1 T-16) + `FlightMigrationRoundTripIT` + `FlightRealProducerRoundTripIT` (tow self-FK two-pass, empty-guid→null, crew nesting, `flight_date`/`locked_at`/`created_at`, lossy air-state→`flight_plan_opened_on`). *(seam: MapperLegacyBindings FLIGHT producer + 2 round-trip ITs)*
- [ ] **T-08** — **Flight parity bundle seeder + legacy MSSQL flight seed** (mirror `AircraftParityBundleSeeder`): synth migrated-flight bundle byte-aligned with T-07's IT (glider+tow paired, motor, a too-recent + a lockable flight, a `DeliveryBooked` read-only flight, a cross-tenant flight) + Gradle task; the legacy seed driving the real export/video. *(seam: parity bundle seeder + legacy seed)* — deps T-07.
- [ ] **T-09** — **Thicken** the real-idp spec to full oracle assertions (glider+tow+motor CRUD, cross-tenant 404, time-gate `flight_date` lock + `locked_at` bill + `DeliveryBooked` read-only, 412 diff, migrated-flight render) + fold **S-062f** (`flights-list.spec.ts` air-state dropdown reliability — green without retries). *(seam: the real-idp spec + flights-list spec)* — deps T-02..T-08.
- [ ] **T-10** — **Legacy flsweb flight video + paired screenshots** (done-bar, e2e-driver): legacy flight list+form video + paired legacy↔AlpenFlight list/form screenshots in the gallery, auto-posted PR link (mirror J-1 T-14/T-19/T-20); wired into `alpenflight-proof-fanout.yml`. *(seam: fanout legacy flight capture + gallery declaration)* — e2e-driver.
- [ ] **T-11** — **Boyscout: ci.yml docs-only path-filter** — skip `alpenflight build` + `alpenflight-proof` on docs/skill/story-only pushes (`docs/**`, `.claude/**`, root `*.md`), required aggregator green via skipped-to-success. *(seam: ci.yml path filter + required aggregator)* — clears the `_BOYSCOUT.md` bullet.
- [ ] **T-12** — **Boyscout: modernize-\* sunset** — delete the 9 `modernize-*` skills + ~12 modernize agents + prune the `rolled_up_into:` horizontal stories (keep 47 `implemented/`). Mechanical; fold only if it doesn't bloat the gate PR, else defer (note for the next feature journey). *(seam: .claude/skills/modernize-*, .claude/agents/*, rolled_up_into stories)* — clears the `_BOYSCOUT.md` bullet.

## Assumptions made

1. `/airmovements` is the **same** SPA screen parameterized by the MOTOR filter (S-064's whole
   point — no legacy-style duplication), so it stays within this one-screen journey rather than
   splitting into its own.
2. The Flight + FlightCrew migration mappers exist from J-0b authoring but are **unproven
   end-to-end** ([[verify_infra_is_run_not_just_authored]]) — sized build/verify, expecting a
   real-export producer-SELECT catch like J-1 T-16.
3. ~~S-061's `flight_date`/`locked_at` gate wording is provisional~~ **RESOLVED (operator 2026-06-03):**
   gate on `flight_date`/`locked_at` (S-061 wording) — a deliberate divergence from legacy `CreatedOn`;
   adds a net-new `locked_at` column + an ADR amendment. See Parity decisions.
4. The 412/optimistic-concurrency path is a **new-stack affordance** (legacy is last-write-wins),
   so its spec assertions prove new behavior, not legacy parity.
