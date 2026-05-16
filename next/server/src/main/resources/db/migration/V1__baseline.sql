-- V1__baseline.sql
--
-- S-009 placeholder baseline. S-012 ships V2__identity_and_reference.sql on
-- top; S-013 / S-014 add the real schema in V3+ / V4+.
--
-- This table is intentionally SYSTEM_GLOBAL (no club_id): it tracks schema
-- generation only. Classified accordingly in next/database/tenant-rules.yaml
-- so S-011's classifier doesn't emit UNKNOWN.
--
-- Once V1 is applied to any environment its checksum is locked. Adding /
-- removing / amending content here would require flyway:repair on every
-- affected DB. Convention: never amend a shipped migration — ship V2.

CREATE TABLE app_meta (
    meta_key   VARCHAR(64)  PRIMARY KEY,
    meta_value VARCHAR(255) NOT NULL
);

INSERT INTO app_meta (meta_key, meta_value) VALUES ('schema_baseline_version', 'S-009');
