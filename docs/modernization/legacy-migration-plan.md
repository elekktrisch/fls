# Legacy database migration plan

Single source of truth for **every legacy SQL Server table** and what happens to it in the new stack. One row per table. Exhaustive over the final-state legacy schema (initial EF migration + all `DBUpdate_v*.sql` deltas applied in order — 44 tables).

This file is maintained by `/modernize-refine` (Step 4.5). Each story that touches DB schema updates the rows it owns; the diff lands in the same PR as the story's refinement. The story body itself stays silent on per-table migration — readers come here.

## How to read

- **Legacy table** — name as it appears in the final-state legacy schema (post-DBUpdate). Cross-references: [`flsserver/src/FLS.Server.Data/Migrations/201501222055041_InitialCreate.cs`](../../flsserver/src/FLS.Server.Data/Migrations/201501222055041_InitialCreate.cs) for the baseline; [`flsserver/database/FLS/Updates/`](../../flsserver/database/FLS/Updates/) for the deltas.
- **Destination** — new-stack table name (in the V<N> Flyway migration that creates it) OR `(dropped)` OR `(folded into <table>)` OR `(replaced by <external>)`. `TBD` means no story has refined this row yet.
- **Semantics** — one of: `port-as-rows` · `port-as-schema-only` · `drop` · `fold-into-<table>` · `split-into-<tables>` · `replaced-by-<external-system>`. `TBD` until refined.
- **Owned by** — story ID that wrote / last updated this row. Past owners ride in **Notes** as `also touched by S-XXX`.
- **Notes** — cutover specifics, sacred-cow flags, anything an operator needs at migration time.

## Allowed semantics

| Value | Meaning |
|---|---|
| `port-as-rows` | Rows copied 1:1 (column-mapped) by the S-016 importer / S-141 ingest pipeline. |
| `port-as-schema-only` | Table exists in new schema but rows are not copied (e.g. recreated empty per tenant on first use). |
| `drop` | No destination. Legacy rows are not read; the table doesn't exist in the new stack. |
| `fold-into-<table>` | Row contents merged into another aggregate's table (legacy junction → parent's column array, etc.). |
| `split-into-<tables>` | Single legacy table decomposed into N new tables (e.g. inheritance flattened). |
| `replaced-by-<external-system>` | Legacy responsibility moved to a non-DB system (Keycloak, OGN, Proffix). |

If a story needs a semantics value not in this list, add it via refine's Step 3.5 grill and update this header before stamping the row.

## Final-state legacy tables

> **Bootstrap pending.** The row list below is **not yet populated**. The authoritative source is a live-DB extract via [`alpenflight/database/extract/`](../../alpenflight/database/extract/) (`tables.json` output) — script / entity parsing was rejected because the legacy production schema is driven by `DBUpdate_v*.sql` deltas applied on top of the EF baseline, and `INFORMATION_SCHEMA` against the running DB is the only authoritative view.
>
> **To bootstrap:**
>
> 1. On a machine with the legacy SQL Server accessible, run the extractor per its [README](../../alpenflight/database/extract/README.md). Capture `raw/tables.json`.
> 2. For every `table_name` in `tables.json` (skipping system schemas), append a row below with `Destination=TBD`, `Semantics=TBD`, `Owned by=TBD`, `Notes` left blank (or one line of context if the column-shape from `columns.json` suggests it).
> 3. Sort alphabetically. Commit with subject `legacy-migration-plan: bootstrap from live-DB extract <YYYY-MM-DD>`.
> 4. From this point forward, refine's Step 4.5 updates rows in place as stories ship; no row is ever added by refine — the bootstrap is exhaustive.

| Legacy table | Destination | Semantics | Owned by | Notes |
|---|---|---|---|---|
| _(rows populated from live-DB extract — see bootstrap note above)_ |  |  |  |  |

## Coverage check

A future story (or a one-shot script) should grep `_ORDER.md` against this file and assert every story that lists a `flsserver/database` path or names a legacy table in its acceptance has stamped the corresponding rows here. Until then, an operator's eyeball is the check — rows still showing `TBD` after their owning story has merged are bugs.
