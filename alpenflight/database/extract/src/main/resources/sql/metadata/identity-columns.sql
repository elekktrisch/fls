SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    c.name                                   AS column_name,
    CAST(ic.seed_value AS BIGINT)            AS seed_value,
    CAST(ic.increment_value AS BIGINT)       AS increment_value,
    CAST(ic.last_value AS BIGINT)            AS last_value,
    CAST(ic.is_not_for_replication AS BIT)   AS is_not_for_replication
FROM sys.identity_columns ic
JOIN sys.tables t  ON t.object_id = ic.object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE t.is_ms_shipped = 0
ORDER BY schema_name, table_name, column_name
