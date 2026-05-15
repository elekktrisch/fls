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
refined: true
refined_at: 2026-05-15
refined_specialists: [requirements-engineer, solution-architect, security-engineer, qa-engineer, performance-engineer]
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

**Important context (from refinement):** the EF migrations under `flsserver/src/FLS.Server.Data/Migrations/` contain only the `201501222055041_InitialCreate` baseline from 2015 — *all* schema evolution since then lives in `database/FLS/Updates/DBUpdate_v*.sql`. The EF migration tree is therefore NOT a useful diff source; the prod-applied DDL (extracted via `INFORMATION_SCHEMA` + `sys.*`) is the authoritative source. Cross-reference against `FLS.Server.Data/Mapping/*.cs` is still required for EF-only constraints (`HasMaxLength`, `IsRequired`, fluent-declared indexes).

**Re-refined 2026-05-15 with production-scale information.** Prod carries **hundreds of thousands of `Flight` rows** + **hundreds of thousands of audit-log rows** (split across `AuditLogs` and `AuditLogDetails`; the detail table is the actual PII container via `OriginalValue`/`NewValue` `nvarchar(max)` columns — `DBUpdate_v1.8.2.sql:35-74`). At this scale, the doc must carry concrete sizing data to be useful for S-013 (index design) and S-016 (migration window). Schema-only sections (§0-§2, §5 column lists, §6-§14) remain valid against any source; scale-bearing sections (§3.5, per-table sizing footers, audit-log breakdown) require prod-derived data.

<!-- modernize-refine: start -->

## Design notes

### Artifact layout (scale-bearing raw outputs promoted)

```
next/database/
├── legacy-baseline.md                # PRIMARY deliverable — single doc, multi-H2
└── extract/
    ├── README.md                     # runbook including `--allow-prod` checklist
    ├── extract-legacy-schema.{sh,py}
    ├── schema.sql                    # metadata queries (sectioned)
    ├── storage.sql                   # NEW — sys.dm_db_partition_stats + sp_spaceused
    ├── index-physical.sql            # NEW — sys.dm_db_index_physical_stats(... LIMITED)
    ├── cardinality.sql               # NEW — APPROX_COUNT_DISTINCT per indexed column
    ├── usage-stats.sql               # NEW — sys.dm_db_index_usage_stats (drop-candidate indexes)
    ├── ef-mapping-scan.py
    ├── render.py
    ├── verify.sh
    ├── verify/*.sh                   # ~20 verifiers (Test plan)
    ├── excluded-tables.txt
    ├── .env.example
    ├── .gitignore
    ├── out/manifest.txt              # source, DB version, commit SHA, snapshot date, row totals
    └── raw/                          # gitignored except listed files
        ├── tables.json
        ├── columns.json
        ├── pks.json
        ├── fks.json
        ├── uniques.json
        ├── checks.json
        ├── defaults.json
        ├── indexes.json
        ├── views.json
        ├── triggers.json
        ├── sequences.json
        ├── ef-mappings.json
        ├── row-counts.json           # PROMOTED — per-table rows + reserved/used pages
        ├── storage-stats.json        # NEW — data MB + index MB + unused MB per table
        ├── index-sizes.json          # NEW — per-index size + LIMITED-mode fragmentation
        ├── index-usage.json          # NEW — seeks/scans/lookups/updates (drop-candidate flags)
        ├── column-cardinality.json   # NEW — indexed columns only: APPROX_COUNT_DISTINCT
        └── audit-yearly.json         # NEW (conditional) — audit_log rows per YEAR(created_at)
```

**`column-cardinality.json` scope: indexed columns only.** Full-table NDV scans on 100K+ row tables cost minutes per column for data S-013 doesn't use on non-indexed columns. Enumerate via `sys.index_columns` JOIN `sys.columns` — one row per `(table, column)` appearing in any non-PK index plus FK columns.

**`raw/` gitignored except four files** consumed by CI verifiers: `row-counts.json`, `storage-stats.json`, `column-cardinality.json`, `index-sizes.json`. The rest stay local. `.gitignore` has an explicit allow-list for those names.

### Extraction approach — three query categories

Schema metadata, storage/cardinality metadata, distribution metadata — all metadata or aggregate; **never row data**.

1. **Schema metadata** (as before): `INFORMATION_SCHEMA.*` + `sys.tables`, `sys.columns`, `sys.foreign_keys`, `sys.check_constraints`, `sys.default_constraints`, `sys.indexes`, `sys.index_columns`, `sys.identity_columns`, `sys.triggers`, `sys.views`. Cheap, deterministic, sub-second.

2. **Storage / cardinality metadata** (new):
   - `sys.dm_db_partition_stats` — joined to `sys.tables` for per-table `row_count`, `reserved_page_count`, `used_page_count`. Multiply pages × 8 KB for MB.
   - `sys.dm_db_index_physical_stats(DB_ID(), NULL, NULL, NULL, 'LIMITED')` — per-index size + sampled fragmentation. **`'LIMITED'` is mandatory; `'DETAILED'` is forbidden.**
   - `sys.dm_db_index_usage_stats` — seeks/scans/lookups/updates since last SQL Server restart. Indexes with zero reads in months are drop-candidates; replicating dead indexes in Postgres costs storage + write-amp. **Caveat:** stats reset on SQL Server restart; record uptime (`sys.dm_os_sys_info.sqlserver_start_time`) alongside.
   - `sp_spaceused @objname = '<table>'` per top-50-by-row table for cross-check.

3. **Distribution metadata** (new):
   ```sql
   SELECT 'Flights' AS table_name, 'AircraftId' AS column_name,
          APPROX_COUNT_DISTINCT(AircraftId) AS approx_distinct,
          COUNT_BIG(*) AS sampled_rows
   FROM dbo.Flights WITH (READPAST);
   ```
   `APPROX_COUNT_DISTINCT` requires SQL Server 2019+ (compat level 150+). Fallback: `COUNT(DISTINCT col)` over `TABLESAMPLE (10 PERCENT)` with multiplier-correction, documented sample size. Both paths return scalars only.
   
   For blob-width profiling on tables > 50K rows:
   ```sql
   SELECT MAX(DATALENGTH(col)), AVG(DATALENGTH(col)), STDEV(DATALENGTH(col))
   FROM dbo.AuditLogDetails TABLESAMPLE SYSTEM (1 PERCENT) REPEATABLE (42);
   ```
   `DATALENGTH` returns a length; `TABLESAMPLE` bounds the page scan; `REPEATABLE` makes the run idempotent. **Forbid `SELECT col, DATALENGTH(col)`** — one keystroke from emitting the blob. Verifier greps the pattern.

**Boundary confirmation:** all three categories return scalars or per-object aggregates. Pre-merge `grep` (Security plan) blocks any `FROM dbo.<table>` not wrapped in `COUNT|APPROX_COUNT_DISTINCT|MAX|MIN|SUM|AVG|DATALENGTH`.

### Doc structure (revised — `## 3.5 Storage & cardinality` inserted)

