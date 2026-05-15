SELECT
    s.name           AS schema_name,
    t.name           AS table_name,
    'TABLE'          AS object_type
FROM sys.tables t
JOIN sys.schemas s ON s.schema_id = t.schema_id
WHERE t.is_ms_shipped = 0
UNION ALL
SELECT
    s.name           AS schema_name,
    v.name           AS table_name,
    'VIEW'           AS object_type
FROM sys.views v
JOIN sys.schemas s ON s.schema_id = v.schema_id
WHERE v.is_ms_shipped = 0
ORDER BY schema_name, table_name
