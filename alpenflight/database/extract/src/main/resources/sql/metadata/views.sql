SELECT
    s.name                                   AS schema_name,
    v.name                                   AS view_name,
    CAST(1 AS BIT)                           AS definition_present,
    CAST(v.is_ms_shipped AS BIT)             AS is_ms_shipped
FROM sys.views v
JOIN sys.schemas s ON s.schema_id = v.schema_id
WHERE v.is_ms_shipped = 0
ORDER BY schema_name, view_name
