-- Audit-log sizing breakdown — runs only if both AuditLogs and
-- AuditLogDetails are present (caller checks via tables.json first).
-- Returns one row per audit table with row count, storage MB, avg row size.
-- Blob-column DATALENGTH stats are produced by separate per-column queries
-- the caller runs against AuditLogDetails specifically (OriginalValue +
-- NewValue) — those land in audit-log-blob-stats.json via the Java code.
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
