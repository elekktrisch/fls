SELECT DISTINCT
    s.name           AS schema_name,
    t.name           AS table_name,
    c.name           AS column_name
FROM sys.indexes i
JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
JOIN sys.columns c        ON c.object_id = ic.object_id AND c.column_id = ic.column_id
JOIN sys.tables t         ON t.object_id = i.object_id
JOIN sys.schemas s        ON s.schema_id = t.schema_id
WHERE i.type > 0 AND i.is_primary_key = 0 AND t.is_ms_shipped = 0
ORDER BY schema_name, table_name, column_name
