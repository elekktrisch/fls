SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    tr.name                                  AS trigger_name,
    tr.type_desc                             AS trigger_type,
    CAST(tr.is_disabled AS BIT)              AS is_disabled,
    CAST(tr.is_instead_of_trigger AS BIT)    AS is_instead_of
FROM sys.triggers tr
JOIN sys.tables t  ON t.object_id = tr.parent_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
WHERE tr.parent_class = 1 AND t.is_ms_shipped = 0
ORDER BY schema_name, table_name, trigger_name
