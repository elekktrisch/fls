-- S-155 boyscout: refresh aggregate-root id `COMMENT ON COLUMN` text after
-- the typed-ID external form changed from `<prefix>_<crockford-base32>` to
-- `<prefix>-<uuid>`. ADR 0019 amended in the same change. Comment-only
-- update — no DDL, no DML.
--
-- V2/V3/V4 carry the original (now stale) comments; migrations are
-- immutable per CONVENTIONS.md, so we overwrite via a new migration.

COMMENT ON COLUMN person.id IS
    'UUID v7. Aggregate root (ADR 0018). External form psn-<uuid>. See ADR 0019.';
COMMENT ON COLUMN club.id IS
    'UUID v7. Aggregate root (ADR 0018). External form clb-<uuid>. See ADR 0019.';
COMMENT ON COLUMN "user".id IS
    'UUID v7. Aggregate root (ADR 0018). External form usr-<uuid>. See ADR 0019.';

COMMENT ON COLUMN flight.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: flt-<uuid>. See ADR 0019.';
COMMENT ON COLUMN aircraft.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: acf-<uuid>. See ADR 0019.';
COMMENT ON COLUMN location.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: loc-<uuid>. See ADR 0019. Cross-tenant shared resource (per S-011 sacred cow); SYSTEM_ADMIN-only mutation.';
COMMENT ON COLUMN flight_type.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: fty-<uuid>. See ADR 0019.';
COMMENT ON COLUMN article.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: art-<uuid>. See ADR 0019.';

COMMENT ON COLUMN aircraft_reservation.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arv-<uuid>. See ADR 0019.';
COMMENT ON COLUMN planning_day.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: pln-<uuid>. See ADR 0019.';
COMMENT ON COLUMN accounting_rule_filter.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arf-<uuid>. See ADR 0019.';
COMMENT ON COLUMN delivery.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dlv-<uuid>. See ADR 0019.';
COMMENT ON COLUMN delivery_creation_test.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dct-<uuid>. See ADR 0019.';
