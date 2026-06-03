---
id: J-2
title: Flight list + edit forms (airfield hot path)
epic: E-07
status: todo
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

## Assumptions made

1. `/airmovements` is the **same** SPA screen parameterized by the MOTOR filter (S-064's whole
   point — no legacy-style duplication), so it stays within this one-screen journey rather than
   splitting into its own.
2. The Flight + FlightCrew migration mappers exist from J-0b authoring but are **unproven
   end-to-end** ([[verify_infra_is_run_not_just_authored]]) — sized build/verify, expecting a
   real-export producer-SELECT catch like J-1 T-16.
3. S-061's `flight_date`/`locked_at` gate wording is provisional; the `CreatedOn` legacy behavior
   is the ship-time oracle question, not a carve-time guess.
4. The 412/optimistic-concurrency path is a **new-stack affordance** (legacy is last-write-wins),
   so its spec assertions prove new behavior, not legacy parity.
