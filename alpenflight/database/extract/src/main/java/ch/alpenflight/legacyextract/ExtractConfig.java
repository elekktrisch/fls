package ch.alpenflight.legacyextract;

import java.nio.file.Path;

public record ExtractConfig(boolean allowAggregateCounts, boolean allowProd, Path outDir) {}
