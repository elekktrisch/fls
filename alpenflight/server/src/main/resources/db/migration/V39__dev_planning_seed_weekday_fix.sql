-- V39 — pin the V34 "weekday" seed planning day to an actual weekday.
--
-- V34 seeded day …0e01 at a naive CURRENT_DATE + 3, which lands on Saturday /
-- Sunday when the migration runs on a Wednesday / Thursday — and the J-6
-- planning-list e2e (planning-list.spec.ts:167) plus the seed's own contract
-- (PlanningDevSeedIT names it WEEKDAY_DAY_ID) require data-weekend=false on
-- that row. V34's sibling day …0e02 already does proper next-Saturday
-- targeting; this applies the matching weekday targeting to …0e01.
-- (V34 is applied on long-lived databases, so it cannot be edited in place —
-- Flyway checksum. Idempotent UPDATE, dev-seed data only.)
UPDATE t_planning_day
SET planning_date = CURRENT_DATE + 3
        + CASE EXTRACT(ISODOW FROM CURRENT_DATE + 3)::int
              WHEN 6 THEN 2  -- Saturday  -> following Monday
              WHEN 7 THEN 1  -- Sunday    -> following Monday
              ELSE 0
          END
WHERE id = '019e30c3-2c00-7001-8000-000000000e01';
