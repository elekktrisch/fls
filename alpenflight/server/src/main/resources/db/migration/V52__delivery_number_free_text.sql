
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
