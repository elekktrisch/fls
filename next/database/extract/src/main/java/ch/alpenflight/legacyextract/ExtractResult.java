package ch.alpenflight.legacyextract;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Summary of one extraction run. Returned from
 * {@link MetadataExtractor#extractTo(ExtractConfig)} so the CLI can log the
 * results and tests can assert against them.
 */
public record ExtractResult(Path outDir, List<Path> emittedFiles, Duration duration) {}
