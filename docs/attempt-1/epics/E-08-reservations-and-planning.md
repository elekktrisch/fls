---
id: E-08
title: Reservations & planning days
status: todo
adr_refs: [0005, 0008]
---

## Goal
Port reservation CRUD, the reservation-scheduler calendar view, planning-day CRUD, and the planning-setup wizard. Smaller surface than flight ops but uses the same patterns; serves as a second vertical-slice validation of the architecture.

## Scope
- In: AircraftReservation entity + types; reservation scheduler calendar; PlanningDay entity + assigned crew references; planning-setup wizard.
- Out: planning-day notification email (E-10).

## Stories
- [ ] S-068 — AircraftReservation CRUD + validation
- [ ] S-069 — Reservation scheduler (calendar/timeline view)
- [ ] S-070 — PlanningDay CRUD + per-day reservation listing
- [ ] S-071 — Planning-setup wizard

## Done when
- Specs `10` `11` `14` `15` pass against the new stack with parity-equivalent screens.
- Reservation creation respects aircraft availability constraints (conflicting-reservation rejection path tested — depth coverage S-101 in E-13 picks this up).
