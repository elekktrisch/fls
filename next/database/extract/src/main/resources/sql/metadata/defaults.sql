SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    c.name                                   AS column_name,
    dc.name                                  AS constraint_name,
    dc.definition                            AS default_definition
FROM sys.default_constraints dc
JOIN sys.tables t  ON t.object_id = dc.parent_object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
JOIN sys.columns c ON c.object_id = dc.parent_object_id AND c.column_id = dc.parent_column_id
WHERE t.is_ms_shipped = 0
ORDER BY schema_name, table_name, column_name
