-- ShedLock's lock table, created ahead of the distributed-scheduler cutover
-- (S-018). The library is on the classpath and this table exists, but
-- @EnableSchedulerLock is deliberately NOT switched on: AlpenFlight runs
-- single-instance, so locking would add a failure mode without buying anything.
-- Shipping the table now keeps the cutover to a one-annotation change with no
-- migration in the critical path.
--
-- Column shape is ShedLock's own contract (JdbcTemplateLockProvider) — it is not
-- ours to model. Cross-tenant by nature: a scheduler lock is infrastructure, not
-- club data.
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);
