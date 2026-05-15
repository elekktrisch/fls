SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    cc.name                                  AS constraint_name,
    cc.definition                            AS check_definition,
    CAST(cc.is_disabled AS BIT)              AS is_disabled
FROM sys.check_constraints cc
JOIN sys.tables t  ON t.object_id = cc.parent_object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
WHERE t.is_ms_shipped = 0
ORDER BY schema_name, table_name, constraint_name
