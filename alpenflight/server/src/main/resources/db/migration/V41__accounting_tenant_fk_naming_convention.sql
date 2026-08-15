ALTER TABLE t_accounting_rule_filter
    RENAME CONSTRAINT fk_arf_operating_club_id
        TO fk_accounting_rule_filter_operating_club_id;
