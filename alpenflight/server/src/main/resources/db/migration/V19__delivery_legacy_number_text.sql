-- S-186 — preserves the original legacy Deliveries.DeliveryNumber text when
-- the value can't be parsed as INTEGER. V4 anticipated this column (see V4
-- header "the text format lives at S-016 in club_extension or as
-- delivery.legacy_delivery_number_text (parity column added on cutover)").
--
-- Migrated rows: at most one of {delivery_number, legacy_delivery_number_text}
-- is non-NULL — integer-parseable values populate delivery_number (subject to
-- the per-club gap-free UNIQUE), non-integer-parseable values populate this
-- column. New-write rows always leave legacy_delivery_number_text NULL.
--
-- Schema-deviation review (ADR 0022 directive 2): one nullable column, no
-- CHECK constraint encoding the at-most-one invariant — that's a domain rule
-- on Delivery.legacyNumberText / Delivery.deliveryNumber (S-064).

ALTER TABLE t_delivery
    ADD COLUMN legacy_delivery_number_text TEXT;

COMMENT ON COLUMN t_delivery.legacy_delivery_number_text IS
    'Raw legacy Deliveries.DeliveryNumber text when the value was not'
    ' Integer-parseable (e.g. "INV-2024-001"). Populated only on cutover'
    ' rows; always NULL for new writes. Mutually exclusive with'
    ' delivery_number — domain rule enforced on the Delivery aggregate'
    ' (S-064) per ADR 0022 D2. PII-adjacent (legal-record reference) —'
    ' @AuditRedact covers it.';
