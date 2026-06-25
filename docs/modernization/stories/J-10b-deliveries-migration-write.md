---
id: J-10b
title: Deliveries — migration + write side (create / book-terminal / delete)
epic: E-09
status: todo
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
