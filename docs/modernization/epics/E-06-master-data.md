---
id: E-06
title: Master data & reference data parity
status: todo
adr_refs: [0005, 0008]
---

## Goal
Port the CRUD-shaped admin surface (clubs, aircraft, persons, users, locations, flight types, member states, person categories, articles, email templates, system data/logs, plus all reference-data dropdowns) onto the new stack. This epic is the first end-to-end vertical proof — by the time it's done, every cross-cutting concern (auth, tenancy, audit log, observability, OpenAPI codegen, NgRx Signal Store pattern, Reactive Forms, Tailwind UI kit) has been exercised on real domain code.

## Scope
- In: all entities classified as CRUD-shaped in [current-state §2 "Master data"](../01-current-state.md#master-data-crud-shaped-admin-surface); Persons + PersonClub many-to-many; User+Role assignment; bundled i18n translations replacing `/api/v1/translations`.
- Out: business-state entities (flights, reservations, planning days, deliveries — live in E-07/E-08/E-09).

## Stories
- [ ] S-047 — Reference-data domain (countries, unit types, member states, person categories, languages) — server + client
- [ ] S-048 — Clubs CRUD
- [ ] S-049 — Locations CRUD
- [ ] S-050 — Aircraft CRUD (+ aircraft types/states)
- [ ] S-051 — Persons CRUD + PersonClub many-to-many
- [ ] S-052 — Users CRUD + role assignment (maps Keycloak users ↔ FLS User row)
- [ ] S-053 — Flight types + flight cost balance types CRUD
- [ ] S-054 — Articles CRUD
- [ ] S-055 — Email templates CRUD
- [ ] S-056 — System data + system-logs view
- [ ] S-057 — Translations migrated to bundled JSON (closes C15; removes `/api/v1/translations`)

## Done when
- Every master-data CRUD spec in `e2e/tests/` (specs `12`, `13`, `26`, `27`, `28`, `29`, `30`, `31`) passes against the new stack with parity-equivalent screens.
- The `/api/v1/translations` endpoint is gone on the new server; the SPA loads translations from bundled JSON via the i18n library chosen in S-005.
- A cross-tenant leakage attempt against any master-data list endpoint returns empty / 404 (S-024 test guards this).
