---
id: J-10
title: Deliveries (invoice drafts)
epic: E-09
status: todo
journey0: false
carved: true
depends_on: [J-9]
rolls_up: [S-078]
acceptance:
  # ≥feature — the /deliveries screen: the rules-engine output persisted as an invoice draft, booked + numbered
  - "[happy] /deliveries list renders the club's deliveries (delivery number, recipient, batch, state) tenant-scoped, paged + sortable; reachable via the masterdata nav (spec ENTERS via the dropdown)"
  - "[happy] create: trigger delivery creation for a Locked flight → the J-9 engine output persists as a Delivery (line items + frozen recipient snapshot + flight link) in state Prepared; appears in the list"
  - "[happy] view a delivery: read-only line items (position/article/itemText/quantity/unitType), the 9-field frozen recipient snapshot, and the linked flight info — line items are engine output, not hand-editable"
  - "[happy] book: Prepared → Booked allocates a GAP-FREE delivery_number from the per-club counter + sets delivered_on; Booked is terminal (read-only)"
  - "[key-error] mutate or delete a Booked delivery → 409 (terminal / OR Art. 957a frozen)"
  - "[happy] delete a Prepared delivery → resets the linked flight's process state back to Locked"
  - "[edge] cross-tenant GET of another club's delivery → 404 (tenant isolation)"
  - "[migration/parity] migrated legacy deliveries render under their migrated TestClub tenant, identity-matched to legacy (the migrated done-bar — binds the Delivery + DeliveryItem mappers; HARD fanout gate per the J-9 retro)"
screen: /deliveries (list + view + book + delete) — replacing legacy masterdata/deliveries/
headless_pulled_in: the J-9 rules-engine output → persisted Delivery (the AccountingDeliveryEngine → Delivery aggregate orchestration); the per-club gap-free delivery_number counter
migration: Delivery + DeliveryItem (legacy Delivery/DeliveryItem → V4 t_delivery/t_delivery_item) — binds the authored-but-unbound DeliveryMapper + DeliveryItemMapper
parity_test: alpenflight/web/e2e/tests/real-idp/deliveries-parity.spec.ts
adr_refs: [0008, 0020, 0022, 0027]
---

## Context

The invoice-draft surface: a **Delivery** is the immutable output of the J-9 rules engine — the
billing line items + frozen recipient snapshot for one flight, which the operator reviews and **books**
(assigns a gap-free invoice number, per Swiss OR Art. 957a). J-9 proved the engine in isolation (dry-run,
not persisted); J-10 persists its output as a `Delivery` aggregate and ships the `/deliveries` screen +
the legacy `Delivery`/`DeliveryItem` migration. This is the first journey to BIND the V4 Delivery mappers,
so it carries the migrated done-bar (and, per the J-9 retro, a HARD fanout gate).

## Spec must assert

Grounded in legacy `flsweb/src/masterdata/deliveries/` (list `DeliveriesEditController.js`, edit
`deliveries-edit.html`) + `flsserver` `DeliveriesController.cs` / `Delivery.cs`:

- **The screen** — list (delivery_number, recipient, batch_id, state; paged, sort batch desc / recipient
  asc, tenant-scoped) + view (read-only line items, the frozen 9-field recipient snapshot, read-only flight
  link). **Line items are NOT hand-editable** — they're engine output; the operator shapes them via J-8
  AccountingRuleFilters.
- **The state machine** — `process_state_id`: 10 Prepared → 20 Booked (terminal), 30 Error, 99 Cancelled
  (V4 cutover collapses legacy `flight.ProcessStateId` + `delivery.IsFurtherProcessed` into one enum).
  **Booked is terminal**: any mutation/delete → 409. **Delete of a Prepared delivery** resets the linked
  flight to Locked (legacy `DeliveriesEditController.js` delete confirm + flight reset).
- **book() — gap-free numbering** (the load-bearing invariant): on Prepared → Booked, allocate the next
  `delivery_number` from `t_club_delivery_number_counter` (atomic `UPDATE…RETURNING`, per-club), set
  `delivered_on`, freeze the recipient. The `ux_dlv_club_number_partial` UNIQUE index is the structural
  backstop; "Booked rows carry a number" is a `Delivery.book()` precondition (ADR 0022 D2 — business rule
  on the aggregate, not a DB CHECK). Two bookings → consecutive numbers, no gap.
- **The engine → Delivery handoff** — `AccountingDeliveryEngine.computeForFlight(flightId)` returns the
  in-memory `RuleBasedDeliveryDetails` (J-9, stable); J-10's service maps it (+ flight metadata) to a
  persisted `t_delivery` row + `t_delivery_item` rows. DoNotInvoice → skip; no items / no recipient →
  Error state. Dispatch `legacy-oracle` at ship time for the exact create-validation + state-transition
  guards if the implementer can't derive them from `DeliveryService.cs`.

