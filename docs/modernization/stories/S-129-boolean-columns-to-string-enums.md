---
id: S-129
title: Migrate boolean schema columns to string-serialized enums (tri-state ready)
epic: E-02
status: todo
estimate: M
parity_test: none
depends_on: [S-012, S-013]
adr_refs: [0019]
refined: false
origin: rework
origin_story: S-013
origin_finding: Operator request mid-S-013 implementation — plan a future migration of all BOOLEAN columns in the new schema to VARCHAR + CHECK enum columns. Tri-state ready (e.g. `YES` / `NO` / `UNKNOWN` or domain-specific terms) for cases where the binary collapse loses information. S-013 shipped with booleans (consistency with already-merged S-012); this follow-up does the cross-cutting V<n> ALTER for both generations in one go.
---

## Context

S-013 review surfaced a forward-looking ask from the operator: replace all
`BOOLEAN` columns in V2 + V3 with `VARCHAR(N) NOT NULL CHECK (col IN
('VALUE_A','VALUE_B',['UNKNOWN'|...]))` columns so that a third state
("unknown" / "unspecified" / domain-specific) can be added later without
another schema change.

The shipped state at the close of S-013:

- V2 (`identity_and_reference`) — ~28 BOOLEAN columns across `club`,
  `person`, `person_club`, plus per-club flag booleans.
- V3 (`flights_aircraft_locations`) — ~30 BOOLEAN columns across `flight`,
  `aircraft`, `flight_type`, `flight_crew`, `aircraft_type`,
  `aircraft_state`, `location_type`, `location`,
  `flight_cost_balance_type`, `article`.

`aircraft_type` already carries 3 nullable booleans (`has_engine`,
`requires_towing_info`, `may_be_towing_aircraft`) which give implicit
tri-state via SQL NULL. That works but is inconsistent with the broader
ask — explicit string enums give a typed third option, surface in JSON as
strings rather than booleans, and remove the "NULL-is-meaningful" trap.

## Acceptance criteria

- A new V<n>__boolean_columns_to_string_enums.sql migration converts
  every domain BOOLEAN column in V2 + V3 to `VARCHAR(N) NOT NULL` with a
  CHECK constraint enforcing the value set. Migration is append-only;
  preserves data (`true` → primary code, `false` → opposite code).
- Per-column enum vocabularies decided per-column in the migration header
  (e.g. `is_solo_flight BOOLEAN` → `solo_flight_state VARCHAR(16) NOT NULL
  CHECK (solo_flight_state IN ('SOLO','DUAL'))`). NOT a single
  one-size-fits-all enum; the semantic of the column determines the codes.
- `aircraft_type.has_engine / requires_towing_info / may_be_towing_aircraft`
  go from nullable BOOLEAN to non-null VARCHAR with `UNKNOWN` as the
  third value (previously NULL).
- New ADR 0021 (or ADR 0019 amendment) "String-serialized enum columns
  over BOOLEAN" — rationale, the per-column vocabulary catalog,
  forward-compatible-additions policy. Authored ahead of this story; the
  story implements the ADR.
- `tenant-rules.yaml` updated where any boolean-flagged column was
  referenced (none currently in `pii_columns` / `sensitive_columns`, but
  re-validate).
- All S-012 + S-013 integration tests updated to expect VARCHAR + CHECK
  shape rather than BOOLEAN. Seed JSON oracle
  (`reference-seeds-canonical-uuids.json`) updated: each reference row's
  boolean fields become string-coded.
- `forbidden-migration-patterns.txt` extended with a regex catching new
  `BOOLEAN`-typed column declarations in `alpenflight/server/.../db/migration/`
  so the migration shape doesn't drift back.
- Flyway migration applies cleanly against a fresh Postgres in
  Testcontainers; all S-012 + S-013 baseline tests stay green.

## Tasks

- [ ] Author the ADR (0021 or 0019 amendment) defining per-column enum
      vocabularies. List every boolean column from V2 + V3 and assign
      its enum vocab. Operator sign-off required before implementation.
- [ ] Write `V<n>__boolean_columns_to_string_enums.sql`:
      1. ADD new VARCHAR columns alongside each BOOLEAN.
      2. UPDATE to backfill VARCHAR from BOOLEAN.
      3. DROP the BOOLEAN columns.
      4. ALTER VARCHAR to NOT NULL + CHECK.
- [ ] Update all existing migration / baseline tests to assert VARCHAR +
      CHECK shape.
- [ ] Update reference-seeds-canonical-uuids.json — boolean fields on
      each reference row become string codes.
- [ ] Update `forbidden-migration-patterns.txt` with the `BOOLEAN` guard.

## Notes

- This is a M-estimate story: ~60 columns × 4 steps each × test updates,
  but each step is mechanical once the ADR fixes the vocabulary. Risk
  is mostly in coordination with any in-flight S-022 (JPA wiring) work
  that may need to adopt the enum shape at the entity layer.
- **NOT in scope:** Java enum types at the JPA layer (S-022); JSON
  serialization shape decisions (S-022 / S-023); OpenAPI schema
  reshape (S-035). This story owns the DB shape only.
- **Coordination:** S-022 (JPA wiring) should ideally land AFTER this
  story so it consumes the final VARCHAR-enum shape directly. If S-022
  ships first, S-129 must add an S-022 follow-up to flip
  `@Boolean` → `@Enumerated(EnumType.STRING)` per column.
- **Operator confirmation 2026-05-16:** Approach A chosen — "New story,
  propose ADR amendment". S-013 shipped with booleans; this story
  handles the cross-cutting V<n> ALTER in one go.

## Absorbed from S-013 rework triage 2026-05-16

- **aircraft_operating_counter.at_date_time CHECK uses non-IMMUTABLE `now()`** —
  `alpenflight/server/src/main/resources/db/migration/V3__flights_aircraft_locations.sql:623-624`.
  Postgres permits `now()` in CHECK at table-create but only evaluates at row
  INSERT/UPDATE — the constraint is not query-plan-time enforced, and future
  Postgres majors may warn or treat the expression as non-deterministic.
  Address by replacing with a trigger-based enforcement OR moving the bound
  check to the service layer (S-022) and removing the CHECK. Bundle with this
  story's V<n> migration since it's already touching schema.
