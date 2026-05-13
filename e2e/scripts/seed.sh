#!/usr/bin/env bash
# e2e/scripts/seed.sh
#
# Brings the FLSTest database to a deterministic, post-fixture state suitable
# for the Playwright e2e suite. Drops the database, re-applies the schema,
# applies the static seed files, then applies the deterministic fixture
# (database/FLSTest/3 insert/_test-fixture.sql). Idempotent: running twice
# in a row produces the same result.
#
# Usage:
#   bash e2e/scripts/seed.sh
#
# Requires: docker daemon, a running container named "fls-mssql" with sa/Demo#FLS#2026.

set -euo pipefail

CONTAINER="${FLS_MSSQL_CONTAINER:-fls-mssql}"
SA_PASS="${FLS_MSSQL_SA_PASSWORD:-Demo#FLS#2026}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SQL_DIR="$REPO_ROOT/flsserver/database/FLSTest"
ALTER_DIR="$SQL_DIR/2 alter"
INSERT_DIR="$SQL_DIR/3 insert"

# Severity threshold: 16-25 are real errors per sqlcmd convention.
FATAL_REGEX='Msg [0-9]+, Level (1[6-9]|2[0-5])'

# Two sqlcmd flavours:
#   - SQLCMD_STRICT (-b) exits non-zero on the first batch error. We use it
#     for inserts and the fixture, where any complaint is fatal.
#   - SQLCMD_LOOSE keeps running across GO batches even if one errors. The
#     legacy DBUpdate scripts hardcode auto-generated constraint names that
#     differ on a fresh CREATE; their first ALTER aborts under -b, leaving
#     subsequent ALTERs in the same script unrun. We deliberately tolerate
#     those (TESTING.md sec. 1.2 documents the baseline noise) and still log
#     anything fatal so regressions stand out.
SQLCMD_STRICT=(docker exec -i "$CONTAINER" /opt/mssql-tools18/bin/sqlcmd
               -S localhost -U sa -P "$SA_PASS" -C -b)
SQLCMD_LOOSE=(docker exec -i "$CONTAINER" /opt/mssql-tools18/bin/sqlcmd
              -S localhost -U sa -P "$SA_PASS" -C)

log() { printf '[seed] %s\n' "$*" >&2; }

run_sql_file() {
    local file="$1"
    local db="${2:-FLSTest}"
    local label="${3:-$file}"
    local tolerant="${4:-0}"
    log "applying: $label"
    docker cp "$file" "$CONTAINER:/tmp/seed_current.sql" >&2

    local out
    if [[ "$tolerant" == "1" ]]; then
        # Use loose sqlcmd: keep running across GO batches; surface complaints
        # but do not fail the script on them.
        out="$("${SQLCMD_LOOSE[@]}" -d "$db" -i /tmp/seed_current.sql 2>&1 || true)"
        printf '%s\n' "$out" | grep -E "$FATAL_REGEX" >&2 || true
        return 0
    fi

    if ! out="$("${SQLCMD_STRICT[@]}" -d "$db" -i /tmp/seed_current.sql 2>&1)"; then
        printf '%s\n' "$out" >&2
        log "FAILED: $label"
        exit 1
    fi
    if printf '%s\n' "$out" | grep -E "$FATAL_REGEX" >/dev/null; then
        printf '%s\n' "$out" >&2
        log "FATAL sqlcmd error in: $label"
        exit 1
    fi
}

run_sql_query() {
    local query="$1"
    local db="${2:-master}"
    local out
    if ! out="$("${SQLCMD_STRICT[@]}" -d "$db" -Q "$query" 2>&1)"; then
        printf '%s\n' "$out" >&2
        log "FAILED query: $query"
        exit 1
    fi
    if printf '%s\n' "$out" | grep -E "$FATAL_REGEX" >/dev/null; then
        printf '%s\n' "$out" >&2
        log "FATAL sqlcmd error on query: $query"
        exit 1
    fi
    printf '%s\n' "$out"
}

# ---------------------------------------------------------------------------
# 0. Sanity checks
# ---------------------------------------------------------------------------
if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
    log "container '$CONTAINER' not found; expected a running SQL Server container"
    exit 1
fi

[[ -d "$ALTER_DIR"  ]] || { log "missing $ALTER_DIR";  exit 1; }
[[ -d "$INSERT_DIR" ]] || { log "missing $INSERT_DIR"; exit 1; }
[[ -f "$INSERT_DIR/_test-fixture.sql" ]] || { log "missing _test-fixture.sql"; exit 1; }

