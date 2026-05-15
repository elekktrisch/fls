SELECT
    s.name                                                AS schema_name,
    t.name                                                AS table_name,
    i.name                                                AS index_name,
    ips.page_count                                        AS page_count,
    ips.page_count * 8.0 / 1024.0                         AS size_mb,
    ips.avg_fragmentation_in_percent                      AS fragmentation_pct
FROM sys.dm_db_index_physical_stats(DB_ID(), NULL, NULL, NULL, 'LIMITED') ips
JOIN sys.tables t  ON t.object_id = ips.object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
JOIN sys.indexes i ON i.object_id = ips.object_id AND i.index_id = ips.index_id
WHERE i.type > 0 AND t.is_ms_shipped = 0
ORDER BY size_mb DESC