```markdown
# FLS Legacy Schema Baseline

## 0. Provenance
  - DB engine + version (SELECT @@VERSION)
  - Extraction source: prod | staging | FLSTest
  - Extraction date (ISO 8601)
  - Extractor commit SHA + schema.sql checksum
  - Source DB last-applied DBUpdate_v*.sql version
  - SQL Server uptime (for `index-usage` stats interpretation)
  - Extraction wall-clock start / end
  - sys.columns CHECKSUM_AGG digest (snapshot-time mutation detection)
  - Distribution disclosure: "Internal modernization use only — section §3.5 reveals business scale."
  - Banner (conditional, when Source = FLSTest): "Regenerate against prod before S-013 or S-016."

## 1. How to read this doc
  - Notation legend (PK, FK, UQ, CK, IX, IDENT, NOT NULL, computed, partial)
  - Domain-cluster grouping rationale
  - Cross-references: §2 sacred cows → §5 tables → §10 EF drift

## 2. Sacred-cow call-outs
  H3 per name + "**Why sacred:**" + link to `../00-seed.md`:
  - Flight (single-entity glider/tow/motor, TowFlightId self-ref) — note: NO ClubId column; tenancy via AircraftId → Aircrafts.OwnerClubId
  - FlightCrew (composite unique on Person × Flight × CrewType)
  - FlightProcessState / FlightAirState (workflow state machine)
  - AccountingRuleFilter (rules-engine config — JSONB candidate in S-013)
  - Delivery / DeliveryItem (Prepared → Booked, terminal)
  - User / Person / PersonClub triad (multi-club humans vs. single-club logins)
  - AuditLogs + AuditLogDetails (the PII container — `OriginalValue`/`NewValue` nvarchar(max))

## 3. Tenant-scope catalog
  | Table | Tenant scope | Notes |
  Values: `ClubId-scoped` | `cross-tenant` | `system-global` | `reference-data` | `indirect-tenant` (e.g. Flights via AircraftId)
  Notes column captures cross-tenant references + indirect tenancy paths.
  Direct input to S-011 + S-024.

## 3.5 Storage & cardinality (production-scale data) — NEW

  ### Top 10 tables by row count
  | Table | Rows | Storage (MB) | Avg row size (bytes) | Notes |
  
  ### Top 10 tables by storage size
  (sort order differs — AuditLogDetails likely tops storage even if not rows, due to blob columns)
  
  ### Index size catalog (per-table, indexes sorted by size desc)
  | Table | Index | Pages | Size (MB) | Avg fragmentation % (LIMITED) | Filter | Unique | Reads (since restart) | Writes (since restart) | Drop candidate? |
  
  ### Column cardinality (indexed columns only)
  | Table.Column | Approx distinct | Sampled rows | Selectivity ratio | Method | Drives S-013 decision |
  Method: `APPROX_COUNT_DISTINCT` | `COUNT(DISTINCT) exact` | `sampled at N rows`
  
  ### Audit-log sizing breakdown
  - **`AuditLogs`**: total rows, total storage MB, avg row size, fan-out ratio to `AuditLogDetails`
  - **`AuditLogDetails`**: total rows, total storage MB, avg row size, largest blob columns and their max/avg `DATALENGTH` (sampled), per-year row count (if `created_at` indexed; otherwise `n/a (column not indexed; scan-only)`)
  - Postgres partitioning implication: row threshold rule of thumb is ~1M rows or ~10 GB. If above, S-013 should consider declarative range partitioning on `created_at`.
  
  ### Stale statistics (conditional)
  Present if any `sys.dm_db_stats_properties.last_updated` > 30 days. Surface "old system has been running on stale planner data" for S-108 perf-baseline context.
  
  ### Heap-only tables (conditional)
  Tables in `sys.indexes WHERE index_id = 0`. Heaps with fragmentation at 100K+ rows are perf footguns; S-013 converts to clustered/PK form in Postgres.
  
  ### FK fan-in count
  | Table | Incoming FKs | Notes |
  Sorted desc — gives S-016 a load-order hint (hub tables first).
  
  ### Cutover-window budget math (C6 ≤ 6 h)
  Throughput assumptions:
  - Read (`bcp queryout`): ~20–50 MB/s.
  - Write (`pg_restore -j 4` to Postgres 17): ~50–100 MB/s.
  - **Conservative end-to-end: 30 MB/s.** S-017 rehearsal calibrates.
  
  Per-table formula:
  - Migrate seconds: `storage_mb / 30`
  - Index rebuild seconds: `storage_mb / 50`
  - Audit-log PII redaction (if S-027 needs row-by-row): `~10K rows/sec single-threaded; parallelizable`
  
  | Table | Storage (MB) | Migrate s | Reindex s | Redact s | Subtotal s | % of 6h budget |
  | (top-10 enumerated) |
  | **Bulk-data subtotal** | | | | | **~30 s** | **~0.1%** |
  | **Remaining budget** | | | | | 21,570 s | 99.9% headroom |
  
  **Conclusion the doc must surface:** at this scale the migration window is NOT bounded by row volume; it is bounded by **verification + `ANALYZE` + smoke tests + manual sign-off**. S-016/S-017 should NOT over-engineer the bulk-copy step; they SHOULD invest the budget in automated post-migration parity checks (row counts per table, FK integrity, rules-engine output diff, sample-flight delivery regeneration).
  
  Caveats:
  - 30 MB/s is conservative; actual could be 2–3× higher or lower.
  - Numbers assume Postgres bulk-load tuning preconditions: `pg_restore -j 4`, `maintenance_work_mem ≥ 256 MB`, `synchronous_commit = off` during migration. **Preconditions for S-016, not S-010's scope to enforce.**
  - If audit-log grows to 1M+ rows by cutover, redact step becomes the dominant per-table cost (~100 s) but still trivial vs. budget.

## 4. PII catalog (FADP / GDPR — C5)
  Column-level table:
  | Table.Column | PII class | Row count weight | Encrypt-at-rest? | Export-on-DSAR? | Delete-on-erasure? | FADP basis |
  PII class: `direct-identifier` | `quasi-identifier` | `sensitive-special-category` | `authentication-artifact` | `financial` | `audit-payload`
  Row count weight: from §3.5 data — so S-013 sees encrypt-cost weighted.

  ### 4.1 Volume estimate
  - Direct identifiers in Person: ~N rows × ~15 PII columns = ~15·N data points.
  - AuditLogDetails serialized snapshots: ~M rows × variable blob (avg/max from §3.5).
  - The audit-log detail table is the largest PII container in the system.
  
  ### 4.2 At-rest encryption implications for S-013
  - At 100K rows, per-column pgcrypto on `Person.Email*` is hot-path-affecting on write but acceptable on read.
  - At 100K AuditLogDetails rows of blob payloads, envelope encryption with per-tenant DEK is preferable to per-column pgcrypto (pgcrypto on blob columns destroys query performance).
  - The catalog surfaces columns by row-count weight; S-013 picks per-column.
  
  ### 4.3 Right-to-erasure under FADP §8/§25 at scale
  A Person deletion cascades to Flight history + every AuditLogDetails row mentioning them. If audit blobs are opaque XML/JSON without per-Person index, erasure is a full-table scan + blob rewrite. Flagged for FADP-design follow-up story.

## 5. Tables (grouped by domain cluster, matching current-state §5)
  ### 5.1 Identity, auth, tenancy
  ### 5.2 Master data
  ### 5.3 Flight operations
  ### 5.4 Reservations & planning
  ### 5.5 Accounting & invoicing
  ### 5.6 Public flows
  ### 5.7 Email & scheduled jobs
  ### 5.8 Reference / dropdown data
  ### 5.9 Other / unclassified

  Per-table format — Markdown column table + prose bullets + **NEW** sizing footer:
  
  #### Table: `Flights`
  - **Tenant scope:** indirect via `AircraftId → Aircrafts.OwnerClubId` (no `ClubId` column — sacred-cow note for S-013: denormalize `ClubId` into `flight` in new schema)
  - **Row count (prod):** N
  - **Data MB / Index MB:** D / I
  - **Sample DDL source:** `DBUpdate_v*.sql:line-range`
  - **Columns:** Markdown table | # | Name | Type | Null | Default | IDENT | Computed | EF-only constraint | TZ semantics
  - **Primary key:** ...
  - **Foreign keys:** with cascade behavior (DB-level vs. app-level)
  - **Unique constraints:** ...
  - **Check constraints:** ...
  - **Default constraints:** ...
  - **Indexes:** with size MB + LIMITED-fragmentation % + reads/writes + drop-candidate flag
  - **Notes:** sacred-cow markers, parity quirks, dead-column suspects, indirect-tenancy callout

## 6. Views (zero confirmed in repo; verify against live DB)
## 7. Triggers (zero confirmed in repo; verify against live DB)
## 8. Stored procedures used by the app
## 9. IDENTITY columns / sequences
## 10. EF-mapping ↔ SQL DDL drift report
## 11. Naming inconsistencies (e.g. `PK_Persones` typo at `2 Alter Database.sql:1250`)
## 12. Dead / orphan tables flagged for review
## 13. Knowledge gaps
  - "Storage & cardinality regenerated against prod — BLOCKS: S-013, S-016, S-017, S-027, S-107 implement-phase entry" when Source = FLSTest
## 14. SQL Server → Postgres type-mapping cheatsheet
```

**Per-column timezone-semantics annotation** is load-bearing at this scale. Every `datetime2(7)` is naive; wrong mapping to `timestamp` vs. `timestamptz` shifts flight times by 1-2 hours silently (CEST vs. UTC), breaking the ≥2-day lock + ≥3-day delivery gates. Each timestamp column in §5 must carry a tag: `[UTC-by-convention]` (CreatedOn, ModifiedOn, audit) vs. `[local-naive @ start_location]` (flight times themselves).

### `MODE = 'LIMITED'` chosen for `sys.dm_db_index_physical_stats`

`DETAILED` is a full B-tree scan; on a 100K-row table with dozens of indexes, it's tens of seconds per index, competing for buffer-pool pages with the live app. `LIMITED` reads root-page metadata only — sub-second per index, accurate to ±10% on fragmentation. Adequate for documentation. `SAMPLED` (~1% pages) is a middle ground but doesn't improve over `LIMITED` for documentation use.

### Cutover-window math template

