---
id: S-185
title: Migration-bundle — flight sub-package mappers
epic: E-02
status: todo
depends_on: [S-183]
integration_base: integration/migration
origin: scope-split
origin_story: S-183
refined: false
acceptance:
  - All `EntityType` members in `Group.FLIGHT` ship a concrete `Mapper` under `ch.alpenflight.migration.bundle.flight.*` — `Location`, `StartType`, `FlightType`, `Aircraft`, `AircraftAircraftState`, `AircraftOperatingCounter`, `Flight`, `FlightCrew`.
  - Each mapper implements bidirectional `writeNdjson` + `readEntity` per the contract.
  - Each mapper declares FKs via `foreignKeys()`. Cross-tenant FKs: `Aircraft.ManagingClubId`, `Location.HomeClubId` declared as tenant-bypass.
  - `Flight` mapper translates legacy `AirStateId == FlightPlanOpen` → `t_flight.flight_plan_opened_on` timestamp; all other legacy air-state values are dropped (per V13 + ADR 0022 D2; pinned in S-183 refinement).
  - Each mapper passes the `AbstractMapperContractTest` suite.
estimate: M
adr_refs: [0002, 0003, 0008, 0019, 0022]
---

## Context

Scope-split from [S-183](S-183-migration-bundle-mappers-and-parity-oracle.md). Flight-group mappers including the highest-row entity (`FlightCrew`, ~25M rows at customer scale per ADR 0019). `FlightCrew` is the JMH-benched mapper (S-188) — its allocation discipline lands here.

## Cross-story contracts

- **Consumes:** S-183's scaffolding; S-184's `User` mapper for `FlightCrew.PilotPersonId` resolution via per-bundle Person sub-map.
- **Produces:** Flight-group `Mapper`s consumed by S-141 + S-187 + S-188.

## Notes

- `Flight.AirStateId` legacy → new translation per V13: only `FlightPlanOpen` carries; other states dropped (computed not stored per ADR 0022 D2).
- `FlightCrew` is the perf-critical mapper; readers should review with allocation-budget lens (per-row Jackson + JDBC inherent only).
