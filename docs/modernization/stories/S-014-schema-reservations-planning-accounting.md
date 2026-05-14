---
id: S-014
title: V1__baseline part 3 — reservations / planning / accounting
epic: E-02
status: todo
depends_on: [S-013]
acceptance:
  - Tables defined: `aircraft_reservation`, `aircraft_reservation_type`, `planning_day`, `planning_day_assignment`, `accounting_rule_filter`, `accounting_rule_filter_type`, `accounting_unit_type`, `delivery`, `delivery_item`, `delivery_creation_test`, `delivery_creation_test_item`.
  - All tables carry `operating_club_id` (tenant scope per S-011).
  - `delivery.process_state_id` enum (Prepared/Booked/Error) with `Booked` enforced terminal in code (DB has no transition logic but check constraint can validate state values).
  - `accounting_rule_filter` carries `filter_config` as `jsonb` (per ADR 0002 — natural home for semi-structured rule config).
  - `delivery_creation_test` references a `flight_id` + a JSON snapshot of expected delivery items (regression harness payload).
estimate: M
adr_refs: [0002, 0003, 0008]
parity_test: none
---

## Context
Final chunk of V1__baseline. Sets the table for E-08 + E-09.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] AircraftReservation + ReservationType.
- [ ] PlanningDay + PlanningDayAssignment (FK to person for instructor / tow pilot / flight operator).
- [ ] AccountingRuleFilter — model the predicates (aircraft type / immat list / locations / flight type codes / time ranges) as columns where natural, JSON for the bag of options. Reference S-010 + RulesEngine code paths.
- [ ] AccountingUnitType + AccountingRuleFilterType lookups.
- [ ] Delivery + DeliveryItem (with `article_id` + `quantity` + `unit_price` + `total`).
- [ ] DeliveryCreationTest + DeliveryCreationTestItem (the regression harness from SERVER.md §3).

## Notes
Schema for `AccountingRuleFilter` is non-trivial — the legacy schema mixes per-rule-type columns into one table. The reshape decision (keep one wide table vs. per-type tables vs. JSON `filter_config`) is part of this story; the recommendation is **one base table + jsonb filter_config + filter_type_id discriminator**, mirroring how the rules engine instantiates `Rule` objects from a base `AccountingRuleFilter` row at runtime.