Doc §3.5 carries the formula and the per-table table; extraction script emits raw inputs (`storage-stats.json`); renderer computes seconds + % per table. The throughput constant (`30 MB/s`) is doc-time variable so the operator can tune after S-017 rehearsal. Single-line edit + renderer recompute. Ledger entry in §0 records the constant in effect.

### FLSTest fallback semantics — sharpened at scale

The earlier "FLSTest acceptable as stand-in" fallback is now **structurally OK, operationally weak**:

| Section | FLSTest first-pass validity |
|---|---|
| §0 Provenance | OK (records source as `FLSTest`) |
| §1 How to read | OK |
| §2 Sacred cows | OK (structural) |
| §3 Tenant-scope | OK |
| **§3.5 Storage & cardinality** | **PLACEHOLDER ONLY** — banner-marked at section top |
| §4 PII catalog | PARTIAL — columns identical; volume estimates placeholder |
| §5 Tables (column lists) | OK |
| **§5 per-table sizing footer** | **PLACEHOLDER** — banner-marked |
| §6-§11 | OK |
| **§12 Dead tables** | **UNRELIABLE** — FLSTest zero-row tables ≠ prod zero-row tables |
| §13 Knowledge gaps | OK |
| §14 Cheatsheet | OK |

`verify-manifest.sh` is nuanced: when `Source = FLSTest`, doc carries **two** distinct banners — one general at the top, one section-specific at the top of §3.5 ("Storage & cardinality is FLSTest-derived; regenerate against prod before S-013 or S-016 implementation").

**Operational sequencing:** S-010 closeable against FLSTest (tooling-complete, structure-complete). §3.5 + §5-sizing-footers must be regenerated against prod-shaped DB before EITHER S-013 OR S-016 enters implement phase. §13 carries an explicit "BLOCKS S-013 + S-016 implement-phase entry" annotation.

### Integration with other stories — scale flows downstream

**Inputs:** none (`depends_on: []`).

**Outputs / consumed by:**

| Story | Consumed | Scale change vs. earlier refinement |
|---|---|---|
| S-011 (tenant catalog) | §3 verbatim | Unchanged |
| S-012 (V1 baseline pt 1: identity/master) | §5 column lists | Unchanged |
| **S-013 (V1 baseline pt 2: flights/aircraft/persons)** | **§3.5 cardinality + storage MB**; §5 column lists; §10 drift | **NEW — composite-index column ordering decisions depend on per-column distinctness; without §3.5 data S-013 guesses and at 100K+ rows guessing wrong is a 10-50× p95 swing** |
| S-014 (V1 baseline pt 3: accounting/deliveries) | §5 + §3.5 audit sizing for `DeliveryItem` | Unchanged in shape |
| **S-016 (one-shot migration script)** | **§3.5 cutover-window math + `raw/row-counts.json` + `raw/storage-stats.json`** | **NEW — per-table seconds estimates tell S-016 which tables need parallel-load; row counts are S-017 post-migration verification floor** |
| S-017 / S-113 (rehearsals) | §3.5 row-counts as verification floor; rehearsal calibrates the 30 MB/s constant | NEW dependency |
| S-022 (`@TenantId` rollout) | §3 catalog | Unchanged |
| S-024 (cross-tenant leakage CI) | §3 catalog | Unchanged |
| **S-027 (audit-log infrastructure)** | **§3.5 audit-log sizing + §4 PII catalog** | **NEW — sizing tells S-027 whether row-by-row redaction is feasible in cutover window; per-year breakdown drives partitioning recommendation** |
| **S-107 (rules-engine corpus)** | **§3.5 `AccountingRuleFilter` per-club count + §5 Flight `ProcessState` distribution** | **NEW — drives corpus-generation effort estimate** |

### Alternatives considered (revised with scale)

- **Single doc with H2 sections (chosen) vs. split files.** Scale pushes doc to 5K-10K lines, 200-500 KB. Still single-doc-feasible; ToC at top + anchor links become non-optional. Split (`legacy-baseline.md` + `legacy-storage.md` + `legacy-pii.md`) considered — rejected because the redesign reader needs sizing + columns + drift on one screen. **See Open design questions.**
- **`sys.dm_db_index_physical_stats` mode: `LIMITED` (chosen) vs. `DETAILED` vs. `SAMPLED`.** `LIMITED` is metadata-only, sub-second per index, ±10% fragmentation accuracy — adequate. `DETAILED` is page-scan, minutes of read-IO, evicts buffer-pool pages. `SAMPLED` (~1% pages) doesn't improve on `LIMITED` for documentation.
- **`APPROX_COUNT_DISTINCT` (chosen) vs. `COUNT(DISTINCT col)` exact vs. `TABLESAMPLE` extrapolated.** Approximate is ~5% accurate from index histograms — no row pages read. Exact requires full scan = minutes/column at 100K+ rows. `TABLESAMPLE` extrapolation is the documented fallback for SQL Server < 2019 (compat level < 150).
- **Cardinality scope: indexed columns only (chosen) vs. all columns.** Full-column NDV scans on 100K+ row tables are minute-scale per column. S-013 only makes index decisions on indexed/FK columns; cardinality on `Person.Lastname` doesn't inform any S-013 decision.
- **Per-year audit row counts (chosen, conditional) vs. defer.** Index-range scan on `audit_log.created_at` is sub-second if indexed; table scan otherwise. Inspect `sys.indexes` first; defer with annotation if unindexed.
- **Aggregate-count queries against user tables** (`COUNT(*) FROM Flights`, `GROUP BY ProcessState`): gated by `--allow-aggregate-counts` flag with explicit operator confirmation. See Open design questions.
- **`raw/` gitignored except 4 metadata files (chosen) vs. all-committed vs. all-gitignored.** Mostly-gitignored prevents accidental row-data commits; explicit allow-list (`row-counts.json`, `storage-stats.json`, `column-cardinality.json`, `index-sizes.json`) lets CI verifiers run without DB access.
- **EF mapping cross-reference: regex (chosen) vs. Roslyn.** Unchanged.
- **Single application Markdown doc (chosen) vs. AsciiDoc vs. JSON+HTML.** Unchanged.

## Edge cases & hidden requirements

### Edge cases per AC (scale-changed)

**AC1 — every table, column, type, PK/FK/index/check**
- **Per-index physical statistics mandatory at scale** — size MB, LIMITED-mode fragmentation %, page count. `'DETAILED'` mode forbidden.
- **Per-index usage stats mandatory** — `sys.dm_db_index_usage_stats` shows seeks/scans/lookups/updates since SQL Server restart. Drop-candidate indexes (writes-only, never read) MUST be surfaced. **Caveat:** stats reset on restart; record uptime alongside.
- **`CLUSTERED` vs `NONCLUSTERED`:** at 100K+ rows, clustered-key choice affects physical row ordering. Annotate per table whether queries depend on physical ordering; S-013 needs this for `CLUSTER` advisory comments (Postgres has no clustered indexes — only one-shot `CLUSTER` reorderings).
- **`IGNORE_DUP_KEY = ON`** silent-skip-on-duplicate semantics: Postgres has no equivalent. Grep extraction for the clause — at scale, tolerated silent skips may be load-bearing data hygiene.
- **Confirmed empty in repo (zero across `flsserver/database/FLS/Updates/` + `flsserver/database/FLSTest/`):** views, triggers, stored procs, functions, sequences, UDTs. Verify against live DB; report.
- **`DEFAULT` constraints (`DF_*`):** carry business meaning (e.g. `IsDeleted = 0`). Must be included.
- **`UNIQUE` constraints separate from PKs:** load-bearing — `UNIQUE_FlightCrews_Person_Flight_FlightCrewType` (composite) enforces "same pilot can't be both pilot+instructor on same flight."
- **Filtered/partial indexes:** confirmed at `DBUpdate_v1.9.4.sql:27` (`WHERE [FlightCode] IS NOT NULL`). Postgres partial-index equivalent. Flag.
- **`PK_Persones` typo** (`2 Alter Database.sql:1250`). Catalog verbatim.

**AC2 — generated from live DB or dump**
- **Dump strategy hierarchy at scale:**
  1. schema-only `.bacpac` — fast; structurally identical to prod; no row data. Preferred.
  2. schema + statistics-only — adds histograms (`sys.stats` + `DBCC SHOW_STATISTICS`); same PII safety. **Recommended** so S-013 has planner-eye view.
  3. full-data `.bacpac` — multi-GB; PII-exposing; OUT of scope for S-010; needed only by S-017/S-113 rehearsal on isolated host.
