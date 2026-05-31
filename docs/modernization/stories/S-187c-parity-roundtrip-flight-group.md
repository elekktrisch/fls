---
id: S-187c
title: Parity round-trip — FLIGHT group + composite location + producer-drop reconciliation + FK-orphan walk
epic: E-02
status: todo
depends_on: [S-187a, S-185, S-187b]
integration_base: integration/migration
origin: scope-split
origin_story: S-187a
refined: false
acceptance:
  - **FLIGHT-group bindings + fixtures** for LOCATION, START_TYPE, FLIGHT_TYPE, AIRCRAFT, AIRCRAFT_AIRCRAFT_STATE, AIRCRAFT_OPERATING_COUNTER, FLIGHT, FLIGHT_CREW, including ≥ 1 `Flights.AirStateId=5` row per Club.
  - **Composite `legacy_id_map_location`** special-case FK walker: `Flight.{start,ldg}_location_id` via `(legacy_guid, operating_club_id)`; `Aircraft.homebase_id` via `(legacy_guid, managing_club_id)` with deterministic lowest-UUID-v7 fallback. Assert `count == Σ_legacyLocation |referencing-Club set|`.
  - **Producer-drop reconciliation wired live.** `ProducerHarness` collects `ProducerDropWarning`s (AIRCRAFT_NO_MANAGING_CLUB from the managing-club cascade); `ParityDiffEngine` folds them via `ProducerDropReconciliation` into the per-(Club, table) equality.
  - **Flight tow-chain two-pass** for `Flight.tow_flight_id`; AirStateId translation (`FlightPlanOpen` → `flight_plan_opened_on`, others produce no air-state delta — not a row drop).
  - **FK-orphan walk executed** from `ForeignKeyOrphanPlan`; `summary.json.fkOrphans` measured (flips from null) and asserted `== 0` in the happy round-trip.
  - Round-trip green in CI's parity job.
estimate: L
adr_refs: [0008, 0019, 0022, 0023]
parity_test: alpenflight/migration-bundle/src/parity/java/ch/alpenflight/migration/bundle/parity/ParityOracleHarnessTest.java
---

## Context

Scope-split from [S-187a](S-187a-parity-harness-remaining-mappers-and-gates.md) (machinery built + unit-tested there). Extends the live round-trip to the FLIGHT group and wires the producer-drop reconciliation + FK-orphan walk + composite-location resolver against the FLSTest MSSQL container (CI parity job). See S-187a's refinement block for the composite-location key shape and the lowest-UUID-v7 fallback rationale.
