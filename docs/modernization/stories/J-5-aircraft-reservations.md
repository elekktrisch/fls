---
id: J-5
title: Aircraft reservations
epic: E-08
status: todo
journey0: false
carved: true
depends_on: [J-1]
rolls_up: [S-068, S-069]
acceptance:
  - "[happy] Create a timed reservation (aircraft + pilot + location + start/end) → persists, appears in the list and on the scheduler in the aircraft's lane at the right time."
  - "[happy] All-day reservation (IsAllDayReservation) stores start=end=day, renders as a full-day band."
  - "[key-error] A second reservation overlapping an existing one on the SAME aircraft + time range is rejected 409 Conflict; editing a reservation does NOT conflict with itself."
  - "[happy] Edit moves time / changes type; delete soft-deletes and the slot frees (a new overlapping reservation then succeeds)."
  - "[edge] Cross-tenant aircraft (charter): operating_club may reserve an aircraft owned by another club; a non-charter aircraft of another club is not reservable (403/422)."
  - "[happy] Paginated list endpoint `POST /api/v1/aircraftreservations/page/{start}/{size}` returns the SPA-shaped page; reservation-type dropdown loads from `/aircraftreservationtypes/listitems`."
screen: /reservations (+ /reservation-scheduler calendar view of the same data)
headless_pulled_in: none
migration: AircraftReservation + AircraftReservationType (mappers already authored+unit-tested in migration-bundle — needs a real round-trip)
parity_test: alpenflight/web/e2e/tests/reservations/reservations-crud.spec.ts
adr_refs: [0005, 0008, 0022]
---

## Context
Reservation conflict detection is real domain logic — the first journey whose load-bearing
proof is a *rejection* (overlap → 409), not just a round-trip. A club books an aircraft for a
window (timed or all-day); the system must refuse a second booking that overlaps the same
aircraft, while allowing an edit of the existing one and allowing legitimately-adjacent
bookings. The screen replaces legacy `flsweb/src/reservations/` (list + edit) and
`flsweb/src/reservation-scheduler/` (the aircraft×time grid) — one reservation route family.

## Spec must assert
The happy path + the conflict rejection, grounded in legacy behavior:

- **Conflict = overlap on the same aircraft**, soft-deleted rows excluded, **self-excluded on
  update**. Legacy enforces this in the service layer over a GiST range probe — the schema
  deliberately has **no** `EXCLUDE` constraint (`V4__…sql:128-130`: legitimate-overlap rules
  exist, so the rule lives on the aggregate per ADR 0022 directive 2). Spec proves: overlap →
  409; edit-in-place of the overlapping row → OK; adjacent `[)` ranges (end==next start) → OK.
- **All-day vs timed.** `IsAllDayReservation` ⇒ start=end=day (legacy `ReservationEditController.save`
  collapses to `YYYY-MM-DD`); timed ⇒ explicit start/end on the day. `reservation_range` is the
  generated `tstzrange(start,end,'[)')` (`V4__…sql:115-118`).
- **Cross-tenant aircraft rule.** Aircraft is cross-tenant (charter, J-1); `aircraft.owner_club_id
  != reservation.operating_club_id` is allowed for charter aircraft only — service layer answers
  "may this operating_club reserve this aircraft?" (`V4__…sql:43-47`). Reuse J-1's charter pattern.
- **SPA-compat endpoints** (preserve wire shape for the ported screen):
  `POST /api/v1/aircraftreservations/page/{start}/{size}` (paged list, `{Sorting,SearchFilter}` body),
  `GET /aircraftreservations/future`, `GET /aircraftreservations/{id}`,
  `POST /aircraftreservations` (create), `POST /aircraftreservations/{id}` + `X-HTTP-Method-Override: PUT`
  (update — map to a real `PUT`/`PATCH` on the new API), delete, `GET /aircraftreservationtypes/listitems`.
- **Scheduler render.** A created reservation appears in the correct aircraft lane at the correct
  time offset on the calendar view. Legacy is a hand-built grid (`rowHeight 30`, `cellWidth 8px/hr`,
  `hoursPerDay 24`, persisted `AircraftIdsToDisplayInScheduler`) — the new view need not copy the
  pixel grid; assert lane×time placement of the row.