- **`sys.dm_db_partition_stats.row_count` instantaneous, PII-free** — use exclusively for row counts. `SELECT COUNT(*) FROM Flights` at 100K+ rows is a scan; locks pages; may stall OGN ingestion. **CI grep blocks `COUNT(*) FROM` outside `INFORMATION_SCHEMA.`/`sys.`** unless gated by `--allow-aggregate-counts`.
- **Snapshot-time mutation gap widens at scale:** `.bacpac` export of multi-GB DB takes minutes; row counts at minute-0 vs. index sizes at minute-N may disagree if writes happen mid-export. `manifest.txt` records extraction `started` + `ended` timestamps; `verify.sh` flags if drift between metadata sources exceeds threshold.

**AC3 — input for S-012..S-014**
- **S-013's composite-index ordering decisions for top-5 tables (Flights, AuditLogs, AuditLogDetails, Reservations, FlightCrew) need numerical inputs from this story, not prose.** At 100K+ rows, getting `(club_id, flight_date DESC)` vs. `(flight_date DESC, club_id)` wrong is a 10-50× p95 swing on the dominant list-page query.
- **`Flights` has NO `ClubId` column** (confirmed by inspection: `2 Alter Database.sql:552-591`). Tenancy reaches `Flights` only via `Flights.AircraftId → Aircrafts.OwnerClubId`. The doc must call this out per-table and note the **reshape candidate** for S-013: denormalize `club_id` into `flight` in new schema. Without denormalization, every list query joins through `Aircrafts` at this scale.

### Hidden requirements (scale-driven additions / promotions)

- **PROMOTE to mandatory AC candidate:** row count + storage size (data MB + index MB) per table. Source: `sys.dm_db_partition_stats` + `sys.allocation_units` + `sp_spaceused`. Previously "ambiguous, recommend yes" — at this scale, load-bearing.
- **PROMOTE to mandatory:** column-cardinality / NDV for every indexed column. Source: `DBCC SHOW_STATISTICS('dbo.<table>', '<index_name>') WITH STAT_HEADER, DENSITY_VECTOR` (no row data; summary stats only). Single most useful input for S-013.
- **NEW H2:** `## 3.5 Storage & cardinality` with sub-sections (Top-N by rows, Top-N by storage, Index size catalog, Column cardinality, Audit-log sizing breakdown, Stale statistics, Heap-only tables, FK fan-in, Cutover-window budget math).
- **NEW sub-section:** **Audit-log sizing breakdown**. The operator's "100K audit-log rows" likely refers to `AuditLogs`; **`AuditLogDetails` is the actual storage giant** with `OriginalValue`/`NewValue` `nvarchar(max)` columns (confirmed at `DBUpdate_v1.8.2.sql:35-74`). The doc must size both tables; `AuditLogDetails` is almost certainly larger by storage:
  - `AuditLogs`: total rows, total storage MB, avg row size.
  - `AuditLogDetails`: total rows, total storage MB, avg row size, fan-out ratio (`COUNT(AuditLogDetails) / COUNT(AuditLogs)`).
  - Blob-width profile for `OldValue` / `NewValue`: `AVG(DATALENGTH(col))`, `MAX(DATALENGTH(col))`, `STDEV(DATALENGTH(col))` over `TABLESAMPLE SYSTEM (1 PERCENT)`.
  - Per-year rows from `audit_log.created_at` if indexed.
- **NEW:** stale-statistics flag list. `sys.dm_db_stats_properties.last_updated > 30 days`. At write-heavy 100K+ rows, stale stats mean SQL Server planner is wrong before migration; informs S-017 rehearsal's `UPDATE STATISTICS WITH FULLSCAN` pre-step.
- **NEW:** heap-only tables list (`sys.indexes WHERE index_id = 0`). `Flights` confirmed NOT a heap (`PK_Flights CLUSTERED`); other tables may be. Heap fragmentation at 100K+ rows is a footgun.
- **NEW:** FK fan-in count per table. Tables with high fan-in (`Persons`, `Clubs`, `Aircrafts`, `Flights`) are migration hubs; S-016 must insert these before dependents. Sorted desc gives S-016 a load-order hint.
- **NEW:** `Flights` state-distribution snapshot. `SELECT FlightState, COUNT(*) FROM Flights GROUP BY FlightState` — counts only, no row data. Gated by `--allow-aggregate-counts` flag. Tells S-013 hot states, S-016 ordering, S-107 corpus size.
- **NEW:** `AccountingRuleFilter` per-club count. `SELECT ClubId, COUNT(*) FROM AccountingRuleFilters GROUP BY ClubId`. Drives S-107 corpus-generation effort + S-013 JSONB-vs-relational decision.
- **NEW:** per-year row counts for `AuditLogs` + `Flights`. If S-013 considers Postgres declarative partitioning, partition boundaries need cardinality-over-time.
- **NEW:** large-blob column width profile. For every `nvarchar(max)` / `varbinary(max)` column on tables > 10K rows: `TABLESAMPLE`-bounded avg/max/stdev `DATALENGTH`. Drives S-016 batch-size budget — 100K rows × 10 B `Comment` migrates in seconds; same rows × 5 KB `Comment` is GB-scale.
- **`Flights` has no `ClubId`** — already covered in AC3 edge case above; doc surfaces as a concrete reshape candidate for S-013.
- **Additional system tables earlier refinement missed:** `dbo.SystemVersion` (`2 Alter Database.sql:1365`), `dbo.SystemData` (`:1307`), `dbo.SystemLogs` (`:1335` — NLog application log, distinct from `AuditLogs`). Catalog row counts; flag retention policy.
- **Doc lifecycle / re-extraction protocol:** snapshot timestamp + DB version + extractor commit SHA in §0; re-run probability non-zero (Data-Cleaning patches happen).
- **Source-of-truth precedence (decided):** prod DDL > SQL update scripts > EF mappings. EF migrations frozen at 2015 baseline.

### Scope clarifications

**Newly In (mandatory at scale):**
- Row count per table.
- Storage size per table (data MB, index MB, total MB).
- Per-index size MB + LIMITED-mode fragmentation + page count.
- Per-index usage stats (seeks/scans/lookups/updates) with server-uptime caveat.
- Cardinality / NDV / density for every indexed column.
- Top-N tables H2 (by rows; by storage).
- Audit-log sizing breakdown (both `AuditLogs` and `AuditLogDetails`).
- `Flights` state distribution count (gated).
- `AccountingRuleFilter` per-club count (gated).
- Heap-only tables flag.
- FK fan-in count.
- Stale-statistics flag.
- Large-blob avg/max width profile per `nvarchar(max)` on tables > 10K rows.
- Per-top-10-table cutover-window estimate.
- Per-column timezone-semantics annotation.

**Still Out:** any row data, any PII content, migration script (S-016), index re-design (S-013), encryption mechanism choice (S-013/S-027), Postgres partition boundaries (S-013).

**Newly ambiguous (operator decides — Open design questions):**
- Aggregate-count queries gated by `--allow-aggregate-counts` flag (recommend yes) vs. pre-computed-and-committed numbers (no live-DB aggregate access). Affects script + DENY-rule shape.
- Per-index fragmentation via `'SAMPLED'` mode (heavier than `'LIMITED'`) — recommend NO.
- `Persons` / `AuditLogDetails` DENY-at-table-level for the extractor role — operationally awkward but maximally safe.

### NFR call-outs

- **Performance (extraction script):** budget bumped from < 2 min to **< 5 min** because of cardinality + storage + sampling passes. > 10 min → investigate (likely `'DETAILED'` slipped in).
- **Security at scale:** "no row data" is now FADP-reportable risk on accidental SELECT. Read-only DB role is table-stakes; `verify-no-row-data-queries.sh` greps for `FROM <app_table>`. See Security plan.
- **Compliance:** PII catalog drives encryption-at-rest decisions in S-013. At 100K Person rows × ~10 PII columns × encryption overhead, PII catalog accuracy materially affects DB size and read p95.
- **Cutover window (NEW explicit requirement):** doc must derive per-table cutover estimate for top-10 tables. At this scale, bulk-data migration is ~30 seconds vs. 6h budget — the window is bounded by verification, not row volume. Surfacing this prevents S-016 from over-engineering bulk copy.

### Things NOT the right shape (re-evaluated at scale)

