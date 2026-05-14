---
id: S-010
title: Extract production-schema parity baseline
epic: E-02
status: todo
depends_on: []
acceptance:
  - A reference doc `next/database/legacy-baseline.md` lists every table, column (type + nullability), primary key, foreign key, index, and check constraint in the current production SQL Server schema.
  - The doc is generated from the live DB (or its dump), not hand-typed.
  - The doc is the explicit input for the new Postgres schema design in S-012..S-014.
estimate: M
adr_refs: [0002, 0003]
parity_test: none
---

## Context
Production schema is driven by `database/FLS/Updates/DBUpdate_v*.sql` (R7). To design the new Postgres schema with confidence, we need a structured baseline — not a pile of 11 SQL scripts.

## Acceptance criteria
See frontmatter.

## Tasks
- [ ] Restore a production-shape SQL Server dump locally (or use the FLSTest fixture as a stand-in if access is constrained).
- [ ] Run `INFORMATION_SCHEMA` queries to extract: tables, columns, types, nullability, defaults, PKs, FKs, unique constraints, check constraints, indexes.
- [ ] Format as a markdown doc grouped by domain (matching current-state §5 clusters).
- [ ] Cross-reference against `FLS.Server.Data/Mapping/` fluent mappings to confirm any EF-only constraints captured.
- [ ] Note any surprises (orphan tables, dead columns, inconsistent naming) — these inform the redesign in S-012..S-014.

## Notes
This is *documentation*, not a migration. We don't preserve the SQL Server schema in Postgres — C9 allows reshape. But the baseline is the spec the redesign must cover.
