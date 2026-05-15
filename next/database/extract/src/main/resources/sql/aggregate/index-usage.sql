SELECT
    s.name                                                AS schema_name,
    t.name                                                AS table_name,
    i.name                                                AS index_name,
    COALESCE(ius.user_seeks, 0)                           AS user_seeks,
    COALESCE(ius.user_scans, 0)                           AS user_scans,
    COALESCE(ius.user_lookups, 0)                         AS user_lookups,
    COALESCE(ius.user_updates, 0)                         AS user_updates,
    (SELECT sqlserver_start_time FROM sys.dm_os_sys_info) AS sqlserver_start_time
FROM sys.indexes i
JOIN sys.tables t  ON t.object_id = i.object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
LEFT JOIN sys.dm_db_index_usage_stats ius
       ON ius.object_id = i.object_id AND ius.index_id = i.index_id AND ius.database_id = DB_ID()
WHERE i.type > 0 AND t.is_ms_shipped = 0
ORDER BY user_seeks DESC, user_scans DESC
