---
id: S-069
title: Reservation scheduler (calendar/timeline view)
epic: E-08
status: todo
depends_on: [S-068]
acceptance:
  - Calendar view (aircraft × time slot grid) at `/reservation-scheduler`.
  - Inline create-from-grid (click empty slot → new reservation modal).
  - Drag-to-resize / drag-to-move existing reservations (parity with legacy UX).
  - Spec `11-reservation-scheduler.spec.ts` passes.
estimate: L
adr_refs: [0005]
parity_test: tests/reservations/11-reservation-scheduler.spec.ts
---

## Context
The calendar/scheduler is real UI work — drag-drop interactions. Sizable but well-scoped.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Pick a calendar library (FullCalendar's Angular wrapper, or build with Tailwind + CDK drag-drop).
- [ ] Implement the grid view.
- [ ] Wire create/move/resize to backend.
- [ ] Conflict feedback (server returns 409; UI shows ghost).
- [ ] Spec verification.

## Notes
L because of the drag-drop UX. Tasks split it: data fetching, grid render, interaction, conflict UX.
