SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    fk.name                                  AS constraint_name,
    pc.name                                  AS column_name,
    rs.name                                  AS referenced_schema,
    rt.name                                  AS referenced_table,
    rc.name                                  AS referenced_column_name,
    fkc.constraint_column_id                 AS column_ordinal,
    fk.delete_referential_action_desc        AS on_delete,
    fk.update_referential_action_desc        AS on_update
FROM sys.foreign_keys fk
JOIN sys.tables t           ON t.object_id = fk.parent_object_id
JOIN sys.schemas s          ON s.schema_id = t.schema_id
JOIN sys.tables rt          ON rt.object_id = fk.referenced_object_id
JOIN sys.schemas rs         ON rs.schema_id = rt.schema_id
JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
JOIN sys.columns pc         ON pc.object_id = fk.parent_object_id AND pc.column_id = fkc.parent_column_id
JOIN sys.columns rc         ON rc.object_id = fk.referenced_object_id AND rc.column_id = fkc.referenced_column_id
WHERE t.is_ms_shipped = 0
ORDER BY schema_name, table_name, constraint_name, column_ordinal
