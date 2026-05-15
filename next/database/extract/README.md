# fls-legacy-extract

Spring Boot CLI that reads metadata from a legacy FLS SQL Server database and
emits ephemeral JSON describing the schema. The JSON is the input downstream
modernization stories (S-012/S-013/S-014/S-016) consume when designing the
new Postgres schema and the one-shot migration script.

**Read-only by construction.** The app queries `INFORMATION_SCHEMA`, `sys.*`,
and `sys.dm_db_*` views only. Application-table reads (anything against
`Persons`, `Flights`, `AuditLogs`, etc.) are forbidden unless gated behind the
`--allow-aggregate-counts` flag, and even then only aggregate expressions
(`COUNT`, `APPROX_COUNT_DISTINCT`, `MAX(DATALENGTH(...))`) are permitted. A
JUnit test asserts the SQL classpath contains no `SELECT *` outside
`INFORMATION_SCHEMA` / `sys.*` and no `MODE = 'DETAILED'` / `'SAMPLED'` calls
to `sys.dm_db_index_physical_stats`.

## Why this exists

Production schema is driven by `flsserver/database/FLS/Updates/DBUpdate_v*.sql`
(11 scripts, no single source of truth). The EF migration tree under
`flsserver/src/FLS.Server.Data/Migrations/` is frozen at the 2015 baseline.
Reading `INFORMATION_SCHEMA` from the prod-applied DDL is the only way to get
an authoritative current-state view. This tool produces that view on demand
— it is not a one-time documentation pass; downstream stories re-run it.

## Running

### Against a local FLSTest container (default path)

```bash
# 1. Start a SQL Server container (Linux: x64; Apple Silicon: see "Apple Silicon" below).
docker run -d --name fls-mssql \
  -e ACCEPT_EULA=Y \
  -e MSSQL_SA_PASSWORD='Y0ur_strong!Pass' \
  -p 1433:1433 \
  mcr.microsoft.com/mssql/server:2022-latest

# 2. Wait ~10s for it to be ready. Then seed with the FLSTest fixture.
#    (The integration tests do this automatically — see FlsTestSchemaSeeder.
#    For ad-hoc runs, mount the FLSTest dir and run sqlcmd inside the
#    container, or use Azure Data Studio / mssql-cli.)

# 3. Run the extractor against the seeded DB.
export MSSQL_HOST=localhost
export MSSQL_PORT=1433
export MSSQL_USER=sa
export MSSQL_PASSWORD='Y0ur_strong!Pass'
export MSSQL_DATABASE=FLSTest
./gradlew bootRun --args="--allow-aggregate-counts"

# 4. JSON outputs land under ./raw/ (gitignored).
ls raw/
# tables.json columns.json pks.json fks.json uniques.json checks.json
# defaults.json indexes.json views.json triggers.json identity-columns.json
# ef-mappings.json row-counts.json storage-stats.json index-sizes.json
# index-usage.json column-cardinality.json manifest.json
```

### Against production (operator-only, audited)

Pre-flight checklist:

1. Connect with a read-only role (`schema_baseline_extractor`) — the role
   grants `SELECT` on `INFORMATION_SCHEMA`, `sys.*`, `sys.dm_db_*`, and
   nothing on `dbo.*`. Belt-and-braces: even with a writer role the runtime
   guard rejects DDL / DML, but the DB role is the load-bearing defense.
