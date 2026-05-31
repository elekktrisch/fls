-- S-059 — optimistic locking on flight.
--
-- Adds a JPA @Version column. The transition matrix (Java-side) is the
-- last writer; concurrent operator + bulk-job paths racing on the same
-- flight would otherwise silently last-writer-win and lose an audit row.
-- Bounded retry (1-2 attempts) at the application service is sufficient
-- per the performance plan — conflict rate is expected near zero in
-- normal use.
--
-- Per ADR 0022 directive 2 the column is purely structural — no CHECK,
-- no trigger, no generated-column math. The aggregate carries the
-- transition rules.
ALTER TABLE t_flight
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
