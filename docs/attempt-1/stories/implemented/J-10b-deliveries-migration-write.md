---
id: J-10b
title: Deliveries — migration + write side (create / book-terminal / delete)
epic: E-09
status: done
started_at: 2026-06-25
done_at: 2026-06-26
journey0: false
carved: true
depends_on: [J-10, J-11, J-2, J-9b]
rolls_up: [S-078]
acceptance:
  - "[happy] On /deliveries a CLUB_ADMINISTRATOR triggers the 'create deliveries' action → the J-9 rules engine produces one Delivery (+ DeliveryItems) per eligible flight (Locked, flight-type glider/motor, CreatedOn ≤ today−3d); the new deliveries appear in the list tenant-scoped (engine→persist; POST /api/v1/deliveries/create — legacy's mutating GET modernized)."
  - "[happy] Delete a Prepared delivery → it + its DeliveryItems remove (soft-delete by parent), the linked flight AND its tow flight reset to Locked (persisted), and the PersonFlightTimeCredit transaction balanced by this delivery is REVERSED (append-only negated row, IsCurrent flipped, original kept). Audited."
  - "[key-error] Delete is rejected when >1 delivery shares the flight → 409, surfaced on the screen, no partial mutation."
  - "[key-error] Booked is terminal: a Booked delivery / a flight in DeliveryBooked rejects any further delete or re-book → 409, no mutation."
  - "[edge] Book a Prepared delivery via POST /api/v1/deliveries/delivered sets free-text delivery_number, DeliveredOn, IsFurtherProcessed=true, flips flight(+tow) → DeliveryBooked; unknown id → 200 false. The interactive booker is external (Proffix); the spec drives the endpoint."
  - "[edge] Cross-tenant write (create/delete/book a delivery in another club) → 404/403 (every write query @TenantId/ClubId-scoped)."
  - "[migration] Legacy Deliveries + DeliveryItems migrate tenant-scoped (Delivery.club_id = direct @TenantId; DeliveryItem parent-scoped via Delivery). DeliveryItem.ArticleNumber resolves to the migrated J-11 article_id per club; an UNRESOLVABLE ArticleNumber is kept-null — NOT a 23503 bundle failure. Free-text delivery_number preserved verbatim. The fan-out parity JOB is green on the final sha."