# ---------------------------------------------------------------------------
# 1. Drop + recreate FLSTest (idempotency the brute-force way).
# ---------------------------------------------------------------------------
log "drop + create FLSTest"
run_sql_query "
IF DB_ID('FLSTest') IS NOT NULL
BEGIN
    ALTER DATABASE [FLSTest] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE [FLSTest];
END;
CREATE DATABASE [FLSTest];
" master

# ---------------------------------------------------------------------------
# 2. Apply base schema + DBUpdate scripts in semantic-version order.
#    Several DBUpdates are not idempotent (they ADD COLUMN / CREATE TABLE
#    without IF NOT EXISTS); since step 1 dropped the DB we are starting
#    clean every time.
# ---------------------------------------------------------------------------
run_sql_file "$ALTER_DIR/2 Alter Database.sql" FLSTest "2 Alter Database.sql"

# Semantic-version sort: split DBUpdate_vX.Y.Z by dots, sort numerically.
# Falls back to natural ordering for things like "1.10.5p1".
mapfile -t DBUPDATES < <(
    cd "$ALTER_DIR"
    ls DBUpdate_v*.sql 2>/dev/null \
      | awk '{ ver=$0; sub(/^DBUpdate_v/,"",ver); sub(/\.sql$/,"",ver); print ver"\t"$0 }' \
      | sort -V \
      | cut -f2
)

for f in "${DBUPDATES[@]}"; do
    run_sql_file "$ALTER_DIR/$f" FLSTest "alter: $f" 1
done

# DataUpdate scripts (one-shot DB cleanups packaged with the alters).
if [[ -f "$ALTER_DIR/DataUpdate InOutbound-Routes.sql" ]]; then
    run_sql_file "$ALTER_DIR/DataUpdate InOutbound-Routes.sql" FLSTest "DataUpdate InOutbound-Routes.sql" 1
fi

# ---------------------------------------------------------------------------
# 3. Apply the static seed files in canonical order
#    (mirror TESTING.md sec. 1.2). Skip 7a (teardown).
# ---------------------------------------------------------------------------
# tolerant=1 entries below have known baseline complaints:
#   "7 Create Logins FLSTest.sql" creates server-level logins; on reruns
#     those already exist. The CREATE USER/SCHEMA portions inside FLSTest do
#     re-run cleanly because the DB was dropped in step 1.
#   "100 Insert AccountingRuleFilters.sql" contains rules whose ClubId
#     references other clubs not seeded here (the file lives in the
#     legacy /database/FLSTest tree and was never trimmed for the test
#     fixture). We keep it for now so any rows that *do* match still load;
#     _test-fixture.sql below adds the testclub-scoped rules e2e relies on.
# Pipe-separated: filename|db|tolerant
STATIC_SEEDS=(
    "3 Insert Static Data.sql|FLSTest|0"
    "4 or 5 Insert Test Data.sql|FLSTest|0"
    "6 Insert Test Flights.sql|FLSTest|0"
    "7 Create Logins FLSTest.sql|master|1"
    "10 insert internationalisation values.sql|FLSTest|0"
    "90 Insert EmailTemplates.sql|FLSTest|0"
    "99 Insert SystemData.sql|FLSTest|0"
    "100 Insert AccountingRuleFilters.sql|FLSTest|1"
)
for spec in "${STATIC_SEEDS[@]}"; do
    IFS='|' read -r fname db tolerant <<< "$spec"
    if [[ -f "$INSERT_DIR/$fname" ]]; then
        run_sql_file "$INSERT_DIR/$fname" "$db" "insert: $fname" "$tolerant"
    else
        log "warn: missing $INSERT_DIR/$fname -- skipping"
    fi
done

# ---------------------------------------------------------------------------
# 4. Apply the deterministic fixture last.
# ---------------------------------------------------------------------------
run_sql_file "$INSERT_DIR/_test-fixture.sql" FLSTest "_test-fixture.sql"

# ---------------------------------------------------------------------------
# 5. Light post-condition check (informational only).
# ---------------------------------------------------------------------------
log "post-condition counts:"
run_sql_query "SET NOCOUNT ON;
SELECT 'Clubs',            COUNT(*) FROM Clubs
UNION ALL SELECT 'ARFs(testclub)', COUNT(*) FROM AccountingRuleFilters WHERE ClubId='0FA7B76F-47BA-4138-8F96-671400FD7C83'
UNION ALL SELECT 'PersonCategories', COUNT(*) FROM PersonCategories
UNION ALL SELECT 'HistoricalFlights', COUNT(*) FROM Flights WHERE FlightDate < '2025-12-15'
UNION ALL SELECT 'SmtpIsMailpit',  CASE WHEN EXISTS(SELECT 1 FROM SystemData WHERE SmtpServer='localhost' AND SmtpPort=1025) THEN 1 ELSE 0 END;
" FLSTest

log "done."
