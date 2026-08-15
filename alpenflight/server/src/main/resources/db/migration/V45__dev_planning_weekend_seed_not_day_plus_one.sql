UPDATE t_planning_day
SET planning_date = (CURRENT_DATE
        + ((6 - EXTRACT(ISODOW FROM CURRENT_DATE)::int + 7) % 7
           + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE)::int = 6 THEN 7 ELSE 0 END))
        + CASE
              WHEN (CURRENT_DATE
                    + ((6 - EXTRACT(ISODOW FROM CURRENT_DATE)::int + 7) % 7
                       + CASE WHEN EXTRACT(ISODOW FROM CURRENT_DATE)::int = 6 THEN 7 ELSE 0 END))
                   = CURRENT_DATE + 1
              THEN 7 ELSE 0
          END
WHERE id = '019e30c3-2c00-7001-8000-000000000e02';
