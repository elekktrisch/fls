UPDATE t_planning_day
SET planning_date = CURRENT_DATE + 3
        + CASE EXTRACT(ISODOW FROM CURRENT_DATE + 3)::int
              WHEN 6 THEN 2
              WHEN 7 THEN 1
              ELSE 0
          END
WHERE id = '019e30c3-2c00-7001-8000-000000000e01';
