package ch.alpenflight.migration.bundle;

import org.jspecify.annotations.Nullable;

public record ForeignKeyColumn(
        String column, EntityType target, @Nullable String disambiguatorColumn) {

    public ForeignKeyColumn {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("ForeignKeyColumn column must be non-blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("ForeignKeyColumn target must not be null");
        }
    }

    public ForeignKeyColumn(String column, EntityType target) {
        this(column, target, null);
    }
}