2. Pick a low-traffic window (outside 22:00 UTC ±1h and 12:00 UTC ±1h —
   that's when the legacy `WorkflowService` cron jobs run).
3. `--allow-prod` is required when `MSSQL_HOST` does not resolve to a
   loopback address (the app refuses non-local hosts without it).
4. `--allow-aggregate-counts` is required to emit `row-counts.json`,
   `storage-stats.json`, `column-cardinality.json`, and
   `audit-log-sizing.json`. Without the flag those files are absent and
   downstream-story implementations that depend on them will fail their
   own preconditions.
5. Capture commit-message provenance: operator name, source DB host,
   snapshot date (ISO 8601 UTC), extraction duration, connection role,
   and whether `--allow-aggregate-counts` was set. `manifest.json` is the
   structured form; the commit message links to it.

## Flags

| Flag | Purpose |
|---|---|
| `--allow-aggregate-counts` | Enables aggregate queries against app tables (counts, NDV, blob-length sampling). Off by default; required for scale-bearing JSON outputs. |
| `--allow-prod` | Required when `MSSQL_HOST` is not loopback. Forces explicit operator acknowledgement of the risk. |
| `--out-dir=DIR` | Override the output directory (default `./raw/`). |

## JSON outputs

All under `raw/` (gitignored). Each file is a JSON array of records; the
record shape is documented as JavaDoc on the corresponding Java record class
under `src/main/java/ch/fls/legacyextract/output/`.

| File | Always emitted? | Records |
|---|---|---|
| `tables.json` | yes | `schema, name, type (TABLE/VIEW), row_count_hint` |
| `columns.json` | yes | `schema, table, name, ordinal, data_type, is_nullable, default, is_identity, is_computed, max_length, precision, scale` |
| `pks.json` | yes | `schema, table, constraint_name, columns[]` |
| `fks.json` | yes | `schema, table, constraint_name, columns[], referenced_schema, referenced_table, referenced_columns[], on_delete, on_update` |
| `uniques.json` | yes | `schema, table, constraint_name, columns[]` |
| `checks.json` | yes | `schema, table, constraint_name, definition` |
| `defaults.json` | yes | `schema, table, column, constraint_name, definition` |
| `indexes.json` | yes | `schema, table, name, type, is_unique, is_primary_key, columns[], included_columns[], filter` |
| `views.json` | yes | `schema, name, definition_present` (definition body excluded — view bodies can contain hard-coded test data on legacy systems) |
| `triggers.json` | yes | `schema, table, name, type, is_disabled` |
| `identity-columns.json` | yes | `schema, table, column, seed, increment, last_value` |
| `manifest.json` | yes | extraction metadata: source host, DB version, snapshot date, duration, flags, app version, git SHA |
| `row-counts.json` | only with `--allow-aggregate-counts` | `schema, table, row_count, used_mb, reserved_mb` (from `sys.dm_db_partition_stats`) |
| `storage-stats.json` | only with `--allow-aggregate-counts` | per-table data MB + index MB + unused MB |
| `index-sizes.json` | only with `--allow-aggregate-counts` | per-index size MB + LIMITED-mode fragmentation % |
| `index-usage.json` | only with `--allow-aggregate-counts` | per-index seeks/scans/lookups/updates since SQL Server restart, plus `sqlserver_start_time` for context |
| `column-cardinality.json` | only with `--allow-aggregate-counts` | per (indexed column) `approx_distinct, method, sampled_rows` |
| `audit-log-sizing.json` | only with `--allow-aggregate-counts` | total rows, total MB, avg row size, fan-out ratio, blob-column max/avg `DATALENGTH` (sampled) for `AuditLogs` and `AuditLogDetails` |
| `cutover-window.json` | only with `--allow-aggregate-counts` | per-top-10-table cutover-window estimate: `storage_mb`, `migrate_seconds` (`storage_mb / 30`), `reindex_seconds` (`storage_mb / 50`), `subtotal_seconds`, `pct_of_budget`. Throughput overridable via `-Dextract.throughput.mb-per-sec=N`. Surfaces the S-016 / S-017 finding that bulk-data migration is < 1% of the C6 ≤ 6h budget — the window is bounded by verification, not row volume. |

## Tests

```bash
./gradlew test
```

**Integration-tests only — no mocking, no unit-test tier.** Per the test
philosophy for this stack, every test connects to a real SQL Server in a
Docker container. The single integration test class
(`MetadataExtractorIntegrationTest`) starts a SQL Server container via
`MssqlTestContainerLifecycle` (which shells out to the `docker` CLI rather
than using Testcontainers — see "Why not Testcontainers" below), seeds it
with the actual FLSTest fixture (`flsserver/database/FLSTest/`), runs the
extractor end-to-end, and asserts the JSON outputs contain real FLS tables.
~60-90s per run after the SQL Server image is cached. The
`SqlGuard.scanClasspathResources()` boot-time safety net is exercised by
the same test (catches forbidden SQL patterns at the classpath level).

### Why not Testcontainers

Testcontainers 1.21.x ships docker-java 3.4.x, which negotiates Docker REST
API version 1.32 by default. Recent Docker daemons (29.x and newer) enforce
a minimum API version of 1.44 and reject the older negotiation. Setting
`DOCKER_API_VERSION=1.45` via env / system property / `~/.testcontainers.properties`
doesn't override the hardcoded constant deep inside docker-java's request
path. Rather than carry the workaround indefinitely, the test infra drives
the container lifecycle through the `docker` CLI directly — the CLI does
its own version negotiation and works against any modern daemon.

## Apple Silicon / non-x86

`mcr.microsoft.com/mssql/server` is x86-only. On ARM use
`mcr.microsoft.com/azure-sql-edge:latest` (the test code already does this
automatically — Testcontainers detects the architecture).

## Where the durable knowledge lives

- **Sacred-cow callouts + tenant-scope reasoning + PII catalog narrative**
  live as JavaDoc on the corresponding output record types in
  `src/main/java/ch/fls/legacyextract/output/`. Future-readers find the
  reasoning next to the code that emits it.
- **Source-of-truth precedence rule** (prod-applied DDL > `DBUpdate_v*.sql`
  > EF mappings; EF migration tree frozen at 2015) lives at the top of
  `ExtractApplication.java`'s JavaDoc.
- **Cutover-window math + 30 MB/s assumption** lives as constants +
  JavaDoc on the `CutoverWindowEstimator` class.

There is no separate curation Markdown document — the operator override
during S-010 implement-phase was: "drop the whole verifier-script concept
too... a small spring-boot app would be easier to maintain and test."
