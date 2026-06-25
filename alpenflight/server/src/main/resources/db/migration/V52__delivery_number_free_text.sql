-- Legacy Deliveries.DeliveryNumber is free-text NVARCHAR(100) (nullable until
-- booking; the legacy workflow job stamps values like "Workflow {timestamp}"
-- that never parse as an integer). V4 had modelled it as INTEGER with a per-club
-- gap-free partial UNIQUE + a separate legacy_delivery_number_text parity column
-- (V19). Both are wrong: the value is externally supplied (Proffix), collisions
-- exist, and there is no counter. Collapse to ONE nullable text column used by
-- both the native booking write and the migration mapper.

DROP INDEX IF EXISTS ux_dlv_club_number_partial;

ALTER TABLE t_delivery
    ALTER COLUMN delivery_number TYPE VARCHAR(100) USING delivery_number::text;

ALTER TABLE t_delivery
    DROP COLUMN legacy_delivery_number_text;

COMMENT ON COLUMN t_delivery.delivery_number IS
    'Free-text invoice number (legacy Deliveries.DeliveryNumber NVARCHAR(100)).'
    ' Externally supplied (Proffix) at booking; nullable until booked; no'
    ' counter, no UNIQUE — collisions exist in legacy data. Preserved verbatim'
    ' on migration.';