## Notes

**60/40 framing.** ≥60% is the new reservations vertical (the `AircraftReservation` aggregate +
conflict domain rule + CRUD/paged API + the `/reservations` screen + the calendar view). The
≤40% tech-debt budget is filled by riders below that touch this journey's surface — they ride the
gate, not their own journey ([[feedback_journey_is_a_60_40_sprint]]).

**Seam hints (non-binding, for `/do-ship` sizing):**
- `AircraftReservation` aggregate (+ `AircraftReservationType` value/lookup) — owns
  `validateDuration()` (end>start, no empty range) + `conflictsWith()` overlap on the aggregate,
  NOT the schema. One aggregate = one task.
- Reservations resource — the endpoint cluster above (list/page/get/create/update/delete/types). One
  resource = one task (may split paged-list from CRUD if it overflows the sizing gate).
- Conflict-detection query — the GiST range probe (`btree_gist` on `(aircraft_id, reservation_range)
  WHERE deleted_on IS NULL`, `V4__…sql:128`); a native-SQL register entry is needed (tenant-scoped
  table → `NativeSqlRegisterTest`). Every mutating controller method needs its own audit event
  (`ControllerAuditCoverageTest`) — create/update/delete each emit one.
- `/reservations` list+edit component, and the `/reservation-scheduler` calendar view — **the
  drag-move/resize interaction is the heaviest seam**; size it as its own task(s). If the calendar
  interaction overflows a clean worker, `/do-ship` re-plans it into sub-tasks (it does not become a
  follow-up — drive to goal with tasks).
- Migration: the mappers (`AircraftReservationMapper` + `AircraftReservationTypeMapper`) already
  exist + unit-pass in `migration-bundle/…/accounting/`; authored ≠ proven
  ([[verify_infra_is_run_not_just_authored]]). The done-bar needs a **real legacy→migrate→AlpenFlight
  round-trip** (fanout), not just synth — reservations reference Aircraft/Person/Location, so seed
  those legacy rows + verify fan-out keying ([[project_synth_bundle_doesnt_validate_producer_select]]).

**Candidate riders to fold into the ≤40% budget** (`/do-ship` confirms which fit this gate):
- **Docker disk leak / local self-verify** (`PostgresTestContainerLifecycle` pre-start sweep + raise
  the 60s readiness cap + a Stop hook) — the backend slice's ITs hit exactly this harness; operator-
  approved at J-4 retro. [[project_docker_disk_leak_orphaned_testcontainers]]
- **J-1 aircraft real-idp spec flake** (`aircraft-migration-parity.spec.ts` retry-isolation + S-163
  45s timeout) — J-5 builds on aircraft and its proof shares the clean-seed job; a stray aircraft
  flake would red J-5's gate. Fix it here (retry-idempotent create + diagnose the slow edit path).
- **Proof-gallery per-journey re-arch — FIRST slice only** (substantial pure tech-debt, split across
  2-3 journeys' budgets): e.g. make the gallery generator **key by `journey`** (the sidecars already
  carry the field) without yet retiring the per-proof-type sub-paths. [[feedback_proof_gallery_per_journey_one_bookmark]]
- Minor: e2e prettier/tsc-strict normalization on touched specs only.

**Assumptions made:**
1. **S-068 + S-069 stay one journey** (roadmap assumption #5: list + calendar are two views of one
   reservation route family). The spec's load-bearing proof is the conflict rejection + CRUD; the
   calendar asserts lane×time placement. The drag-drop scheduler is the heaviest seam — if it can't
   fit J-5's gate cleanly, `/do-ship` re-plans its tasks rather than spinning a J-5b (a re-carve is
   only warranted if the *whole* journey proves oversized at ship time).
2. `X-HTTP-Method-Override: PUT` is a legacy transport quirk; the new API exposes a real `PUT`/`PATCH`
   and the spec drives that — wire-shape parity is the request/response body, not the verb tunneling.
