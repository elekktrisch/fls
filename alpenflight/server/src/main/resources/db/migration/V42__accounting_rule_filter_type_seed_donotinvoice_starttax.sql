
INSERT INTO t_accounting_rule_filter_type (id, code, legacy_int_id, name, description) VALUES
    ('019e2e15-2c00-7658-8000-000000004658'::uuid, 'DO_NOT_INVOICE',  5, 'Do not invoice flight rule filter', 'Matching flight is excluded from invoicing'),
    ('019e2e15-2c00-7659-8000-000000004659'::uuid, 'START_TAX',      55, 'Start tax accounting rule filter',  'Emits a start-tax line item for matching flights');
