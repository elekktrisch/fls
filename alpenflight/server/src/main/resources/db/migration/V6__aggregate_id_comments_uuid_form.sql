
COMMENT ON COLUMN t_person.id IS
    'UUID v7. Aggregate root (ADR 0018). External form psn-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_club.id IS
    'UUID v7. Aggregate root (ADR 0018). External form clb-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_user.id IS
    'UUID v7. Aggregate root (ADR 0018). External form usr-<uuid>. See ADR 0019.';

COMMENT ON COLUMN t_flight.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: flt-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_aircraft.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: acf-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_location.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: loc-<uuid>. See ADR 0019. Cross-tenant shared resource (per S-011 sacred cow); SYSTEM_ADMIN-only mutation.';
COMMENT ON COLUMN t_flight_type.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: fty-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_article.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: art-<uuid>. See ADR 0019.';

COMMENT ON COLUMN t_aircraft_reservation.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arv-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_planning_day.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: pln-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_accounting_rule_filter.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: arf-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_delivery.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dlv-<uuid>. See ADR 0019.';
COMMENT ON COLUMN t_delivery_creation_test.id IS
    'UUID v7. Aggregate root (ADR 0018). External form: dct-<uuid>. See ADR 0019.';
