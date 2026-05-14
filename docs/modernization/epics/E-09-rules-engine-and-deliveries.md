---
id: E-09
title: Accounting rules engine & delivery pipeline parity
status: todo
adr_refs: [0005, 0008]
---

## Goal
Port the **sacred cow**: `DeliveryItemRulesEngine` (the decrement-loop pipeline that iteratively consumes `ActiveFlightTime`, applies `FlightTime`/`EngineTime`/`InstructorFee`/`LandingTax`/etc. rules, and emits `DeliveryItem`s with recursion through `TowFlightId`), the `Delivery` entity and its `Prepared → Booked` transitions, the `DeliveryCreationTest` regression harness, the `AccountingRuleFilter` CRUD that drives all of it, and the Proffix-compatible API surface (`/api/v1/deliveries/*`).

This epic is the highest-risk story in the whole modernization. It is sequenced **after** flight ops (E-07) — without a stable Flight model, rules-engine parity has nothing solid to validate against. The actual cutover gate is C11: a regression-test corpus of ≥ 1 case per production rule-filter combination per club passes bit-equivalently on old + new.

## Scope
- In: AccountingRuleFilter + AccountingRuleFilterType + AccountingUnitType + FlightCrewType CRUD; rules-engine Java port (IgnoreFlightRulesEngine → RecipientRulesEngine → DeliveryItemRulesEngine including FlightTime + EngineTime decrement loops, InstructorFee, AdditionalFuelFee, StartTax, LandingTax, NoLandingTax, VsfFee, plus recursion into TowFlightId); Delivery + DeliveryItem entities with Prepared/Booked/Error states; DeliveryCreationTest entity + UI + run-on-demand endpoint; `generateExampleDelivery(flightId)` dry-run endpoint; Proffix-compat API verification.
- Out: the *scheduled* DeliveryCreationJob (lives in E-10 — it just invokes the engine from this epic); DeliveryMailExportJob (also E-10); rules-engine corpus expansion (E-13).

## Stories
- [ ] S-072 — AccountingRuleFilter + filter-type CRUD
- [ ] S-073 — Rules-engine port: IgnoreFlight + Recipient stages
- [ ] S-074 — Rules-engine port: FlightTime decrement loop
- [ ] S-075 — Rules-engine port: EngineTime decrement loop
- [ ] S-076 — Rules-engine port: InstructorFee + AdditionalFuelFee + LandingTax + StartTax + NoLandingTax + VsfFee
- [ ] S-077 — Rules-engine port: glider→tow recursion via `TowFlightId`
- [ ] S-078 — Delivery + DeliveryItem CRUD + `Prepared → Booked` transitions
- [ ] S-079 — DeliveryCreationTest harness + `generateExampleDelivery(flightId)` endpoint
- [ ] S-080 — Proffix-compatible API surface verification (`/api/v1/deliveries/*`)

## Done when
- Every committed `DeliveryCreationTest` produces a `DeliveryItem` set bit-equivalent to the legacy engine when run against the same flight row (cell-by-cell diff, no tolerance).
- The `Delivery.Prepared → Booked` transition is terminal and rejected for any second attempt.
- A `PROFFIX-FLS-Sync` smoke pull against the new `/api/v1/deliveries/*` returns a payload schema-identical to today's (verified during S-080).
- Expansion of the corpus to "≥1 per production rule-filter combination per club" (C11) is tracked separately in S-105 in E-13 — this epic only ensures the *mechanism* is correct.
