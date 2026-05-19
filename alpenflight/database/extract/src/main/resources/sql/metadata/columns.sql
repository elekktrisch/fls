SELECT
    s.name                                   AS schema_name,
    t.name                                   AS table_name,
    c.name                                   AS column_name,
    c.column_id                              AS ordinal,
    ty.name                                  AS data_type,
    CAST(c.is_nullable AS BIT)               AS is_nullable,
    OBJECT_DEFINITION(c.default_object_id)   AS default_expression,
    CAST(c.is_identity AS BIT)               AS is_identity,
    CAST(c.is_computed AS BIT)               AS is_computed,
    CASE
        WHEN ty.name IN (N'nvarchar', N'nchar') AND c.max_length > 0 THEN c.max_length / 2
        WHEN ty.name IN (N'varchar', N'char', N'binary', N'varbinary') THEN c.max_length
        ELSE NULL
    END                                      AS max_length,
    CASE WHEN c.precision = 0 THEN NULL ELSE c.precision END AS [precision],
    CASE WHEN c.scale = 0 AND ty.name NOT IN (N'decimal', N'numeric') THEN NULL ELSE c.scale END AS scale
FROM sys.columns c
JOIN sys.tables t  ON t.object_id = c.object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
JOIN sys.types ty  ON ty.user_type_id = c.user_type_id
WHERE t.is_ms_shipped = 0
ORDER BY schema_name, table_name, ordinal
