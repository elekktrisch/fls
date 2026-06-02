---
id: J-1
title: Aircraft register
epic: E-06
status: todo
journey0: false
carved: true
depends_on: [J-0, J-0b]
rolls_up: [S-161, S-162, S-163, S-164]
acceptance:
  - Club admin opens /aircrafts and sees their club's aircraft, sorted by immatriculation, showing immatriculation + competition sign + aircraft type. [happy]
  - Add a new aircraft via the form (immatriculation, type, manufacturer/model, seats, club-vs-private owner, owner club/person, homebase, spot link); it appears in the list. [happy]
  - Edit an existing aircraft; changes persist and re-render. [happy]
  - Delete an aircraft; it leaves the list. [happy]
  - A caller whose club ≠ the aircraft's managing club is denied edit/delete (403) — `AircraftAccess.canEdit` parity (managingClubId == callerClubId). [key-error]
  - `latestCounter` is present in the Aircraft detail for a manager caller and redacted for a non-manager caller (S-164). [edge]
  - Real legacy Aircraft data migrates into AlpenFlight and the migrated aircraft renders in the owning club's /aircrafts list under its immatriculation — full legacy→migrate→Keycloak→UI chain green (reuses J-0c's harness). [happy, real-data]
screen: /aircrafts (feature folder masterdata/aircrafts/) — replacing legacy flsweb/src/masterdata/aircrafts/
headless_pulled_in: per-entity AIRCRAFT migration mapper (+ AircraftAircraftState history, AircraftOperatingCounter) — pulled in by this screen; AircraftType/AircraftState reference data already have controllers
migration: Aircraft (+ aircraft_aircraft_state, aircraft_operating_counter)
parity_test: alpenflight/web/e2e/tests/masterdata/aircrafts.spec.ts
adr_refs: [0008, 0022]
---

## Context

The aircraft register is the masterdata screen every flight/reservation/accounting
journey depends on (J-2/J-5/J-8 list `depends_on: J-1`). A club admin manages the
club's fleet — immatriculation, type, ownership, homebase. It's the first journey
after the migration-fan-out trio, so it runs the **full real legacy→migrate→Keycloak
→UI chain** as its done bar (mapper- *and* domain-touching — retro Q1).

## Spec must assert

Grounded in legacy `flsweb/src/masterdata/aircrafts/` (list `aircrafts-table.html`,
form `aircraft-form-fields.html`, modal `add-aircraft.html`) and the existing backend
`AircraftsController` (`/api/v1/aircraft`: GET list/`/picker`/`/{id}`, POST, PUT/{id},
DELETE/{id}, POST `/{id}/transfer-ownership`) + `AircraftAccess`:

- **List + CRUD** (happy): list sorted by immatriculation; create via form; edit; delete.
  Fields from `aircraft-form-fields.html`: immatriculation, competition sign, aircraft
  type, manufacturer, model, seats, owner type (club vs private), owner club/person,
  homebase, spot link.
- **Tenant edit-isolation** (key-error): `canEdit` admits only the **managing club**
  (`AircraftAccess.canMutate`: callerClubId == resolveManagingClubId) → cross-club
  edit/delete is 403. Mirrors J-0's cross-tenant 404/403 pattern.
- **latestCounter redaction** (edge, S-164): present in `AircraftDetail` for a manager
  caller, redacted (null) for a non-manager.
- **Real-data parity** (happy): a migrated legacy Aircraft renders in the owning club's
  list under its immatriculation, post-migration.

**Parity to confirm at ship time (do NOT guess — dispatch `legacy-oracle`):**
- **S-163** — extend `canEdit` to admit the person matching `aircraft_owner_person_id`.
  The current code comment (`AircraftAccess` ~line 37) says owner-person is **intentionally
  NOT** admitted, but S-163 (todo) + the `_ORDER` † note ("backend already implemented")
  disagree. Resolve against legacy `AircraftService`/access behavior before asserting.
- **S-162** — sysadmin variant `/api/v1/admin/aircraft` with explicit `managingClubId`:
  confirm whether it exists + assert parity, or descope to its own admin journey.
- **S-161** — cross-club aircraft visibility (charter case): what the managing club vs a
  using club sees. Confirm the legacy read-visibility rule before asserting list contents.

## Notes

- **Backend largely exists** — `AircraftsController` + `AircraftAccess` + `Aircraft`
  aggregate + DTOs are implemented; S-162/163/164 are flagged "backend done, re-assert
  parity" in `_ORDER` (but see the S-163 contradiction above). **Net-new is the SPA
  `/aircrafts` screen** + the AIRCRAFT migration wiring + the parity assertions.
- **Done bar = real chain** (retro Q1: mapper + domain touching). Reuse J-0c's
  legacy→migrate→Keycloak→AlpenFlight harness + per-club video; don't settle for
  synthetic-IT green.
- **Boyscout riders to fold into the task list** (from `_BOYSCOUT.md` — they ride this
  journey, not separate stories):
  - **Gallery ✅-ordering fix + guard test** — J-1's gate regenerates the canonical
    gallery, so fixing `generate-gallery.mjs`'s roadmap regex here restores J-0's
    ordering *with visible proof* (the operator's standing gallery complaint).
  - **Migrated-admin profile completion** — J-1's real chain logs a migrated admin into
    the UI, so production `provisionClubAdminIdentity` must set firstName/lastName from
    the legacy Person (and the test-side fixup is removed). Without it the migrated admin
    hits Keycloak `VERIFY_PROFILE` — the journey can't reach /aircrafts.
  - **Binding-completeness guard** — assert the AIRCRAFT mapper's `foreignKeys()` targets
    all have `MapperLegacyBindings` entries (the J-0c T-21 "authored-but-unwired" class).
  - **Keycloak fail-closed contract doc** + **Docker Hub image-pull retry** — fold if a
    task touches that surface; else leave for a later gated journey.
- **Seam hints for `/do-ship`** (non-binding, one seam each): AIRCRAFT migration mapper +
  legacy seed · `/aircrafts` list component + store · aircraft add/edit form component ·
  Playwright spec stub · spec thicken (real parity assertions) · each boyscout rider above.
- **Migration shape:** Aircraft fans out per-club like Location (J-0b infra); the migrated
  admin must own the migrated aircraft's managing club to pass the edit-isolation assertion.

## Assumptions made

- The AIRCRAFT migration mapper exists from J-0b's per-entity authoring but is **unproven
  end-to-end** ([[verify-infra-is-run-not-just-authored]]) — sized as build/verify, not wire.
- S-162/163/164's "backend done" is taken as provisional; the S-163 contradiction is a
  ship-time oracle question, not a carve-time guess.
