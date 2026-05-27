-- V1__baseline.sql
--
-- Intentionally empty baseline. Flyway's own `flyway_schema_history` table
-- is the source-of-truth for which migrations have run; a hand-maintained
-- sentinel duplicating that information drifts at the first missed update.
--
-- V2..V13 ship the actual schema. Convention: never amend a shipped
-- migration — ship V<N+1>.

-- (no DDL)