screen: /deliveries (J-10's read screen + the write actions create + delete; booked-terminal guard) — replacing legacy masterdata/deliveries/ write path
headless_pulled_in: "the J-9 rules-engine create → the screen's 'create deliveries' action; the /delivered booking endpoint → driven by the spec (the interactive booker is external Proffix). No new admin screen."
migration: "Delivery + DeliveryItem (dbo.Deliveries / dbo.DeliveryItems) — ArticleNumber→article_id (J-11) + orphan-keep, nullable FlightId (J-2); delete reverses J-9b PersonFlightTimeCredit."
parity_test: alpenflight/web/e2e/tests/real-idp/deliveries-write-parity.spec.ts + alpenflight/server/.../migrations/web/DeliveryCollisionOrphanProducerIT.java
adr_refs: [0005, 0008, 0022, 0026, 0027]
---

## Context

J-10 shipped the read-only `/deliveries` screen + ported the `Delivery`/`DeliveryItem` entities. J-10b adds the
**write side** — create (J-9 engine → persist), the Prepared→Booked terminal state machine, delete (resets the
linked flights + reverses the balanced flight-time-credit) — plus the **Delivery/DeliveryItem migration**
deferred until J-11 migrated ARTICLE. The write side is the accounting-correctness seam; the money paths
(credit reversal, flight-state reset) are the load-bearing assertions.

## Spec must assert

Grounded in `flsserver/.../Accounting/DeliveryService.cs` + `FlightService.cs`. The real-idp run drives the
real `/deliveries` UI (entered via the masterdata nav):

1. **Create (engine→persist).** The 'create deliveries' action runs the J-9 engine over Locked, glider/motor,
   `CreatedOn ≤ today−3d` flights; one Delivery (+items) per flight; flight(+tow) → DeliveryPrepared; a credit
   transaction is added.
2. **Delete (the destructive money path).** Cascade items; reset flight **and tow** → Locked (persisted);
   **reverse** the balanced credit transaction (append-only). Reject when >1 delivery shares the flight.
3. **Booked-terminal.** A booked delivery / a flight in DeliveryBooked rejects delete + re-book → 409.
4. **Booking.** `/delivered` stamps the free-text `delivery_number`, DeliveredOn, IsFurtherProcessed, flips
   flight(+tow) → DeliveryBooked; unknown id → 200 false.
5. **Migration round-trip.** The collision/orphan IT proves an orphan ArticleNumber resolves→null (not 23503)
   and that multiple deliveries per flight ingest (no UNIQUE(flight_id)); the fan-out parity job is green.

## Decisions (load-bearing — parity exclusions + money-bug sign-offs)

**Deliberate legacy→AlpenFlight modernizations** (cosmetic verb/status divergences; the reject/persist behavior
is exact parity, so legit per the parity-exclusion rule):
- **Create verb** — legacy `GET /deliveries/create` (mutating GET) → `POST /api/v1/deliveries/create`.
- **Status codes** — booked-terminal reject (legacy 400) and the >1-delivery delete guard (legacy unmapped
  500) → **409 Conflict** (correct conflict semantics, matches the flights-store optimistic-lock convention).
- **`delivery_number`** — a single nullable **text** column (legacy free-text `nvarchar(100)`); no counter
  exists in legacy, so none was built. The legacy "gap-free counter" roadmap wording was oracle-refuted.
- **Item delete** — soft-delete by parent-invisibility (not a DB CASCADE hard-delete); the `balanced_delivery_id`
  back-ref + audit trail need the item rows to survive, so `fk_dli_delivery_id ON DELETE CASCADE` never fires.

**Schema guards (hard — real legacy data violates the opposite):** NO `UNIQUE(flight_id)` (legacy permits
multiple deliveries per flight — the delete guard exists precisely for that), NO `UNIQUE(delivery_number)`
(nullable, collisions exist), `balanced_delivery_id` FK stays `ON DELETE SET NULL` (not CASCADE — transactions
survive a delivery delete). V52 dropped the stray `ux_dlv_club_number_partial`; V53 made `article_id` nullable
+ dropped the dead `t_club_delivery_number_counter`.

**Three reachable legacy money bugs — fixed, not reproduced (ADR 0026 pattern; operator sign-off). All confirmed
reachable + guarded by a DB-re-read / red→green discriminator test:**
1. **Tow-flight reset wrong target** (`FlightService.cs:1482` resets `flight` inside the tow block) → AlpenFlight
   resets the correct **tow** flight. `DeliveryDeleteControllerIT` re-reads both `process_state_id` from the DB
   after the HTTP DELETE.
2. **Missing `SaveChanges`** (`FlightService.cs:1457-1493` never persists the Prepared→Locked reset) → AlpenFlight
   persists; same IT proves it via DB re-read (not in-memory).
3. **Glider+tow shared-credit under-consume** (legacy last-write-wins over an unflipped balance) → AlpenFlight
   **sums** both passes (`RuleBasedDeliveryDetails.recordCreditConsumption` `Long::sum`). Confirmed **reachable**:
   credit matches a `matched_aircraft_immatriculations` CSV substring, per-person, reused across the tow
   recursion — one credit listing both immats matches both passes. Discriminator
   `DeliveryItemPipelineTest.gliderAndTowSharingOneCreditSumBothPassesOntoIt` (glider 1800s + tow 600s → 2400;
   flipping `merge`→`put` REDs it at 600 = the legacy under-consume).

## Tasks

- [x] **T-01** — spec stub (6-case scaffold + testids) + J-10b proof-gallery page; `parity_test:` auto-scopes the per-push real-idp gate.
- [x] **T-02** — `Delivery.createFromEligibleFlight` (engine→persist) + `POST /create`.
- [x] **T-03** — `Delivery.delete` + `DELETE /{id}`: reset flight+tow (persisted) + append-only credit reversal + >1-delivery 409.
- [x] **T-04** — booked-terminal 409 guard + `POST /delivered` booking.
- [x] **T-04b** — `delivery_number` Integer→single nullable text column.
- [x] **T-05** — Delivery/DeliveryItem migration mapper (ArticleNumber→article_id + orphan-keep; schema guards; dead-counter drop).
- [x] **T-06** — real-producer collision/orphan IT (orphan ArticleNumber → null; multi-delivery-per-flight → no 23505).
- [x] **T-07** — clean-seed write fixtures (eligible flight + deterministic rule filter, glider+tow credit, shared-flight, cross-tenant).
- [x] **T-07b** — green the backend batch (counter-drop baseline test, collision-IT seed, fixture naming).
- [x] **T-08** — `/deliveries` write actions over `DeliveriesStore` + folded [NG8113-DEADIMPORT].
- [x] **T-09** — thicken the real-idp spec to full assertions (7 cases incl. the money proof) + gallery captures.
- [x] **T-10** — stabilize the 4 chronic real-idp flakes.
- [x] **T-11** — bug-3 within-delivery SUM discriminator (reachability established) + re-book-of-booked 409 IT.

## Outcome

Shipped — 14 tasks; real chain green at **job level on 8d20a3ff** (real-idp clean-seed + synth proof + the
migration `fan-out parity` job: V53 producer-SELECT export against the real MSSQL schema + migrated
Delivery/DeliveryItem round-trip). gap-hunter ×3 cleared: VERTICAL / SCOPED (no tenancy leak, migration honest) /
MONEY-CORRECT. No mocked seams. The three reachable legacy money bugs are fixed-not-reproduced, each with a
DB-re-read or red→green discriminator. cpd baseline held at 5362.
