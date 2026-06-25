---
id: J-10b
title: Deliveries — migration + write side (create / book-terminal / delete)
epic: E-09
status: in_progress
started_at: 2026-06-25
journey0: false
carved: true
depends_on: [J-10, J-11, J-2, J-9b]
rolls_up: [S-078]
acceptance:
  - "[happy] On /deliveries a CLUB_ADMINISTRATOR triggers the 'create deliveries' action → the J-9 rules engine produces one Delivery (+ DeliveryItems) per eligible flight (Locked, >3 days old, matching accounting-rule filters); the new deliveries appear in the list tenant-scoped (engine→persist; GET /api/v1/deliveries/create parity)."
  - "[happy] Delete a Prepared delivery → it + its DeliveryItems cascade-remove, the linked flight (AND its tow flight) reset to Locked, and any PersonFlightTimeCredit transaction balanced by this delivery is REVERSED (compensating negated transaction, IsCurrent flipped). Audited."
  - "[key-error] Delete is rejected when >1 delivery shares the flight (legacy guard parity) — surfaced on the screen, no partial mutation."
  - "[key-error] Booked is terminal: a Booked delivery (IsFurtherProcessed=true) / a flight in DeliveryBooked rejects any further edit/delete → 409, no mutation."
  - "[edge] Book a Prepared delivery via the /delivered endpoint sets delivery_number (free-text, request-supplied), DeliveredOn, IsFurtherProcessed=true, and flips the flight (+tow) → DeliveryBooked. The interactive booker is external (Proffix); the spec drives the endpoint."
  - "[edge] Cross-tenant write (create/delete/book a delivery in another club) → 404/403 (tenant isolation, every query ClubId-scoped)."
  - "[migration] Legacy Deliveries + DeliveryItems migrate tenant-scoped (Delivery.club_id = direct @TenantId; DeliveryItem parent-scoped via Delivery). DeliveryItem.ArticleNumber resolves to the migrated J-11 article_id per club; an UNRESOLVABLE ArticleNumber (free-typed / deleted article) is kept-null / skipped-with-log — NOT a 23503 bundle failure. Free-text delivery_number preserved verbatim. The fan-out parity JOB is green on the final sha."
