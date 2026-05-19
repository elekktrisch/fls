SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    i.name                                   AS index_name,
    i.type_desc                              AS index_type,
    CAST(i.is_unique AS BIT)                 AS is_unique,
    CAST(i.is_primary_key AS BIT)            AS is_primary_key,
    CAST(i.is_unique_constraint AS BIT)      AS is_unique_constraint,
    c.name                                   AS column_name,
    ic.key_ordinal                           AS key_ordinal,
    CAST(ic.is_descending_key AS BIT)        AS is_descending,
    CAST(ic.is_included_column AS BIT)       AS is_included_column,
    i.filter_definition                      AS filter_predicate
FROM sys.indexes i
JOIN sys.tables t          ON t.object_id = i.object_id
JOIN sys.schemas s         ON s.schema_id = t.schema_id
JOIN sys.index_columns ic  ON ic.object_id = i.object_id AND ic.index_id = i.index_id
JOIN sys.columns c         ON c.object_id = ic.object_id AND c.column_id = ic.column_id
WHERE i.type > 0 AND i.is_hypothetical = 0 AND t.is_ms_shipped = 0
ORDER BY schema_name, table_name, index_name, key_ordinal