- **AC list still too narrow.** Earlier refinement flagged this for defaults/uniques/triggers. With scale info, missing AC is starker: **no AC mentions row counts, storage sizes, cardinality, or audit-log sizing.** Recommend adding **AC4:** *"The doc includes per-table row count, storage MB, per-index size MB, NDV for every indexed column, dedicated H2 sections for top-N by rows and storage, audit-log sizing breakdown for both `AuditLogs` and `AuditLogDetails`, and a per-top-10-table cutover-window estimate."* This is a concrete recommendation to the operator, not a silent fix (per skill rules: surface in Open design questions, don't auto-edit ACs).
- **"FLSTest acceptable as first-pass" needs tightening.** At this scale, FLSTest row counts are 3-4 orders of magnitude smaller than prod — meaningless for §3.5. Verifier split per Doc structure: schema sections pass against FLSTest; scale-bearing sections require Source = prod.
- **`legacy-baseline.md` page count** revised: 5K-10K lines, 200-500 KB. Still single-doc-feasible; ToC + anchors non-optional. See Open design questions for split-vs-single.

## Security plan

### Threat model (sharpened at scale)

- **PII row data exfiltration via copy-adapted query (HIGH, sharpened from MED).** At 100K+ Person rows + 100K+ audit rows, an accidental `SELECT TOP 100 * FROM AuditLogDetails` ships ~100K characters of serialized PII (names, emails, license numbers in `OriginalValue`/`NewValue` blobs). Persists in SSMS query history + DB plan cache + page file. Mitigations:
  - Extraction script SQL as constant strings at file top with `# WHITELIST: INFORMATION_SCHEMA.*, sys.*, sys.dm_db_*` header.
  - Pre-merge grep blocks `FROM <app_table>`.
  - Runbook explicitly forbids interactive SSMS against prod by anyone but the operator using the read-only role.
- **Live-DB load via `MODE = 'DETAILED'` (NEW MED).** Full-page scan competes with live OLTP. Mitigation: **`MODE = 'LIMITED'` exclusively**; `verify-no-detailed-mode.sh` fails build if found.
- **Audit-log metadata is itself sensitive (NEW HIGH).** Per-year row counts reveal user-activity patterns; size growth reveals business scale; per-table audit volume is an operational fingerprint. Mitigation: row counts + storage MB in internal `legacy-baseline.md` OK; **external publication requires operator approval** — recorded in `next/database/extract/README.md` distribution section.
- **`APPROX_COUNT_DISTINCT` on indexed columns (LOW, document).** Reads index histograms only. Safe at any scale. Document.
- **`MAX(DATALENGTH(col))` on large-blob columns (NEW LOW-MED).** Returns length (safe) but underlying SQL reads blob pages (load). Mitigation: bound cost with `TABLESAMPLE (1 PERCENT) REPEATABLE (42)`; **forbid `SELECT col, DATALENGTH(col)`** — one keystroke from emitting blob content. Verifier greps the pattern.
- **Accidental DML against prod (sharpened).** At 100K+ rows, stray `UPDATE` is a full restore + FADP breach. Read-only DB role table-stakes; `verify-role-readonly.sh` runs `SELECT HAS_PERMS_BY_NAME('dbo.Person', 'OBJECT', 'UPDATE')` and fails if non-zero.
- **PII spill to dev laptop via extraction-script logs (NEW MED).** DEBUG mode logging row results = 100K rows in `extract.log`. Mitigation: **script does NOT support a DEBUG mode that prints rows** — only summary counts. `verify-no-debug-rowprint.sh` greps for `print(row` / `logging.debug(row` / `pprint(rows`.
- **Earlier threats still apply** (creds in repo, hardcoded host, writer role, `.env` leak, committed `raw/*.json`, hostnames in script) — unchanged.

### Authorization

- N/A at runtime.
- **DB-side, sharpened:** `schema_baseline_extractor` role grants:
  - `GRANT SELECT ON SCHEMA :: INFORMATION_SCHEMA TO schema_baseline_extractor;`
  - `GRANT SELECT ON sys.* TO schema_baseline_extractor;` (system catalog views)
  - `GRANT SELECT ON sys.dm_db_partition_stats, sys.dm_db_index_physical_stats, sys.dm_db_index_usage_stats, sys.dm_db_stats_properties TO schema_baseline_extractor;`
  - DMVs require `VIEW SERVER STATE` at server scope: `GRANT VIEW SERVER STATE TO [extractor_login];` — narrowly to the read-only role.
  - **Operator decision (Open design questions):** (a) single role with `VIEW SERVER STATE` runs everything, OR (b) split — read-only role for metadata + sys catalog; privileged human runs the DMV portion. Recommend (a) for solo-operator simplicity.
  - **Belt-and-braces:** `DENY SELECT ON SCHEMA :: dbo TO schema_baseline_extractor;` to block app-table reads at the DB level. Open design question on whether to also `DENY` on `dbo.Persons` and `dbo.AuditLogDetails` specifically — operationally awkward (would block the few permitted aggregates).

### Input validation

- N/A — script-level guardrails only.
- **`--allow-prod` flag operationally significant at scale.** Runbook entry mandatory before running:
  1. Confirm low-traffic window (outside 22:00 UTC ±1h and outside 12:00 UTC ±1h per `../../legacy/server.md` workflow schedule).
  2. Confirm `MSSQL_USER` matches `schema_baseline_extractor` (script asserts via `SELECT CURRENT_USER`).
  3. Confirm `MODE = 'LIMITED'` for all DMV queries (verifier checks).
  4. Confirm `excluded-tables.txt` review.

### PII handling — expanded with volume estimates

**Centerpiece. PII catalog column list unchanged from earlier; volume calculus added.**

PII categories (unchanged):
- **Direct identifiers:** `Person.Firstname/Lastname/Midname`, `AddressLine1/2`, `Zip/City/Region`, `PrivatePhone/MobilePhone/BusinessPhone/FaxNumber`, `EmailPrivate/EmailBusiness`, `Birthday`, `User.UserName`, `User.Email`.
- **Special-category health (FADP Art. 9):** `Person.MedicalClass*`, `MedicalIssueDate`, `MedicalExpireDate`, medical-cert blob columns.
- **Authentication artifacts:** `User.PasswordHash`, `SecurityStamp`, password-reset / email-confirmation tokens, lockout state.
- **Financial / billing:** invoice recipient name+address on `Delivery*`, recipient PII on `AccountingRuleFilter`, IBAN/account if present.
- **Pseudonymous re-identifiers:** `PersonClub.MemberNumber`, `Person.LicenceNumber`, OGN device IDs linking person↔flight.
- **Audit-log payloads:** `AuditLogDetails.OriginalValue` / `NewValue` (nvarchar(max)) — serialized entity snapshots. **Largest PII container in the system.**

**NEW: PII volume estimates per category** in §4.1:
- Direct identifiers in `Person`: ~100K rows × ~15 PII columns = ~1.5M data points. Single largest direct-identifier cluster outside the audit log.
- Audit-log serialized snapshots: ~100K `AuditLogs` × fan-out N × variable-width blobs (length distribution from §3.5 audit-log breakdown). Dominant PII container at scale.
- Per-table PII row count alongside each entry in §4 catalog — so S-013 sees encrypt-cost weighted.

**NEW: at-rest-encryption sizing implications** in §4.2:
- At 100K rows × ~10 PII columns × `pgcrypto` per-column envelope overhead = measurable write-path cost. S-013 picks columns by row-count weight.
- AuditLogDetails blobs: prefer envelope encryption with per-tenant DEK over pgcrypto (per-column pgcrypto on blobs destroys query performance).

**NEW: right-to-erasure under FADP §8/§25 at scale** in §4.3:
- Person deletion cascades to Flight history (potentially thousands of rows per Person across glider/tow/motor) + every `AuditLogDetails` row mentioning them.
- If audit blobs are opaque XML/JSON without per-Person index, erasure = full-table scan + blob rewrite. Flag for FADP-design follow-up story.

### Audit-log events

- N/A at runtime.
- **NEW: extraction-run provenance is a compliance artifact under FADP duty of care.** At this scale, "who pulled what from prod when" is itself audit-relevant. Commit message updating `legacy-baseline.md` carries: operator, source DB host, snapshot date (ISO 8601 UTC), extraction runtime duration, connection role used, `VIEW SERVER STATE` route chosen. `manifest.txt` (committed) is the structured form.

### Cross-tenant leakage

- N/A at extraction time.
- **Sharpened.** §3 tenant-scope catalog drives every `@TenantId` in S-022 and every CI assertion in S-024. At 100K+ Flight rows, **a single missed `@TenantId` traceable to a §3 miscategorization leaks every flight across every club until detected.** High-leverage security artifact.
- **NEW finding (from refinement):** `Flights` has NO `ClubId` column — tenancy is indirect via `Aircrafts.OwnerClubId`. The §3 catalog must use a third value `indirect-tenant` (not just `tenant-scoped` vs. `cross-tenant`); S-013 must consider denormalizing `club_id` into `flight` for performance.
- **`verify-tenant-scope-completeness.sh`:** for every `ClubId`-bearing table in `raw/columns.json`, doc must flag as `tenant-scoped`. For every non-`ClubId` table, doc must carry a one-line justification (`cross-tenant by design (Person)`, `reference data (Country)`, `system-global (Role)`, `indirect-tenant via Aircrafts (Flights)`). No silent omissions.

### OWASP applicability

- **A01 Broken Access Control:** read-only DB role at extraction time + `VIEW SERVER STATE` decision.
- **A03 Injection:** static SQL only, no concatenation.
- **A04 Insecure Design:** script designed to make PII exfiltration hard by mistake (queries as named constants; `assert` no `SELECT *`; no DEBUG row-print).
- **A05 Security Misconfiguration:** `.gitignore` for `raw/` (MB-class at scale); `.env.example` empty; `verify-gitignore-coverage.sh` runs `git check-ignore` against the typical paths.
- **A06 Vulnerable Components:** pin `sqlcmd` / `pyodbc` / `pymssql` versions in `next/database/extract/requirements.txt` with `--require-hashes`.
- **A09 Logging & Monitoring:** extraction-run provenance trail is the compliance log.
- **A02/A07/A08/A10:** N/A.

### Story-specific concerns — scale additions

- **`.gitignore` baseline** at `/c/Users/roman/IdeaProjects/fls/next/database/extract/.gitignore`:
  ```
  .env
  .env.local
  raw/*
  !raw/row-counts.json
  !raw/storage-stats.json
  !raw/column-cardinality.json
  !raw/index-sizes.json
  out/extract.log
  ```
  Mostly-deny + explicit allow-list for the four CI-consumed metadata files.

- **Extraction script header (mandatory, verifier-enforced):**
  ```python
  # =====================================================================
  # FLS legacy schema baseline extractor
  # =====================================================================
  # OPERATIONAL SCALE: production carries 100K+ Flight rows + 100K+
  # AuditLogs + larger AuditLogDetails (blob payloads). A single SELECT
  # against an application table is a six-figure PII spill under FADP /
  # GDPR Art. 9.
  #
  # WHITELIST: queries only:
  #   - INFORMATION_SCHEMA.*
  #   - sys.* (system catalog views)
  #   - sys.dm_db_partition_stats, sys.dm_db_index_physical_stats
  #     (MODE = 'LIMITED' ONLY; never DETAILED/SAMPLED)
  #   - sys.dm_db_index_usage_stats, sys.dm_db_stats_properties
  #
  # FORBIDDEN: any FROM clause naming an application table.
  # GATED: aggregate-only queries (COUNT/APPROX_COUNT_DISTINCT/MAX/AVG)
  #        permitted ONLY behind --allow-aggregate-counts flag.
  #
  # Modifying this script to read row contents is a reportable FADP
  # incident; second-pair-of-eyes review required, recorded in commit msg.
  # =====================================================================
  ```

- **Pre-merge verifier set** at `/c/Users/roman/IdeaProjects/fls/next/database/extract/verify/`:
  - `verify-no-row-data-queries.sh`: grep extraction SQL for `FROM\s+(dbo\.)?\b(Person|User|AuditLog|AuditLogDetails|Flight|Delivery|AccountingRuleFilter|PersonClub|Reservation|PlanningDay|SystemLog)\b` not within `--allow-aggregate-counts`-gated SELECT.
  - `verify-no-select-star.sh`: grep `SELECT\s+\*` outside `INFORMATION_SCHEMA` / `sys.*`.
  - `verify-no-detailed-mode.sh`: grep DMV queries for `MODE\s*=\s*'(DETAILED|SAMPLED)'`.
  - `verify-no-debug-rowprint.sh`: grep extraction script for `print(row` / `logging.debug(row` / `pprint(rows`.
  - `verify-role-readonly.sh`: runtime — `SELECT HAS_PERMS_BY_NAME('dbo.Person', 'OBJECT', 'UPDATE/INSERT/DELETE')` against connection; assert all return 0.
  - `verify-pii-counts-not-rows.sh`: grep `legacy-baseline.md` §3.5 for row tuples that smell like leaked rows.
  - `verify-gitignore-coverage.sh`: `git check-ignore raw/* out/extract.log` for unintended commits.

- **Operator runbook** in `next/database/extract/README.md` `## Running against production`:
  - Pre-flight checklist (low-traffic window, read-only role assertion, `MODE = 'LIMITED'` confirmation, `--allow-prod` flag, post-run `raw/` cleanup, commit-message provenance template).
  - Distribution: internal only; external publication requires operator approval (§3.5 reveals business scale).

- **Compliance disclosure in §0 Provenance:**
  > **Distribution:** internal modernization use only. The §3.5 Storage & cardinality section reveals business scale (club count, flight volume, audit volume). Republishing externally requires explicit operator approval — FADP duty of care.

## Test plan

### Coverage contract (updated for scale)

**S-010 owns (additions):**
- Completeness of `## 3.5 Storage & cardinality`: row count + storage MB per table; "Top 10 by rows", "Top 10 by storage", "Index size catalog" sub-sections present + populated.
- `### Audit-log sizing breakdown`: separate sizing for `AuditLogs` and `AuditLogDetails`; total rows, total storage MB, avg row size, largest blob columns (max/avg `DATALENGTH`), per-year row count when audit table has `created_at`.
- `### Column cardinality`: distinct-count for every indexed column on top-10-by-row tables; method recorded.
- `### Cutover-window budget math`: numeric "C6 budget remaining" line + per-table seconds estimates for at least top-5 tables.
- `### Stale statistics`: present if any stat last_updated > 30 days.
- `### Heap-only tables`: present if any heap exists.
- `### FK fan-in count`: per-table incoming-FK count, sorted desc.
- Banner enforcement nuance: schema sections valid against any source; storage / cardinality / cutover-math sections require **prod-derived** data, else verifiers degrade to banner-present-only.

**S-010 still owns (unchanged):** doc completeness (tables / cols / PK / FK / UQ / CK / defaults / indexes), EF-mapping drift, sacred-cow call-outs, PII catalog, tenant-scope catalog, snapshot provenance.

**S-010 defers:** new schema correctness (S-012/S-013/S-014), migration script (S-016), rehearsal parity (S-017/S-113), runtime tenancy tests (S-024), rules-engine config validity (S-107), index perf (S-013/S-062a).

### Test pyramid

- **Unit / Integration / E2E / Parity:** N/A — documentation-only.
- **Doc-verification (script-level):** ~20 shell scripts under `next/database/extract/verify/` orchestrated by `verify.sh`. CI runs against committed `raw/*.json` + the doc (no live-DB dependency).

### Verifier set (20 total)

**Existing 13** (unchanged): `verify-manifest.sh`, `verify-table-count.sh`, `verify-column-coverage.sh`, `verify-column-types.sh`, `verify-pk-coverage.sh`, `verify-fk-coverage.sh`, `verify-index-coverage.sh`, `verify-check-coverage.sh`, `verify-ef-mapping-drift.sh`, `verify-sacred-cows.sh`, `verify-pii-catalog.sh`, `verify-tenant-scope.sh`, `verify-no-pii-leaked.sh`, `verify-no-row-data.sh`, `verify-internal-links.sh`, `verify-domain-grouping.sh`.

**New 7 (scale-driven):**

- `verify-storage-section.sh` — asserts H2 `## 3.5 Storage & cardinality` exists with sub-sections "Top 10 tables by row count", "Top 10 tables by storage size", "Index size catalog"; each top-10 row has numeric row count + storage MB.
- `verify-row-counts-present.sh` — joins `raw/row-counts.json` against per-table H3 sections; every table carries its row count in either §3.5 top-10 OR its own H3 sizing footer.
- `verify-audit-log-section.sh` — asserts `### Audit-log sizing breakdown` with: separate `AuditLogs` and `AuditLogDetails` sub-blocks; total rows + storage MB + avg row size; largest-blob-columns table with max/avg `DATALENGTH`; per-year row count if audit table has `created_at`.
- `verify-cardinality-section.sh` — asserts `### Column cardinality` exists; every indexed column on top-10-by-row tables has a distinct-count entry; method recorded.
- `verify-cutover-window-math.sh` — asserts `### Cutover-window budget math` exists; numeric "C6 budget remaining" line; top-5 tables enumerated with per-table seconds estimate; total reconciles against 6h budget.
- `verify-no-detailed-mode.sh` — greps extraction scripts for `MODE = 'DETAILED'` / `MODE = 'SAMPLED'`; fails if found.
- `verify-no-select-star.sh` — greps extraction scripts for `SELECT \*` against non-system tables.
- `verify-row-count-sanity.sh` — sanity-floor at prod scale: `Flight ≥ 10_000`, audit-log table `≥ 10_000`. Falls below → fails with "looks like FLSTest; regenerate against prod before S-016." Skipped (warn-only) when `manifest.Source = FLSTest` AND banner present.

**Updated `verify-manifest.sh`:**
- Cross-checks `Source` vs. regenerate banner — `Source = prod` with banner present **fails** (catches stale banner after regen).
- When `Source ≠ prod`, all new scale-section verifiers degrade to banner-present-only checks.

### Edge cases at scale

- **FLSTest vs. prod row counts diverge 3-4 orders of magnitude.** `verify-row-count-sanity.sh` trip-wire. Banner nuance per Doc structure.
- **Per-year audit-log outliers** (one bad month with 10× volume). Surface as §13 knowledge gap; verifier doesn't fail on smoothness.
- **`APPROX_COUNT_DISTINCT` unavailable on SQL Server < 2019.** Verifier accepts `exact` | `approx` | `sampled at N rows` as long as method recorded.
- **Stale stats:** `### Stale statistics` sub-section conditional on extraction output.
- **`MAX(DATALENGTH(col))` cost at 100K rows:** doc footnotes "approximate, sampled at N rows via `TABLESAMPLE`"; verifier accepts exact or sampled with method recorded.
- **Snapshot drift mid-PR:** `extract.sh` records `CHECKSUM_AGG(...)`; `verify-manifest.sh` re-checks via committed `manifest.txt`.

### Test data + fixtures

- Source ranking unchanged: (1) prod restore on disposable instance, (2) staging restore, (3) FLSTest fixture.
- **Degraded mode for non-prod source:** if `manifest.Source ≠ prod`, the seven new scale verifiers downgrade to banner-present-only checks. FLSTest-backed runs go green for schema sections (the usable part); scale sections explicitly "not yet validated."
- `extract.sh` reads `MSSQL_CONNECTION_STRING` via env var; CI never gets it. Operator runs extraction locally; commits the four PII-safe metadata JSONs + rendered doc. CI re-runs verifiers against committed data.
- Cleanup: `raw/*.json` gitignored except the four allow-listed names.

### Doc-as-oracle for downstream (updated)

- **S-011 (tenant catalog):** unchanged.
- **S-012 (V1 baseline pt 1):** unchanged.
- **S-013 (Postgres baseline pt 2):** **NEW dependency** on `### Column cardinality`. S-013's tests assert new composite indexes order columns by descending cardinality consistent with legacy data — e.g. `(club_id, created_at DESC)` on audit-log only justified if cardinality shows `club_id` ≪ `created_at` distinct count.
- **S-016 (one-shot migration script):** **NEW dependency** on `### Cutover-window budget math`. S-016 rehearsal tests assert actual per-table migration time fits §3.5 budget; > 10% over triggers an S-016 re-plan, not an S-010 re-baseline.
- **S-017 / S-113 (rehearsals):** **NEW dependency** on `## 3.5` row counts as post-migration verification floor. Rehearsal re-runs `sys.dm_db_partition_stats` immediately pre-cutover and verifies < 10% drift vs. §3.5; > 10% triggers re-baseline.
- **S-024 (cross-tenant leakage CI):** unchanged.
- **S-027 (audit-log infrastructure):** consumes §4 PII catalog + `### Audit-log sizing breakdown` for retention-policy sizing + per-year-rows for partitioning recommendation.

**Cutover gate:** zero-delta on full ~20-verifier suite at PR-merge. Storage / cardinality / cutover-math sections must be prod-derived (Source = prod, no banner) before S-016 PR can merge.

### Coverage gaps (deferred)

- Rules-engine config validity → S-107.
- Per-tenant data invariants + actual migration timing vs. budget → S-017/S-113.
- Index performance + cardinality-driven composite ordering → S-013/S-062a.
- Trigger / stored-proc behavior — documented if present; tested by replacement story.
- C5 FADP DSAR mapping → surfaced in §4; handlers in later security story.
- Per-table seconds estimate calibration → S-017 rehearsal.

### Risks at scale

- **Row-count snapshot drift** between extraction and cutover. Mitigation: §0 snapshot date; S-016 re-runs `sys.dm_db_partition_stats` pre-cutover; > 10% drift → re-baseline.
- **`MAX(DATALENGTH)` runtime cost** at 100K rows. Mitigation: `TABLESAMPLE`; extraction budget raised from 2 min to 10 min when scale sections populated.
- **CI lacks prod-DB access.** Verifiers run against committed JSON; extraction is operator-only via runbook.
- **Banner-not-present false negatives** when source upgraded mid-PR. Mitigation: `verify-manifest.sh` cross-checks Source vs. banner — Source = prod with banner present also fails.
- **`APPROX_COUNT_DISTINCT` unavailable on older SQL Server.** Verifier accepts any of three methods.
- **Sampled cardinality misleading downstream S-013.** Mitigation: doc records sample size; S-013 ADRs for sampling-based composite indexes carry "validate against full counts post-restore" footnote.
- **Top-10 storage table inflated by audit-log alone.** Dedicated audit sub-section keeps top-10 storage informative.

## Performance plan

### Hot paths
- `INFORMATION_SCHEMA.*`, `sys.tables`, `sys.foreign_keys`, `sys.indexes`, `sys.index_columns`, `sys.check_constraints`, `sys.default_constraints` — sub-second.
- `sys.dm_db_partition_stats` — sub-second; cheap row counts.
- `sys.dm_db_index_physical_stats(..., 'LIMITED')` — root-page sampling; ≤ few seconds at scale. **Mandatory.**
- `sys.dm_db_index_physical_stats(..., 'DETAILED')` — full B-tree scan; 10s of seconds per index; buffer-pool eviction. **Forbidden.**
- `APPROX_COUNT_DISTINCT(col)` on indexed columns — sub-second per column (HyperLogLog over index histograms).
- `COUNT(DISTINCT col)` exact — slower; use approx unless column has fresh stats.
- `MAX(DATALENGTH(blob_col))` on `nvarchar(max)` — tens of seconds at 100K rows; **`TABLESAMPLE (1000 ROWS) REPEATABLE (42)` mitigation mandatory.**
- `SELECT YEAR(created_at), COUNT(*) FROM AuditLog GROUP BY YEAR(created_at)` — fast IF `created_at` indexed; check `sys.indexes` before running. Falls back to `TABLESAMPLE` estimate if unindexed.

### Required indexes
N/A — story creates none. **Downstream:** doc catalogs every legacy index with size MB + filter predicate + INCLUDE columns. The floor S-013 must replicate.

### N+1 risks
N/A — no ORM, bulk metadata reads only.

### Cartesian / explosion risks
`sys.indexes` × `sys.index_columns` × `sys.columns` joins must filter `OBJECT_SCHEMA_NAME(object_id) = 'dbo'`, `is_hypothetical = 0`, `is_ms_shipped = 0`. `sys.foreign_keys` × `sys.foreign_key_columns` × `sys.columns` same.

### Caching strategy
N/A — one-off CLI; `raw/*.json` is the only on-disk artifact.

### Latency budget
- **Extraction total: < 5 min** against full prod schema with 100K+ Flight + 100K+ audit rows. Bottlenecks: `MAX(DATALENGTH)` on `nvarchar(max)` (mitigated via `TABLESAMPLE`) + per-indexed-column `APPROX_COUNT_DISTINCT` on top-10 tables.
- **> 10 min → investigate.** Likely `DETAILED` mode slipped past verifier or `MAX(DATALENGTH)` ran unbounded.
- **Doc-generation (render.py): < 10 s.**
- **Verifier total: < 30 s.** Slowest: `verify-ef-mapping-drift.sh` (regex over ~3,867 lines of `MappingExtensions.cs`) + `verify-internal-links.sh`.

### Memory considerations
- Extraction in-memory: < 10 MB. Trivial.
- `raw/*.json` per file: largest is `columns.json` at ~200 KB. `storage-stats.json` for 40 tables ≈ tens of KB. **If any single file exceeds 5 MB, fail loudly — row data has leaked in.**
- **`legacy-baseline.md` output: 5K–10K lines, 200–500 KB.** Up from earlier 2K–5K because of §3.5 + per-column TZ annotations + sizing footers. Renders fine in IDEs and GitHub (limit 1 MB / 50K lines). See Open design questions on doc split.

### Performance test plan
- **Idempotency regression:** run extraction twice, `diff out/*.json` and `diff legacy-baseline.md` — byte-identical. Deterministic ordering (tables alphabetical; columns by `ordinal_position`; indexes by name).
- **Latency smoke:** wall-clock < 600s. CI annotates duration in PR comment.
- **Read-only assertion:** grep `extract/schema.sql` for `\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE|EXEC)\b` outside comments — zero matches.
- **`MODE = 'LIMITED'` assertion:** `grep -nE "sys\.dm_db_index_physical_stats\s*\(" extract/*.sql` — every match's 5th arg must be literal `'LIMITED'`.
- **`TABLESAMPLE` assertion for blob length queries:** any `MAX(DATALENGTH(...))` against `nvarchar(max)` paired with `TABLESAMPLE`. Verifier `verify-blob-sampling.sh`.

### Cutover-window budgeting (centerpiece — feeds §3.5)

**Throughput assumptions:**
- `bcp out` from SQL Server: ~20–50 MB/s.
- `bcp in` to Postgres via `COPY`: ~50–100 MB/s.
- `pg_restore -j 4` on Postgres 17: ~50–100 MB/s.
- **Conservative end-to-end: 30 MB/s.**

**Per-table formulas:**
- Migrate seconds: `storage_mb / 30`
- Index rebuild seconds: `storage_mb / 50`
- Audit-log PII redaction: `~10K rows/s single-threaded; parallelizable`

**Worked example (hypothetical; actual numbers from prod-source run):**

| Table | Rows | Storage MB | Migrate s | Reindex s | Redact s | Subtotal s |
|---|---|---|---|---|---|---|
| `Flight` | 100K | 50 | 2 | 1 | — | 3 |
| `AuditLogs` | 100K | 50 | 2 | 1 | 5 | 8 |
| `AuditLogDetails` | 500K | 1000 | 33 | 20 | 50 | 103 |
| (all others) | — | 200 | 7 | 4 | — | 11 |
| **Bulk-data subtotal** | | | | | | **~125 s** |

**C6 budget = 21,600 s.** Bulk-data subtotal ~125 s = **~0.6%** of budget. **99.4% headroom.**

**Conclusion the doc must surface:** at this scale the migration window is NOT bounded by row volume; it is bounded by verification + `ANALYZE` + smoke + manual sign-off. S-016/S-017 should NOT over-engineer the bulk-copy step (no parallel-streamed-table sharding, no pg_logical warm replication). They SHOULD invest budget in automated post-migration parity checks.

**Sensitivity / caveats:**
- 30 MB/s is conservative; actual could be 2–3× higher or lower.
- Assumes Postgres bulk-load tuning preconditions: `pg_restore -j 4`, `maintenance_work_mem ≥ 256 MB`, `synchronous_commit = off` during bulk load (reset after), per-session `work_mem` for sort-heavy index builds. Preconditions for S-016, not S-010 to enforce.
- If audit-log grows to 1M+ rows by cutover, redact step dominates per-table cost (~100s) but still trivial vs. budget. S-017 rehearsal calibrates.

### Configuration choices — sharpened at scale

- **Tenant-scope catalog accuracy is load-bearing for S-022 / S-062a.** At 100K+ rows, a missed `@TenantId` on a list query → missing `club_id` predicate → full-table scan → 30s+ list-page load. **Special case: `Flights` has no `ClubId` column.** S-013's denormalization decision turns on §3.5 storage data + §3 tenant-scope flag.
- **Column-cardinality data drives composite-index ordering in S-013.** Postgres B-tree composite indexes work best with most-selective-column-first. Worked example: `Flight.club_id` (~10-50 distinct via Aircrafts) vs. `Flight.flight_date` (~10K distinct). For "list flights for this club in this date range," `(club_id, flight_date)` is right; `(flight_date, club_id)` is wrong. **Without §3.5 cardinality, this is a coin flip.**
- **Index-size catalog drives "do we need partial indexes?" decisions.** Postgres partial indexes shrink hot data. Legacy already uses filtered indexes (`DBUpdate_v1.9.4.sql:27` `WHERE [FlightCode] IS NOT NULL`); catalog them with filter + size.
- **Audit-log sizing drives partitioning decision in S-013.** Threshold rule: partition above ~1M rows or ~10 GB. Per-year breakdown drives partition boundaries.
- **Stale-statistics flagging** is context for "is the old system actually fast?" — informs S-108 perf-baseline comparator.
- **Per-column timezone-semantics annotation is mandatory.** Wrong `timestamp` vs. `timestamptz` mapping in S-013 shifts flight times by 1-2 hours silently, breaking ≥2-day lock + ≥3-day delivery gates.

### Risks

- **`DETAILED` mode against live prod.** Forbidden; verifier blocks.
- **`MAX(DATALENGTH)` cost** without `TABLESAMPLE` — minutes per column at 100K rows. Mitigation: `TABLESAMPLE (1000 ROWS) REPEATABLE (42)`; footnote sample size + seed in §5.
- **`APPROX_COUNT_DISTINCT` not available on SQL Server < 2019.** Check `@@VERSION`; fall back to `COUNT(DISTINCT) OVER TABLESAMPLE` with documented sample. Value annotated `(approx)` or `(sampled@1000)` so S-013 knows confidence.
- **Snapshot drift between extraction and S-016 cutover.** Mitigation: §0 snapshot timestamp; S-016 re-extracts row-count+storage block at cutover-day-minus-7; > 10% top-10-table drift fails rehearsal gate.
- **Cutover-window math sensitive to throughput assumptions.** Doc shows all assumptions; S-017 measures actuals and re-writes §3.5.
- **Postgres bulk-load tuning** affects achievable throughput 2–5×. Flagged in §3.5 as preconditions for S-016.
- **Sys.* join explosion on wide DBs** — schema filter + skip hypothetical/MS-shipped.
- **Accidental prod-load contention** — even metadata queries can briefly block on schema-modification locks. Run against restored snapshot or off-hours; script logs host + start/end wall-clock.

## Open design questions

These specialists' analyses surfaced operator-decision points; the skill does not silently resolve them.

1. **AC4 addition for scale-bearing content.** The current AC list does not require row counts, storage MB, cardinality, audit-log sizing, or cutover-window math. With prod scale, a developer can close S-010 with a doc useless for S-013 / S-016. **Recommend (operator decision):** add **AC4** — *"The doc includes per-table row count, storage MB, per-index size MB + reads/writes since SQL Server restart, NDV for every indexed column, dedicated H2 sections for top-N by rows and storage, audit-log sizing breakdown for both `AuditLogs` and `AuditLogDetails`, and a per-top-10-table cutover-window estimate."* Per skill rules, surfaced here rather than auto-edited into frontmatter.

2. **Aggregate-count queries against user tables.** `COUNT(*) FROM Flights`, `GROUP BY ProcessState`, `GROUP BY YEAR(created_at)` on audit_log. These touch user tables but produce counts only — no row data. Three positions:
   - **Permitted via `--allow-aggregate-counts` flag** (architect + requirements recommend): cheapest path; one extraction run produces all the §3.5 numbers; gated and logged.
   - **Pre-computed-and-committed-as-data** (security defense-in-depth): operator runs the aggregates once in a reviewed session, commits the numbers; extractor never touches user tables. More overhead but maximally safe.
   - **Forbidden entirely**: §3.5 falls back to estimates from `sys.dm_db_partition_stats` row counts + index sizes; per-state distribution + per-year breakdown not in scope.
   
   Recommend: **`--allow-aggregate-counts` gated** with explicit operator confirmation in commit message + DENY-at-table-level on the role NOT in effect for the few permitted aggregations.

3. **`Persons` / `AuditLogDetails` DENY-at-table-level.** If `DENY SELECT ON dbo.Persons` is set for `schema_baseline_extractor`, the few permitted aggregates fail too. Operator decision: (a) accept the DENY and pre-compute aggregates by hand, OR (b) skip the DENY (read-only role + script-level guards are sufficient).

4. **Single doc vs. split files** at 5K-10K lines / 200-500 KB. Single doc forces reviewers to scroll but keeps cross-section reading natural during S-013 design. Split (`legacy-baseline.md` + `legacy-storage.md` + `legacy-pii.md`) is cleaner navigationally but breaks the redesigner's "all on one screen" workflow. Recommend: **single doc**, with mandatory ToC at top + anchor links per section. Operator decides.

5. **S-010 ↔ S-011 boundary.** Carry-over from earlier refinement: S-010 owns the raw `ClubId` column list in §3; S-011 owns the classified tenancy strategy. With new finding that `Flights` has indirect tenancy via `Aircrafts.OwnerClubId`, S-011 may need a third classification value. Resolve when S-011 is itself refined.

6. **Throughput-constant calibration timing.** §3.5 cutover math uses `30 MB/s` default. S-017 rehearsal measures real. **Recommend:** ship S-010 with `30 MB/s` default + per-table seconds estimates. Update §3.5 numbers (single-line + renderer recompute) after first rehearsal. If first rehearsal shows < 15 MB/s sustained, C6 ≤ 6h budget at risk for audit-log specifically — flag S-016 to plan parallel-load strategy.

<!-- modernize-refine: end -->
