---
id: S-161
title: Cross-club aircraft usage visibility (charter case — what does the managing club see?)
epic: E-07
status: todo
estimate: M
depends_on: [S-058, S-064]
origin: rework-meta
origin_story: S-058
kind: deferred-feature
adr_refs: [0008, 0018, 0022]
parity_test: none
refined: false
---

## Context

S-058 makes `Aircraft` cross-tenant: a Club B Flight may reference a Club A
aircraft (the charter case — small glider clubs flying tow planes owned by
other clubs). The Flight aggregate stays `@TenantId(operating_club_id)`, so
the operating club sees the flight in its books. The managing club of the
chartered aircraft does **not** see Club B's flights on its own aircraft.

This may be the right answer (each club keeps its own books; reconciliation
is out-of-band). It may also be operationally insufficient — the managing
club's airframe-counter advances and engine-time totals are spread across
chartering clubs' flights, and reconciliation today requires manual export.

Surfaces the policy question the S-058 grilling pass closed by deferring.

## Shape options (decide at refine time)

1. **Defer** (S-058 day-1 default) — Club B reads their own books; managing
   club reconciles out-of-band. Cheapest; correct for clubs that don't
   actually charter cross-club. **The story exists to surface the decision,
   not to pre-commit to a shape.**
2. **Loosen Flight `@TenantId`** — managing-club readers also see flights
   referencing their aircraft. Structurally breaks ADR 0008's per-flight
   tenant model; high blast radius.
3. **Copy-on-flight-close** — emit a derived `AircraftUsageRecord` in the
   managing club's books when a Flight referencing their aircraft transitions
   to `closed`. Read-only projection; cleanly tenant-scoped to the managing
   club. The likely shape if (1) proves insufficient.

## Acceptance criteria (placeholder until refined)

- Decide and document the shape (DEFER vs. PROJECTION vs. LOOSEN-TENANT).
- If PROJECTION: `AircraftUsageRecord` aggregate + projection trigger from
  Flight close (S-064) + a read endpoint on the managing-club side.
- If DEFER: document the rationale on this story, mark it `status: done`,
  no code change.

## Notes

- Probable refine home is the delivery / accounting epic (E-09), not E-07 —
  the question is "what does the managing club need to see to invoice and
  account for chartered usage?" Reclassify on refine if appropriate.
- Engine-time deltas on Flight already record per-flight counter advances
  (`flight.engine_*_operating_counter_in_seconds`). The data exists; the
  question is whether the managing club gets a tenant-scoped view of it.
