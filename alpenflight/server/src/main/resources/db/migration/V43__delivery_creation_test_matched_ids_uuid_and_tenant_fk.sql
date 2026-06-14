-- Two forward-only changes to the (empty, unmigrated) delivery-creation-test
-- harness substrate so J-9's domain code can map it.
--
-- 1. matched-filter-id arrays BIGINT[] -> UUID[].
--    V4 typed `expected_matched_filter_ids` / `last_test_matched_filter_ids` as
--    BIGINT[] for a legacy-int-id cutover that never happened: the J-9 engine and
--    the J-8 AccountingRuleFilter use UUID ids, and the harness's matched-rule
--    links target /accountingrules/<uuid>. A BIGINT[] cannot hold a UUID, so the
--    columns are retyped. Safe as a plain ALTER TYPE because both tables are
--    empty dev-substrate (no producer binding exists — DeliveryMapper is
--    KNOWN_UNBOUND and targets t_delivery, not these tables); confirmed
--    zero-row before writing this. USING is a no-op cast over the empty set.
--
-- 2. tenant-FK constraint rename to the convention name.
--    The S-024 leakage sweep reconstructs each @TenantId aggregate's tenant FK as
--    `fk_<t_-stripped-table>_<tenant-col>`; V4 used the ad-hoc abbreviation
--    `fk_dct_operating_club_id`, so the sweep can't pin the breach without this
--    realignment (same forward-migration fix V32/V33/V41 made for the other
--    tenant-scoped aggregates; V4 is already applied across environments, so an
--    in-place amend would break Flyway checksum validation).

ALTER TABLE t_delivery_creation_test
    ALTER COLUMN expected_matched_filter_ids DROP DEFAULT,
    ALTER COLUMN expected_matched_filter_ids
        TYPE UUID[] USING expected_matched_filter_ids::text[]::uuid[],
    ALTER COLUMN expected_matched_filter_ids SET DEFAULT '{}',
    ALTER COLUMN last_test_matched_filter_ids
        TYPE UUID[] USING last_test_matched_filter_ids::text[]::uuid[];

ALTER TABLE t_delivery_creation_test
    RENAME CONSTRAINT fk_dct_operating_club_id
        TO fk_delivery_creation_test_operating_club_id;
