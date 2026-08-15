#!/usr/bin/env bash

set -euo pipefail

CONTAINER="${FLS_MSSQL_CONTAINER:-fls-e2e-mssql-1}"
SA_PASS="${FLS_MSSQL_SA_PASSWORD:-Demo#FLS#2026}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SQL_DIR="$REPO_ROOT/flsserver/database/FLSTest"
ALTER_DIR="$SQL_DIR/2 alter"
INSERT_DIR="$SQL_DIR/3 insert"

FATAL_REGEX='Msg [0-9]+, Level (1[6-9]|2[0-5])'

SQLCMD_STRICT=(docker exec -i "$CONTAINER" //opt/mssql-tools18/bin/sqlcmd
               -S localhost -U sa -P "$SA_PASS" -C -b)
SQLCMD_LOOSE=(docker exec -i "$CONTAINER" //opt/mssql-tools18/bin/sqlcmd
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
        out="$("${SQLCMD_LOOSE[@]}" -d "$db" -i //tmp/seed_current.sql 2>&1 || true)"
        printf '%s\n' "$out" | grep -E "$FATAL_REGEX" >&2 || true
        return 0
    fi

    if ! out="$("${SQLCMD_STRICT[@]}" -d "$db" -i //tmp/seed_current.sql 2>&1)"; then
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

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
    log "container '$CONTAINER' not found; expected a running SQL Server container"
    log "running containers:"
    docker ps --format '  {{.Names}}  ({{.Status}})' >&2 || true
    log "start the stack with: bash e2e/scripts/dev-up.sh"
    log "or override the target with: FLS_MSSQL_CONTAINER=<name> $0"
    exit 1
fi

[[ -d "$ALTER_DIR"  ]] || { log "missing $ALTER_DIR";  exit 1; }
[[ -d "$INSERT_DIR" ]] || { log "missing $INSERT_DIR"; exit 1; }
[[ -f "$INSERT_DIR/_test-fixture.sql" ]] || { log "missing _test-fixture.sql"; exit 1; }

compute_seed_hash() {
    {
        find "$ALTER_DIR"  -maxdepth 1 -name '*.sql' -type f -print0 | sort -z | xargs -0 sha256sum
        find "$INSERT_DIR" -maxdepth 1 -name '*.sql' -type f -print0 | sort -z | xargs -0 sha256sum
    } | sha256sum | cut -c1-16
}
SEED_HASH="$(compute_seed_hash)"
BAK_PATH="/var/opt/mssql/seed_${SEED_HASH}.bak"

if [[ "${FLS_SEED_FORCE:-0}" != "1" ]] \
        && docker exec "$CONTAINER" bash -c "test -f $BAK_PATH" 2>/dev/null; then
    log "cache hit ($SEED_HASH); RESTORE FROM DISK = $BAK_PATH"
    run_sql_query "
IF DB_ID('FLSTest') IS NOT NULL
BEGIN
    ALTER DATABASE [FLSTest] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
END;
RESTORE DATABASE [FLSTest] FROM DISK = N'$BAK_PATH' WITH REPLACE;
ALTER DATABASE [FLSTest] SET MULTI_USER;
" master >/dev/null
    log "post-condition counts (restored):"
    run_sql_query "SET NOCOUNT ON;
SELECT 'Clubs',            COUNT(*) FROM Clubs
UNION ALL SELECT 'ARFs(testclub)', COUNT(*) FROM AccountingRuleFilters WHERE ClubId='0FA7B76F-47BA-4138-8F96-671400FD7C83'
UNION ALL SELECT 'PersonCategories', COUNT(*) FROM PersonCategories
UNION ALL SELECT 'HistoricalFlights', COUNT(*) FROM Flights WHERE FlightDate < '2025-12-15'
UNION ALL SELECT 'SmtpIsMailpit',  CASE WHEN EXISTS(SELECT 1 FROM SystemData WHERE SmtpServer='localhost' AND SmtpPort=1025) THEN 1 ELSE 0 END;
" FLSTest
    log "done (cached)."
    exit 0
fi

if [[ "${FLS_SEED_FORCE:-0}" == "1" ]]; then
    log "FLS_SEED_FORCE=1: skipping cache, doing full rebuild"
else
    log "cache miss ($SEED_HASH); will rebuild + create $BAK_PATH"
fi

log "drop + create FLSTest"
run_sql_query "
IF DB_ID('FLSTest') IS NOT NULL
BEGIN
    ALTER DATABASE [FLSTest] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
    DROP DATABASE [FLSTest];
END;
CREATE DATABASE [FLSTest];
" master

run_sql_file "$ALTER_DIR/2 Alter Database.sql" FLSTest "2 Alter Database.sql"

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

if [[ -f "$ALTER_DIR/DataUpdate InOutbound-Routes.sql" ]]; then
    run_sql_file "$ALTER_DIR/DataUpdate InOutbound-Routes.sql" FLSTest "DataUpdate InOutbound-Routes.sql" 1
fi

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

run_sql_file "$INSERT_DIR/_test-fixture.sql" FLSTest "_test-fixture.sql"

log "post-condition counts:"
run_sql_query "SET NOCOUNT ON;
SELECT 'Clubs',            COUNT(*) FROM Clubs
UNION ALL SELECT 'ARFs(testclub)', COUNT(*) FROM AccountingRuleFilters WHERE ClubId='0FA7B76F-47BA-4138-8F96-671400FD7C83'
UNION ALL SELECT 'PersonCategories', COUNT(*) FROM PersonCategories
UNION ALL SELECT 'HistoricalFlights', COUNT(*) FROM Flights WHERE FlightDate < '2025-12-15'
UNION ALL SELECT 'SmtpIsMailpit',  CASE WHEN EXISTS(SELECT 1 FROM SystemData WHERE SmtpServer='localhost' AND SmtpPort=1025) THEN 1 ELSE 0 END;
" FLSTest

log "creating seed cache: $BAK_PATH"
run_sql_query "BACKUP DATABASE [FLSTest] TO DISK = N'$BAK_PATH' WITH FORMAT, INIT, COMPRESSION;" master >/dev/null || \
    run_sql_query "BACKUP DATABASE [FLSTest] TO DISK = N'$BAK_PATH' WITH FORMAT, INIT;" master >/dev/null
docker exec "$CONTAINER" bash -c "ls /var/opt/mssql/seed_*.bak 2>/dev/null | grep -v '^/var/opt/mssql/seed_${SEED_HASH}\\.bak\$' | xargs -r rm -f" || true

log "done."
