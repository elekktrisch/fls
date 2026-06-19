-- V45 — keep the V34 bare weekend seed day off the notification job's day+1 slot.
--
-- V34 seeded the crewless, reservation-less weekend day ...0e02 at the next
-- Saturday strictly after today. On a Friday apply that Saturday IS today+1, so
-- the PlanningDayNotificationJob imminent (today+1) pass mails the club address a
-- planningday-CANCEL ("Flugbetriebstag abgesagt") for it — a stray template at the
-- shared seed-club-1 notification address (flugbetrieb@seed-club-1.example) that
-- collides with the run-now mailpit contract (which expects only "findet statt"
-- there). Push it to the next Saturday that is at least 2 days out: when the next
-- Saturday is tomorrow, advance one more week. Stays a Saturday + future, so the
-- weekend-flag render + PlanningDevSeedIT contract hold.
-- (V34 is applied on long-lived databases, so it cannot be edited in place —
-- Flyway checksum. Idempotent UPDATE, dev-seed data only.)
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