screen: /deliveries (J-10's read screen + the write actions create + delete; booked-terminal guard) — replacing legacy masterdata/deliveries/ write path
headless_pulled_in: "the J-9 rules-engine create (GET /deliveries/create) → the screen's 'create deliveries' action; the /delivered booking endpoint → driven by the spec (the interactive booker is external Proffix). No new admin screen."
migration: "Delivery + DeliveryItem (dbo.Deliveries / dbo.DeliveryItems) — needs J-11 ARTICLE (ArticleNumber→article_id resolution + orphan-keep) and J-2 Flight (nullable FlightId) migrated; delete reverses J-9b PersonFlightTimeCredit."
parity_test: alpenflight/web/e2e/tests/real-idp/deliveries-write-parity.spec.ts (new) + a Delivery/DeliveryItem collision/orphan migration IT
adr_refs: [0005, 0008, 0022, 0026, 0027]
---

## Context

J-10 shipped the read-only `/deliveries` screen + ported the `Delivery`/`DeliveryItem` entities (read path
only, clean-seed). J-10b completes the feature: the **write side** — create deliveries from eligible flights
(the J-9 rules engine output, persisted), the **Prepared→Booked** terminal state machine, and **delete**
(which resets the linked flights' process state and reverses any flight-time-credit it balanced) — plus the
**Delivery/DeliveryItem migration** that J-10 deferred until J-11 migrated ARTICLE. A Delivery is the
immutable billing output of a flight; the write side is the accounting-correctness seam, so the money paths
(credit reversal, the flight-state reset) are the load-bearing assertions.

## Spec must assert

Grounded in `flsserver/.../Accounting/DeliveryService.cs` + `FlightService.cs` + `flsweb/src/masterdata/deliveries/`.
The real-idp run drives, through the real `/deliveries` UI (entered via the masterdata nav):

1. **Create (engine→persist).** The 'create deliveries' action → `CreateDeliveriesFromFlights` over Locked
   flights >3 days old with matching accounting-rule filters (`DeliveryService.cs:48,65`), one Delivery
   (+items) per flight; new deliveries render in the list. (Legacy `GET /api/v1/deliveries/create`,
   `DeliveriesController.cs:100`. The manual-create SPA POST is dead/404 in legacy — do NOT build it.)
2. **Delete (the destructive money path).** `DELETE /api/v1/deliveries/{id}` (club-admin): cascade-delete
   items; reset the flight **and tow flight** `ProcessState → Locked` (`Flight.cs:640`); **reverse** the
   `PersonFlightTimeCreditTransaction` balanced by this delivery (negated seconds, `IsCurrent` flipped —
   `DeliveryService.cs:1257`). Reject when >1 delivery shares the flight (`DeliveryService.cs:1242`).
3. **Booked-terminal.** A flight in `DeliveryBooked` rejects any state change → 409
   (`FlightService.cs:1427`); a Booked delivery is immutable.
4. **Booking.** `/delivered` (`DeliveryService.cs:338`) stamps the free-text `delivery_number`, `DeliveredOn`,
   `IsFurtherProcessed=true`, flips flight(+tow) → `DeliveryBooked`. Driven via the endpoint (external booker).
5. **Migration round-trip.** The collision/orphan IT seeds a `DeliveryItem.ArticleNumber` with NO matching
   Article and proves the bundle still ingests (resolve→null/skip, not 23503); the fan-out parity job is green.

## Notes

- **CARVE CORRECTION — no gap-free counter.** The roadmap row said "gap-free delivery_number counter"; the
  oracle refuted it: `delivery_number` is a **free-text string, assigned at booking, externally supplied**
  (Proffix sacred-cow, `Delivery.cs:62`, `DeliveryService.cs:338`). There is no NumberRange/sequence/max+1 in
  legacy. **Do NOT build a counter** — that invents behavior. The migration preserves the string verbatim.
- **Two reachable LEGACY BUGS — fix, do not reproduce (operator sign-off at /do-ship review, ADR 0026
  pattern).** `DeleteDeliveriesAndUpdateProcessStatesOfFlight` (`FlightService.cs:1457-1493`): (a) line 1482
  sets `flight.ProcessStateId` where it means `towFlight.ProcessStateId` (wrong target), and (b) the method
  never calls `SaveChanges()` before its DbContext disposes, so the whole Prepared→Locked batch reset is
  silently dropped. Both are reachable (`FlightService.cs:1420`) and money/safety-adjacent (flights stay
  falsely Prepared, blocking re-billing). The new write side must persist the transition + reset the CORRECT
  (tow) flight. `gap-hunter` should confirm a migrated/real flight actually flips to Locked, not just the spec.
- **FK closure / tenancy (depends_on rationale).** `Delivery.club_id → Club` (direct `@TenantId`, J-0);
  `DeliveryItem` has **no club_id** → parent-scoped via its `Delivery` (the mapper derives item tenant from
  the parent). Hard new invariant: `DeliveryItem.ArticleNumber → article_id` (J-11) — legacy stores a free
  ArticleNumber **string** with no FK, so unresolvable numbers must keep-null/skip (not orphan the bundle).
  Soft FKs (nullable, but the write behavior needs the targets migrated): `Delivery.FlightId → Flight` (J-2),
  and the credit reversal reads `PersonFlightTimeCreditTransaction.BalancedDeliveryId` (J-9b) — both already
  merged, hence in `depends_on`. `Delivery.RecipientPersonId` is a **loose nullable Guid with NO FK** — keep
  as a raw id, do not add an FK. Recipient name/address are a denormalized snapshot, not a live FK.
- **Migration-fidelity (cluster expected).** `BalancedDeliveryId` on already-migrated J-9b credit rows must
  re-resolve to the NEW migrated delivery ids — mine the real fanout traces for actual values, don't derive.
  The collision/orphan IT (orphan ArticleNumber) catches the common 23503 in `check` before the ~20-min fanout.
- **No design reference** — `design-reference/` has no deliveries screen; build the write affordances onto
  J-10's existing read screen from the legacy `flsweb/src/masterdata/deliveries/` behavior + the AlpenFlight UI kit.
- **Riders to fold (≤40% slot).** **[REALIDP-FLAKE-QUARANTINE]** (J-10b's gate runs the full real-idp
  regression — stabilize/quarantine the 4 chronic flakes so merge-shards stop redding); any fanout-debug
  riders touching the migration path. (`_BOYSCOUT.md`.)
- **Seam hints (non-binding, for /do-ship).** The `Delivery` aggregate's write methods (create-from-engine,
  book→terminal guard, delete→reset-flights+reverse-credit) + repo — one aggregate; the Delivery/DeliveryItem
  per-entity mapper (ArticleNumber resolution + orphan-keep + parent-scoped item tenancy) — one mapper; the
  `/deliveries` SPA write actions (create button + delete confirm, booked-disabled) over J-10's store — one
  component slice; the collision/orphan migration IT — one IT; the legacy seed (Locked-flight + booked +
  shared-flight + credit-balanced fixtures) — one seed contribution.

## Assumptions made

- `depends_on: [J-10, J-11, J-2, J-9b]` — J-10 (read screen + entities, merged), J-11 (ARTICLE, merged #236),
  J-2 (Flight, merged), J-9b (PersonFlightTimeCredit, merged #232). All satisfied; no unbuilt dependency.
- The interactive write actions are **create + delete** (the demonstrable UI money paths); **booking** is an
  external/Proffix endpoint, so the spec drives `/delivered` directly rather than through a UI button. If the
  operator wants an interactive book button, that's a follow-up screen action, not this journey's contract.
- The Proffix `/delivered` DeliveryNumber **format** lives in an off-limits external repo (PROFFIX-FLS-Sync);
  legacy only proves the field is a free-text string. If write-side parity needs the exact format, escalate to
  the Proffix maintainer (seed §Proffix, S-150) — not derivable here.
- Carved on **`do-retro/J-12b-window`** (clean off `origin/main`, retro parent == main), so that retro's
  suite edits + the J-12b-window riders ride J-10b and merge with it (fix-forward).

## Oracle-pinned contract (ship-time corrections to the carve)

The `legacy-oracle` read of `DeliveryService.cs`/`FlightService.cs`/`Flight.cs` pinned these — they
override any looser carve wording. Deliberate legacy→AlpenFlight modernizations are **cosmetic
error/verb divergences** (the reject/persist BEHAVIOR is exact parity), legit per the parity-exclusion rule:

- **Create verb.** Legacy is `GET /deliveries/create` (mutating GET — a quirk). AlpenFlight: **`POST
  /api/v1/deliveries/create`** (ClubAdmin) → 200 + created list. Eligibility filter: `ProcessState=Locked`
  AND flight-type ∈ {Glider, Motor} AND **`CreatedOn ≤ today−3d`** (NOT StartDateTime/LockedOn), club-scoped.
- **Create's side effects (oracle, not in carve).** Per eligible flight create ALSO flips the flight **and
  tow flight → `DeliveryPrepared`** + stamps `DeliveryCreatedOn`, assigns `BatchId = max+1` (app-generated
  `long`, not a DB sequence), and **adds** a `PersonFlightTimeCreditTransaction` (`IsCurrent=true`, prior
  flipped false) — so delete's credit *reversal* undoes a credit that *create* made. Per-flight failures are
  swallowed (`ExcludedFromDeliveryProcess` / `DeliveryPreparationError`), the batch never aborts.
- **Status codes.** Booked-terminal reject: legacy throws **400** (`BadRequestException`); `>1-delivery-per-
  flight` delete guard: legacy throws an **unmapped 500** (`FLSServerException`). AlpenFlight returns **409
  Conflict** for both (correct conflict semantics + matches the flights-store optimistic-lock convention). Non-
  admin delete → 401/403; missing delivery → 404. Booking unknown id → 200 `false` (not an error — parity).
- **Credit reversal is append-only.** The original balanced transaction row is **kept** (`IsCurrent`→false); a
  new negated reversal row is inserted (`IsCurrent`→true). Do NOT delete the original — the audit trail needs it.
- **Schema guards (hard — real legacy data violates the opposite).** Do **NOT** add `UNIQUE(flight_id)` on
  `delivery` (legacy permits multiple deliveries per flight — that's *why* the delete guard exists),
  `UNIQUE(delivery_number)` (nullable free-text, collisions exist), or `CASCADE` on
  `person_flight_time_credit_transaction.balanced_delivery_id` (nullable, no-cascade — transactions must
  survive a delivery delete or the reversal breaks). The collision IT proves multiples + orphan-ArticleNumber.
- **Both legacy bugs confirmed:** `FlightService.cs:1482` sets `flight.ProcessStateId` inside the
  `towFlight` block (wrong target); `:1457-1493` opens its own DbContext and never `SaveChanges()` before
  dispose (the Prepared→Locked reset is silently dropped). AlpenFlight must persist + reset the **tow** flight.
  `gap-hunter` confirms a real migrated flight actually flips to Locked, not just the spec. **Surfaced to the
  operator at §5 review for sign-off** (ADR 0026 fix-not-reproduce pattern) — a reachable money/safety divergence.
- **THIRD legacy money bug (net-new, found T-02, operator pre-blessed "Proceed" 2026-06-25 → fix-not-reproduce;
  confirm at §5 with proof).** When a glider flight + its tow flight both draw on ONE `PersonFlightTimeCredit`,
  legacy applies consumption per-pass with **last-write-wins over an unflipped balance** → the second pass
  overwrites the first → the credit is **under-consumed** (the member keeps flight-time they actually used —
  a reachable money error). AlpenFlight **sums** both passes' consumed seconds onto the credit (append-only,
  correct accounting). The discriminator is a seeded glider+tow e2e — built in T-07/T-09, so the §5 review sees
  it proven. Reversal (T-03) negates whatever create applied, so it's correct under either choice.

## Tasks

- [x] **T-01** — Author `deliveries-write-parity.spec.ts` stub (6-case structure + testids + flow, thin
  assertions, ≥1 active `test(`) + scaffold the J-10b proof-gallery page + link from the index. Confirm the
  `parity_test:` frontmatter resolves to this spec (auto-scopes the per-push real-idp gate to J-10b, drops
  J-12b to nightly — `ci.yml:170-249` derives it; no manual workflow edit).
- [x] **T-02** — `Delivery.createFromEligibleFlights` (engine→persist) + `POST /api/v1/deliveries/create`
  (ClubAdmin). Eligibility (Locked, glider/motor, `CreatedOn ≤ today−3d`); one Delivery+items/flight;
  `BatchId=max+1`; flip flight(+tow)→`DeliveryPrepared`+`DeliveryCreatedOn`; add the credit transaction
  (IsCurrent flip); per-flight swallow (Excluded/PreparationError). + domain tests.
- [x] **T-03** — `Delivery.delete` + `DELETE /api/v1/deliveries/{id}` (ClubAdmin). Cascade items; reset
  flight **and tow (persisted, correct target — fix both legacy bugs)** → Locked; reverse the balanced
  credit transaction (append-only, IsCurrent flip, original kept); reject `>1-delivery-per-flight` → 409,
  no partial mutation; non-admin → 401/403. + domain tests asserting the flip persists + the reversal row.
- [x] **T-04** — Booked-terminal guard (flight/delivery in `DeliveryBooked` rejects mutation → 409) +
  `POST /api/v1/deliveries/delivered` `{deliveryId, deliveryDateTime, deliveryNumber}` → stamp number/
  DeliveredOn/IsFurtherProcessed, flip flight(+tow)→`DeliveryBooked`, 200; unknown id → 200 `false`. + tests.
- [x] **T-04b** — Correct `delivery_number` to a single nullable **text** column (legacy is free-text
  `nvarchar(100)`, not Integer — the workflow job stamps `"Workflow {ts}"`). Flyway alter on J-10's table +
  change the booking DTO/aggregate (T-04) from `Integer` to `String`; do NOT add a separate
  `legacy_delivery_number_text` column — unify so both the native booking write and the T-05 mapper use one
  text `delivery_number`. (worker-revealed fidelity gap, T-04.)
- [x] **T-05** — Delivery/DeliveryItem migration mapper (`MapperLegacyBindings`): `ArticleNumber→article_id`
  per-club resolution + orphan-keep (unresolvable → null/skip, NOT 23503); parent-scoped DeliveryItem
  tenancy; free-text `delivery_number` verbatim; `BatchId`/bigint-seconds preserved; `BalancedDeliveryId`
  remap on migrated J-9b credit rows → new delivery ids; **no new UNIQUE/CASCADE** (schema guard).
- [x] **T-06** — Collision/orphan migration IT (real-producer): orphan `ArticleNumber` → keep-null/skip
  (not 23503) + a multiple-deliveries-per-flight row proving no `UNIQUE(flight_id)` violation. Reds in
  `check` (minutes), not the ~20-min fanout.
- [x] **T-07** — Legacy seed contribution: a Locked eligible glider flight (`CreatedOn ≤ today−4d`) + a
  minimal **deterministic** AccountingRuleFilter producing one known item + recipient; a `DeliveryPrepared`
  flight + its Delivery + a balanced credit transaction; a `DeliveryBooked` flight; a not-further-processed
  Delivery for booking; a shared-flight (>1 delivery) fixture.
- [x] **T-07b** — Green the backend batch (3 regressions the batch-boundary check found): (A) update `ReservationsBaselineIntegrationTest` (5 cases) for the intended V53 `t_club_delivery_number_counter` drop; (B) fix `DeliveryCollisionOrphanProducerIT` seed to create the referenced flight before inserting the delivery (FK `fk_dlv_flight_id`); (C) fix the bare-table-name fixture SQL flagged by `FixtureTableNamingConventionTest`. `./gradlew check` green.
- [ ] **T-08** — `/deliveries` SPA write actions over `DeliveriesStore`: "create deliveries" button (→ POST
  create, refresh list), delete-confirm modal (booked rows disabled), booked badge; orval regen for the new
  endpoints + store write methods. **Fold [NG8113-DEADIMPORT]** (drop unused `AfButtonComponent` from
  `flight-conflict-prompt.component.ts:41` imports).
- [ ] **T-09** — Thicken `deliveries-write-parity.spec.ts` to full real assertions (oracle-pinned, all 6
  cases) + wire gallery captures (paired legacy↔AlpenFlight shots + pass video + migration round-trip).
- [ ] **T-10** — **[REALIDP-FLAKE-QUARANTINE]** rider: stabilize/quarantine the 4 chronic real-idp flakes
  (`token-lifecycle:87/:190`, `hardening-J26:226`, `fan-out-migration-parity:143`) so the §4 gate's
  merge-shards stop redding (console-guard allow-list for the deliberate errors; warm-nav/longer budget for
  the timeouts).
