
ALTER TABLE t_delivery
    ADD COLUMN legacy_delivery_number_text TEXT;

COMMENT ON COLUMN t_delivery.legacy_delivery_number_text IS
    'Raw legacy Deliveries.DeliveryNumber text when the value was not'
    ' Integer-parseable (e.g. "INV-2024-001"). Populated only on cutover'
    ' rows; always NULL for new writes. Mutually exclusive with'
    ' delivery_number — domain rule enforced on the Delivery aggregate'
    ' (S-064) per ADR 0022 D2. PII-adjacent (legal-record reference) —'
    ' @AuditRedact covers it.';
