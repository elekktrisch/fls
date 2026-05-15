package ch.fls.legacyextract;

import java.nio.file.Path;

/**
 * Per-invocation extraction settings. Built from CLI args (production path)
 * or directly constructed by tests.
 *
 * @param allowAggregateCounts when true, the extractor runs aggregate queries
 *     against application tables (COUNT, APPROX_COUNT_DISTINCT, MAX(DATALENGTH))
 *     and emits {@code row-counts.json}, {@code storage-stats.json},
 *     {@code column-cardinality.json}, {@code index-sizes.json},
 *     {@code index-usage.json}, and {@code audit-log-sizing.json}. Off by
 *     default — schema metadata alone is the safe baseline.
 * @param allowProd required to run against a non-loopback {@code MSSQL_HOST}.
 *     The CLI's preflight rejects {@code db-prod.fls.internal} without it.
 * @param outDir directory the JSON files are written to. Conventionally
 *     {@code raw/} relative to the module root, but tests use a {@code @TempDir}.
 */
public record ExtractConfig(boolean allowAggregateCounts, boolean allowProd, Path outDir) {}
