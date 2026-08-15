
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
