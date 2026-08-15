package ch.alpenflight.migration.bundle;

public record ReferenceLookup(String column, String seedTable) {

    public ReferenceLookup {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("ReferenceLookup column must be non-blank");
        }
        if (seedTable == null || seedTable.isBlank()) {
            throw new IllegalArgumentException("ReferenceLookup seedTable must be non-blank");
        }
    }
}
