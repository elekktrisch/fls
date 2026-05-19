SELECT
    s.name                                                AS schema_name,
    t.name                                                AS table_name,
    SUM(CASE WHEN ps.index_id IN (0,1) THEN ps.used_page_count ELSE 0 END) * 8.0 / 1024.0 AS data_mb,
    SUM(CASE WHEN ps.index_id NOT IN (0,1) THEN ps.used_page_count ELSE 0 END) * 8.0 / 1024.0 AS index_mb,
    (SUM(ps.reserved_page_count) - SUM(ps.used_page_count)) * 8.0 / 1024.0 AS unused_mb,
    SUM(ps.reserved_page_count) * 8.0 / 1024.0            AS total_mb
FROM sys.dm_db_partition_stats ps
JOIN sys.tables t  ON t.object_id = ps.object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
WHERE t.is_ms_shipped = 0
GROUP BY s.name, t.name
ORDER BY total_mb DESC
