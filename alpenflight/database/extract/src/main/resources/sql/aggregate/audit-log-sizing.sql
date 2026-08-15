SELECT
    t.name                                                AS table_name,
    SUM(CASE WHEN ps.index_id IN (0,1) THEN ps.row_count ELSE 0 END) AS row_count,
    SUM(ps.used_page_count) * 8.0 / 1024.0                AS used_mb,
    CASE
        WHEN SUM(CASE WHEN ps.index_id IN (0,1) THEN ps.row_count ELSE 0 END) = 0 THEN 0
        ELSE SUM(ps.used_page_count) * 8192.0
             / SUM(CASE WHEN ps.index_id IN (0,1) THEN ps.row_count ELSE 0 END)
    END                                                   AS avg_row_bytes
FROM sys.dm_db_partition_stats ps
JOIN sys.tables t ON t.object_id = ps.object_id
WHERE t.name IN (N'AuditLogs', N'AuditLogDetails')
GROUP BY t.name
ORDER BY t.name
