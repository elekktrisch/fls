SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    kc.name                                  AS constraint_name,
    c.name                                   AS column_name,
    ic.key_ordinal                           AS key_ordinal
FROM sys.key_constraints kc
JOIN sys.tables t          ON t.object_id = kc.parent_object_id
JOIN sys.schemas s         ON s.schema_id = t.schema_id
JOIN sys.indexes i         ON i.object_id = kc.parent_object_id AND i.index_id = kc.unique_index_id
JOIN sys.index_columns ic  ON ic.object_id = i.object_id AND ic.index_id = i.index_id
JOIN sys.columns c         ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE kc.type = 'PK' AND t.is_ms_shipped = 0
ORDER BY schema_name, table_name, key_ordinal