## Notes

**Scope — right-sized to the screen vertical slice (carve decision).** The `_ORDER` roll-up listed 7
stories; per do-plan (one screen/route, headless pulled in by its screen, 60/40), J-10 carries **S-078**
(the `/deliveries` CRUD + state machine) + the Delivery migration + the engine→persist path + the J-9-retro
riders. **Deferred + re-homed (recorded, not built here):**
- **S-089 `DeliveryCreationJob`** (scheduled engine-over-locked-flights persist) — J-10 builds the
  create LOGIC + an on-demand trigger (enough for the screen + the Playwright create→book→delete proof);
  the **scheduled cron/batch/per-flight-error-isolation wrapper rides J-15** (Scheduled-jobs console).
- **S-090 `DeliveryMailExportJob`** (POI Excel zip → per-club email) — a separate headless workflow →
  **J-15** (or its own follow-up).
- **S-029 / S-080 / S-150 Proffix** (client-credentials machine client + API parity + live-consumer
  verification) — J-10 ships the read GET endpoints the screen needs; the **Proffix-specific
  `ROLE_PROFFIX_SYNC` auth + external verification ride a follow-up** (note for `/do-plan`).
- **S-087 `AircraftStatisticReportJob`** — **wrong domain** (aircraft stats, not deliveries); drop from
  J-10's roll-up, leave on the backlog for a reporting journey.

**Deferred engine paths (from J-9, still deferred):** the **PersonFlightTimeCredit/discount sub-engine**
(J-9b — J-10 persists the Delivery WITHOUT credit balance/split/`PersonFlightTimeCreditTransaction`
side-effects) and the text-only **DeliveryDetailsStage** (`deliveryInformation`/`additionalInformation`
— needs MatchableFlight crew-display fields it doesn't carry; the view shows whatever the engine emits,
null-tolerant).

**Riders to fold (BLOCKING — `_BOYSCOUT.md`, J-9-retro):** J-10 is a migration journey → its `fan-out
parity` job is a HARD merge gate (J-9 retro). It cannot go green until:
- the **migration-bundle-ingest 409** is fixed (`ensureSharedMigrationBundle` polls the deployment to
  `COMPLETED` + treats `409 DEPLOYMENT_EXISTS` as reuse) — reds every migrated-parity spec today;
- the **J-9 migrated done-bar article-5001** is resolved (migrated FlightTime filter not applying / the
  TestClub export lacks the inputs).
Both MUST be `T-NN`s in J-10, not deferred.

**Going-in substrate (V4, verified):** `t_delivery` / `t_delivery_item` / `t_club_delivery_number_counter`
schemas exist (`process_state_id` SMALLINT 10/20/30/99; `ux_dlv_club_number_partial`; FK `flight_id`,
`recipient_person_id` SET NULL, `article_id` RESTRICT). `DeliveryMapper` + `DeliveryItemMapper` are
authored + registered in `migration-bundle` but **migration-only — NO server-side Delivery @Entity / repo
/ service / controller exists yet** (J-10 builds all, like J-9 built the engine). The mapper binding +
the real fanout is what proves the producer SELECT.

**Seam hints (non-binding, one seam each):** the `Delivery` aggregate + state machine (Prepared/Booked/
Error/Cancelled + book() precondition); the `DeliveryItem` child + the gap-free `delivery_number` counter
allocation; the `AccountingDeliveryEngine` → `Delivery` persist service (S-089 create logic + on-demand
trigger); the `DeliveryRepository` (@TenantId, JPA-first per ADR 0027); the delivery REST resource (list/
view/book/delete; delete-resets-flight); the `/deliveries` SPA screen (list + view + book/delete actions);
the **Delivery + DeliveryItem migration binding** (the migrated done-bar); the `ensureSharedMigrationBundle`
409 fix; the article-5001 fix + bit-exact migrated assertion; the parity spec.

## Assumptions made

1. `depends_on: [J-9]` — the engine (`AccountingDeliveryEngine`, `RuleBasedDeliveryDetails`) is done +
   stable; J-10 consumes its in-memory output and persists it. No credit path (J-9b deferred).
2. One screen/route (`/deliveries`, list + view + book + delete). The line-item table is read-only
   (engine output); there is no per-line edit UI in legacy or here.
3. `delivery_number` is allocated at **book()** (not at create), gap-free per-club via the counter table —
   matching legacy (the number is assigned at booking, historically operator-entered text).
4. Migration is load-bearing: J-10 binds the Delivery/DeliveryItem mappers, so the **real fanout is a hard
   gate** — the synth bundle aliases columns and won't prove the producer SELECT.
5. The scheduled jobs (S-089 cron, S-090 mail export) + Proffix (S-029/S-080/S-150) re-home to J-15 / a
   follow-up; S-087 leaves J-10 entirely (wrong domain). `_ORDER` updated to reflect the re-scope.
