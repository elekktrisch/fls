-- =============================================================================
-- V42 — seed the two missing accounting-rule filter types: 5 (DoNotInvoice) +
--       55 (StartTax)
-- =============================================================================
-- J-8 T-09. The V4 seed (`reservations_planning_accounting`) populated
-- t_accounting_rule_filter_type with only the 8 line-item / recipient-routing
-- filter types legacy_int_id ∈ {10,20,30,40,50,60,70,80}. The legacy
-- AccountingRuleFilterTypeId enum (oracle) also defines 5 = DoNotInvoice and
-- 55 = StartTax, and a real club may hold AccountingRuleFilter rows of those
-- types. The migration mapper emits filter_type_id via the legacy_int_id and
-- the ingest pipeline resolves it against this table by
-- `WHERE legacy_int_id = <n>` (ux_arft_legacy_int_id point lookup); a type-5/55
-- row with no seeded reference here would FK-fail (23503 / fk_arf_filter_type_id)
-- at fanout. This additive seed closes that gap so all 10 filter-type legacy
-- ids resolve.
--
-- Pinned UUIDs continue the V4 canonical-UUIDv7 family
-- (`accounting_rule_filter_type` offset 18_000 in
-- server/src/test/resources/scripts/GenerateCanonicalUuids.java): the V4 rows
-- occupy indices 0..7 (…4650 … …4657); these two take the next free indices
-- 8 (uuidV7(18008) = …4658) and 9 (uuidV7(18009) = …4659). Ground truth:
-- server/src/test/resources/reference-seeds-canonical-uuids.json. Re-running the
-- generator must reproduce these bit-identically; do NOT regenerate after ship.
--
-- ux_arft_code + ux_arft_legacy_int_id are UNIQUE — the new codes
-- (DO_NOT_INVOICE / START_TAX) and legacy ids (5 / 55) do not collide with the
-- V4 set.

INSERT INTO t_accounting_rule_filter_type (id, code, legacy_int_id, name, description) VALUES
    ('019e2e15-2c00-7658-8000-000000004658'::uuid, 'DO_NOT_INVOICE',  5, 'Do not invoice flight rule filter', 'Matching flight is excluded from invoicing'),
    ('019e2e15-2c00-7659-8000-000000004659'::uuid, 'START_TAX',      55, 'Start tax accounting rule filter',  'Emits a start-tax line item for matching flights');
