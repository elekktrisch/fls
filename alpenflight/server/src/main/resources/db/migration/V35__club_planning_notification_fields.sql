-- =============================================================================
-- J-6 T-10b: the planning-notification fields the PlanningDayNotificationJob
-- (T-10c) reads off the Club aggregate.
--
-- `send_planning_day_info_mail_to` (the club's planning-notification address(es),
-- legacy Club.SendPlanningDayInfoMailTo) ALREADY EXISTS from the V2 S-014
-- baseline (t_club.send_planning_day_info_mail_to VARCHAR(250)) — it is only
-- mapped onto the Club aggregate in T-10b, no DDL needed.
--
-- This migration adds the one genuinely-missing column:
--
--   use_planning_day_without_reservations   governs the ok-vs-cancel rule when a
--                                           planning day has no reservation
--                                           (legacy ClubUsePlanningDayWithout-
--                                           Reservations setting): true ⇒ still
--                                           send the "takes place" mail; false ⇒
--                                           send the "cancelled" mail.
--
-- Per ADR 0022 directive 2: schema is STRUCTURAL only — the column + its boolean
-- default. The ok/cancel rule itself is NOT in SQL; it lives on the Club
-- aggregate (Club.shouldSendPlanningDayOk(boolean)).
-- =============================================================================

ALTER TABLE t_club
    ADD COLUMN use_planning_day_without_reservations BOOLEAN NOT NULL DEFAULT false;
